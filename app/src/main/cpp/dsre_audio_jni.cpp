#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

namespace {
std::mutex g_error_mutex;
std::string g_last_error;
std::atomic_bool g_cancelled{false};

std::string ff_error(int code) {
    char buffer[AV_ERROR_MAX_STRING_SIZE]{};
    av_strerror(code, buffer, sizeof(buffer));
    return std::string(buffer);
}

void set_error(const std::string& value) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = value;
    __android_log_print(ANDROID_LOG_ERROR, "DSRE-FFmpeg", "%s", value.c_str());
}

struct InputCloser { void operator()(AVFormatContext* p) const { if (p) avformat_close_input(&p); } };
struct CodecCloser { void operator()(AVCodecContext* p) const { if (p) avcodec_free_context(&p); } };
struct PacketCloser { void operator()(AVPacket* p) const { if (p) av_packet_free(&p); } };
struct FrameCloser { void operator()(AVFrame* p) const { if (p) av_frame_free(&p); } };
struct SwrCloser { void operator()(SwrContext* p) const { if (p) swr_free(&p); } };
struct SwsCloser { void operator()(SwsContext* p) const { if (p) sws_freeContext(p); } };
struct FifoCloser { void operator()(AVAudioFifo* p) const { if (p) av_audio_fifo_free(p); } };

struct CopiedCover {
    AVPacket* packet = nullptr;
    int stream_index = -1;
};

void free_copied_cover(CopiedCover& cover) {
    if (cover.packet) av_packet_free(&cover.packet);
    cover.stream_index = -1;
}

int rgb_to_yuv420p(const AVFrame* source, AVFrame* destination) {
    if (!source || !destination || source->width <= 0 || source->height <= 0) return AVERROR(EINVAL);
    const auto format = static_cast<AVPixelFormat>(source->format);
    const int width = source->width;
    const int height = source->height;
    auto clamp_byte = [](int value) -> uint8_t {
        return static_cast<uint8_t>(std::clamp(value, 0, 255));
    };
    auto rgb_at = [&](int x, int y, int& r, int& g, int& b) -> bool {
        const uint8_t* row = source->data[0] + static_cast<ptrdiff_t>(y) * source->linesize[0];
        switch (format) {
            case AV_PIX_FMT_RGB24: r=row[x*3]; g=row[x*3+1]; b=row[x*3+2]; return true;
            case AV_PIX_FMT_BGR24: b=row[x*3]; g=row[x*3+1]; r=row[x*3+2]; return true;
            case AV_PIX_FMT_RGBA: r=row[x*4]; g=row[x*4+1]; b=row[x*4+2]; return true;
            case AV_PIX_FMT_BGRA: b=row[x*4]; g=row[x*4+1]; r=row[x*4+2]; return true;
            case AV_PIX_FMT_ARGB: r=row[x*4+1]; g=row[x*4+2]; b=row[x*4+3]; return true;
            case AV_PIX_FMT_ABGR: b=row[x*4+1]; g=row[x*4+2]; r=row[x*4+3]; return true;
            case AV_PIX_FMT_GRAY8: r=g=b=row[x]; return true;
            default: return false;
        }
    };
    int probe_r=0, probe_g=0, probe_b=0;
    if (!rgb_at(0, 0, probe_r, probe_g, probe_b)) return AVERROR(ENOSYS);
    for (int y=0; y<height; ++y) {
        uint8_t* y_row = destination->data[0] + static_cast<ptrdiff_t>(y) * destination->linesize[0];
        for (int x=0; x<width; ++x) {
            int r,g,b; rgb_at(x,y,r,g,b);
            y_row[x]=clamp_byte(((66*r + 129*g + 25*b + 128) >> 8) + 16);
        }
    }
    for (int y=0; y<height; y+=2) {
        uint8_t* u_row=destination->data[1]+static_cast<ptrdiff_t>(y/2)*destination->linesize[1];
        uint8_t* v_row=destination->data[2]+static_cast<ptrdiff_t>(y/2)*destination->linesize[2];
        for (int x=0; x<width; x+=2) {
            int sum_r=0,sum_g=0,sum_b=0,count=0;
            for(int dy=0;dy<2;++dy) for(int dx=0;dx<2;++dx) if(x+dx<width && y+dy<height){int r,g,b;rgb_at(x+dx,y+dy,r,g,b);sum_r+=r;sum_g+=g;sum_b+=b;++count;}
            const int r=sum_r/count,g=sum_g/count,b=sum_b/count;
            u_row[x/2]=clamp_byte(((-38*r - 74*g + 112*b + 128) >> 8) + 128);
            v_row[x/2]=clamp_byte(((112*r - 94*g - 18*b + 128) >> 8) + 128);
        }
    }
    return 0;
}

int convert_cover_to_mjpeg(const AVStream* source, AVPacket** converted_packet,
                           int* width, int* height) {
    if (!source || !source->codecpar || source->attached_pic.size <= 0 ||
        !converted_packet || !width || !height) return AVERROR(EINVAL);
    *converted_packet=nullptr;
    const AVCodec* decoder_codec=avcodec_find_decoder(source->codecpar->codec_id);
    const AVCodec* encoder_codec=avcodec_find_encoder(AV_CODEC_ID_MJPEG);
    if(!decoder_codec || !encoder_codec) return AVERROR_DECODER_NOT_FOUND;
    std::unique_ptr<AVCodecContext,CodecCloser> decoder(avcodec_alloc_context3(decoder_codec));
    if(!decoder) return AVERROR(ENOMEM);
    int ret=avcodec_parameters_to_context(decoder.get(),source->codecpar); if(ret<0)return ret;
    if((ret=avcodec_open2(decoder.get(),decoder_codec,nullptr))<0)return ret;
    std::unique_ptr<AVFrame,FrameCloser> decoded(av_frame_alloc()); if(!decoded)return AVERROR(ENOMEM);
    AVPacket input{}; if((ret=av_packet_ref(&input,&source->attached_pic))<0)return ret;
    ret=avcodec_send_packet(decoder.get(),&input); av_packet_unref(&input); if(ret<0)return ret;
    if((ret=avcodec_receive_frame(decoder.get(),decoded.get()))<0)return ret;

    std::unique_ptr<AVCodecContext,CodecCloser> encoder(avcodec_alloc_context3(encoder_codec));
    if(!encoder)return AVERROR(ENOMEM);
    encoder->width=decoded->width; encoder->height=decoded->height;
    encoder->time_base={1,25}; encoder->framerate={25,1};
    encoder->pix_fmt=AV_PIX_FMT_YUVJ420P; encoder->color_range=AVCOL_RANGE_JPEG;
    encoder->thread_count=1; encoder->thread_type=0;
    if((ret=avcodec_open2(encoder.get(),encoder_codec,nullptr))<0)return ret;
    std::unique_ptr<AVFrame,FrameCloser> converted(av_frame_alloc()); if(!converted)return AVERROR(ENOMEM);
    converted->format=encoder->pix_fmt; converted->width=encoder->width; converted->height=encoder->height;
    converted->color_range=AVCOL_RANGE_JPEG;
    if((ret=av_frame_get_buffer(converted.get(),32))<0)return ret;
    if((ret=av_frame_make_writable(converted.get()))<0)return ret;
    std::unique_ptr<SwsContext,SwsCloser> scaler(
        sws_getContext(
            decoded->width,
            decoded->height,
            static_cast<AVPixelFormat>(decoded->format),
            converted->width,
            converted->height,
            encoder->pix_fmt,
            SWS_BICUBIC,
            nullptr,
            nullptr,
            nullptr
        )
    );
    if(!scaler) return AVERROR(EINVAL);
    const int scaled_height=sws_scale(
        scaler.get(),
        decoded->data,
        decoded->linesize,
        0,
        decoded->height,
        converted->data,
        converted->linesize
    );
    if(scaled_height!=converted->height) return scaled_height<0?scaled_height:AVERROR(EIO);
    converted->pts=0;
    if((ret=avcodec_send_frame(encoder.get(),converted.get()))<0)return ret;
    AVPacket* packet=av_packet_alloc(); if(!packet)return AVERROR(ENOMEM);
    ret=avcodec_receive_packet(encoder.get(),packet);
    if(ret<0){av_packet_free(&packet);return ret;}
    packet->pts=0;packet->dts=0;packet->duration=0;packet->flags|=AV_PKT_FLAG_KEY;
    *width=encoder->width;*height=encoder->height;*converted_packet=packet;
    return 0;
}

int attach_cover_stream(AVFormatContext* output, const AVStream* source,
                        AVCodecID codec_id, AVPacket* packet, int width, int height,
                        CopiedCover& cover) {
    AVStream* destination=avformat_new_stream(output,nullptr); if(!destination)return AVERROR(ENOMEM);
    destination->codecpar->codec_type=AVMEDIA_TYPE_VIDEO;
    destination->codecpar->codec_id=codec_id;
    destination->codecpar->width=width;destination->codecpar->height=height;
    destination->time_base={1,90000}; destination->disposition|=AV_DISPOSITION_ATTACHED_PIC;
    av_dict_copy(&destination->metadata,source->metadata,0);
    av_dict_set(&destination->metadata,"title","Album cover",AV_DICT_DONT_OVERWRITE);
    av_dict_set(&destination->metadata,"comment","Cover (front)",AV_DICT_DONT_OVERWRITE);
    int ret=av_packet_ref(&destination->attached_pic,packet); if(ret<0)return ret;
    destination->attached_pic.stream_index=destination->index;
    destination->attached_pic.pts=0;destination->attached_pic.dts=0;destination->attached_pic.duration=0;
    destination->attached_pic.flags|=AV_PKT_FLAG_KEY;
    cover.packet=av_packet_clone(&destination->attached_pic); if(!cover.packet)return AVERROR(ENOMEM);
    cover.stream_index=destination->index;cover.packet->stream_index=destination->index;
    return 0;
}

int copy_metadata_and_cover(const char* original_path, AVFormatContext* output,
                            AVStream* output_audio_stream, CopiedCover& cover) {
    if (!original_path || !*original_path || !output || !output_audio_stream) return 0;
    AVFormatContext* raw_original=nullptr;
    int ret=avformat_open_input(&raw_original,original_path,nullptr,nullptr);
    if(ret<0||!raw_original){__android_log_print(ANDROID_LOG_WARN,"DSRE-FFmpeg","Metadata source open skipped: %s",ff_error(ret).c_str());return 0;}
    std::unique_ptr<AVFormatContext,InputCloser> original(raw_original);
    if((ret=avformat_find_stream_info(original.get(),nullptr))<0){__android_log_print(ANDROID_LOG_WARN,"DSRE-FFmpeg","Metadata stream info skipped: %s",ff_error(ret).c_str());return 0;}
    av_dict_copy(&output->metadata,original->metadata,0);
    for(unsigned int i=0;i<original->nb_streams;++i){AVStream* source=original->streams[i];if(source&&source->codecpar&&source->codecpar->codec_type==AVMEDIA_TYPE_AUDIO){av_dict_copy(&output_audio_stream->metadata,source->metadata,0);break;}}
    for(unsigned int i=0;i<original->nb_streams;++i){
        AVStream* source=original->streams[i]; if(!source||!source->codecpar)continue;
        const bool picture=(source->disposition&AV_DISPOSITION_ATTACHED_PIC)!=0||(source->codecpar->codec_type==AVMEDIA_TYPE_VIDEO&&source->attached_pic.size>0);
        if(!picture||source->attached_pic.size<=0)continue;
        const std::string muxer_name = output->oformat && output->oformat->name ? output->oformat->name : "";
        const bool force_mjpeg = muxer_name.find("mp3") != std::string::npos ||
            muxer_name.find("ipod") != std::string::npos ||
            muxer_name.find("mp4") != std::string::npos ||
            muxer_name.find("mov") != std::string::npos;
        if(!force_mjpeg && avformat_query_codec(output->oformat,source->codecpar->codec_id,FF_COMPLIANCE_NORMAL)>0){
            AVPacket direct{}; if((ret=av_packet_ref(&direct,&source->attached_pic))<0)return ret;
            ret=attach_cover_stream(output,source,source->codecpar->codec_id,&direct,source->codecpar->width,source->codecpar->height,cover);
            av_packet_unref(&direct); return ret;
        }
        AVPacket* mjpeg=nullptr;int width=0,height=0;
        ret=convert_cover_to_mjpeg(source,&mjpeg,&width,&height);
        if(ret<0){__android_log_print(ANDROID_LOG_WARN,"DSRE-FFmpeg","Cover conversion skipped: %s",ff_error(ret).c_str());return 0;}
        if(avformat_query_codec(output->oformat,AV_CODEC_ID_MJPEG,FF_COMPLIANCE_NORMAL)<=0){av_packet_free(&mjpeg);__android_log_print(ANDROID_LOG_WARN,"DSRE-FFmpeg","Output muxer does not support MJPEG cover");return 0;}
        ret=attach_cover_stream(output,source,AV_CODEC_ID_MJPEG,mjpeg,width,height,cover);av_packet_free(&mjpeg);return ret;
    }
    return 0;
}

int write_packets(AVCodecContext* encoder, AVFormatContext* output, AVStream* stream, AVPacket* packet) {
    while (true) {
        int ret = avcodec_receive_packet(encoder, packet);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) return 0;
        if (ret < 0) return ret;
        av_packet_rescale_ts(packet, encoder->time_base, stream->time_base);
        packet->stream_index = stream->index;
        ret = av_interleaved_write_frame(output, packet);
        av_packet_unref(packet);
        if (ret < 0) return ret;
    }
}

int push_converted(AVAudioFifo* fifo, SwrContext* swr, const AVFrame* decoded,
                   AVSampleFormat out_fmt, int out_channels, int out_rate) {
    const int max_samples = av_rescale_rnd(
        swr_get_delay(swr, decoded->sample_rate) + decoded->nb_samples,
        out_rate, decoded->sample_rate, AV_ROUND_UP);
    uint8_t** converted = nullptr;
    int linesize = 0;
    int ret = av_samples_alloc_array_and_samples(&converted, &linesize, out_channels,
                                                  max_samples, out_fmt, 0);
    if (ret < 0) return ret;
    const int samples = swr_convert(swr, converted, max_samples,
                                    const_cast<const uint8_t**>(decoded->extended_data),
                                    decoded->nb_samples);
    if (samples < 0) {
        av_freep(&converted[0]); av_freep(&converted);
        return samples;
    }
    ret = av_audio_fifo_realloc(fifo, av_audio_fifo_size(fifo) + samples);
    if (ret >= 0 && av_audio_fifo_write(fifo, reinterpret_cast<void**>(converted), samples) < samples)
        ret = AVERROR_UNKNOWN;
    av_freep(&converted[0]); av_freep(&converted);
    return ret < 0 ? ret : 0;
}

int encode_fifo(AVAudioFifo* fifo, AVCodecContext* encoder, AVFormatContext* output,
                AVStream* stream, AVPacket* packet, int64_t& pts, bool final) {
    const int frame_size = encoder->frame_size > 0 ? encoder->frame_size : 1024;
    while (av_audio_fifo_size(fifo) >= frame_size || (final && av_audio_fifo_size(fifo) > 0)) {
        const int available = av_audio_fifo_size(fifo);
        const int samples = final ? std::min(frame_size, available) : frame_size;
        std::unique_ptr<AVFrame, FrameCloser> frame(av_frame_alloc());
        if (!frame) return AVERROR(ENOMEM);
        frame->nb_samples = samples;
        frame->format = encoder->sample_fmt;
        frame->sample_rate = encoder->sample_rate;
        int ret = av_channel_layout_copy(&frame->ch_layout, &encoder->ch_layout);
        if (ret < 0) return ret;
        ret = av_frame_get_buffer(frame.get(), 0);
        if (ret < 0) return ret;
        if (av_audio_fifo_read(fifo, reinterpret_cast<void**>(frame->data), samples) < samples)
            return AVERROR_UNKNOWN;
        frame->pts = pts;
        pts += samples;
        ret = avcodec_send_frame(encoder, frame.get());
        if (ret < 0) return ret;
        ret = write_packets(encoder, output, stream, packet);
        if (ret < 0) return ret;
    }
    return 0;
}

struct OutputSpec {
    AVCodecID codec_id;
    const char* encoder_name;
    const char* muxer_name;
    int64_t bit_rate;
};

bool output_spec(const std::string& format, OutputSpec& spec) {
    if (format == "ALAC" || format == "M4A") {
        spec = {AV_CODEC_ID_ALAC, "alac", "ipod", 0};
        return true;
    }
    if (format == "FLAC") {
        spec = {AV_CODEC_ID_FLAC, "flac", "flac", 0};
        return true;
    }
    if (format == "MP3") {
        spec = {AV_CODEC_ID_MP3, "libmp3lame", "mp3", 320000};
        return true;
    }
    return false;
}

int transcode(const char* input_path, const char* output_path, const char* format_name,
              const char* original_path) {
    g_cancelled = false;
    AVFormatContext* raw_input = nullptr;
    int ret = avformat_open_input(&raw_input, input_path, nullptr, nullptr);
    if (ret < 0) return ret;
    std::unique_ptr<AVFormatContext, InputCloser> input(raw_input);
    if ((ret = avformat_find_stream_info(input.get(), nullptr)) < 0) return ret;
    const int stream_index = av_find_best_stream(input.get(), AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (stream_index < 0) return stream_index;
    AVStream* input_stream = input->streams[stream_index];
    const AVCodec* decoder_codec = avcodec_find_decoder(input_stream->codecpar->codec_id);
    if (!decoder_codec) return AVERROR_DECODER_NOT_FOUND;
    std::unique_ptr<AVCodecContext, CodecCloser> decoder(avcodec_alloc_context3(decoder_codec));
    if (!decoder) return AVERROR(ENOMEM);
    if ((ret = avcodec_parameters_to_context(decoder.get(), input_stream->codecpar)) < 0) return ret;
    if ((ret = avcodec_open2(decoder.get(), decoder_codec, nullptr)) < 0) return ret;

    const std::string format(format_name ? format_name : "");
    OutputSpec spec{};
    if (!output_spec(format, spec)) {
        set_error("Unsupported output format: " + format);
        return AVERROR(EINVAL);
    }
    AVFormatContext* output = nullptr;
    if ((ret = avformat_alloc_output_context2(&output, nullptr, spec.muxer_name, output_path)) < 0 || !output) {
        set_error("avformat_alloc_output_context2: " + ff_error(ret < 0 ? ret : AVERROR_UNKNOWN));
        return ret < 0 ? ret : AVERROR_UNKNOWN;
    }
    auto output_cleanup = [&]() {
        if (output) {
            if (!(output->oformat->flags & AVFMT_NOFILE) && output->pb) avio_closep(&output->pb);
            avformat_free_context(output);
        }
    };
    const AVCodec* encoder_codec = avcodec_find_encoder_by_name(spec.encoder_name);
    if (!encoder_codec) encoder_codec = avcodec_find_encoder(spec.codec_id);
    if (!encoder_codec) {
        set_error("Encoder not found for format: " + format);
        output_cleanup();
        return AVERROR_ENCODER_NOT_FOUND;
    }
    std::unique_ptr<AVCodecContext, CodecCloser> encoder(avcodec_alloc_context3(encoder_codec));
    if (!encoder) { output_cleanup(); return AVERROR(ENOMEM); }
    // Match the proven DSRE-Mobile encoder initialization. Setting these fields
    // explicitly is important for the ipod/M4A muxer to accept the ALAC stream.
    encoder->codec_id = spec.codec_id;
    encoder->codec_type = AVMEDIA_TYPE_AUDIO;
    encoder->sample_rate = decoder->sample_rate;
    encoder->bit_rate = spec.bit_rate;

    // FFmpeg 9 removed AVCodec::sample_fmts. Query the public supported-config
    // API with this encoder context, as required by the current API.
    const void* sample_formats_config = nullptr;
    int sample_format_count = 0;
    ret = avcodec_get_supported_config(
        encoder.get(),
        encoder_codec,
        AV_CODEC_CONFIG_SAMPLE_FORMAT,
        0,
        &sample_formats_config,
        &sample_format_count
    );
    if (ret < 0) {
        set_error(std::string("avcodec_get_supported_config (") + format + "): " + ff_error(ret));
        output_cleanup();
        return ret;
    }
    const auto* sample_formats = static_cast<const AVSampleFormat*>(sample_formats_config);
    if (!sample_formats || sample_format_count <= 0) {
        set_error("Encoder reports no supported sample format for: " + format);
        output_cleanup();
        return AVERROR(EINVAL);
    }
    auto supports_sample_format = [&](AVSampleFormat wanted) {
        return std::find(sample_formats, sample_formats + sample_format_count, wanted)
            != sample_formats + sample_format_count;
    };
    encoder->sample_fmt = sample_formats[0];
    if (spec.codec_id == AV_CODEC_ID_ALAC) {
        if (supports_sample_format(AV_SAMPLE_FMT_S32P)) {
            encoder->sample_fmt = AV_SAMPLE_FMT_S32P;
            encoder->bits_per_raw_sample = 24;
        } else if (supports_sample_format(AV_SAMPLE_FMT_S16P)) {
            encoder->sample_fmt = AV_SAMPLE_FMT_S16P;
            encoder->bits_per_raw_sample = 16;
        } else {
            set_error("ALAC encoder supports neither s32p nor s16p");
            output_cleanup();
            return AVERROR(EINVAL);
        }
    } else {
        const AVSampleFormat preferred[] = {
            AV_SAMPLE_FMT_FLTP, AV_SAMPLE_FMT_FLT,
            AV_SAMPLE_FMT_S32P, AV_SAMPLE_FMT_S32,
            AV_SAMPLE_FMT_S16P, AV_SAMPLE_FMT_S16,
        };
        for (AVSampleFormat wanted : preferred) {
            if (supports_sample_format(wanted)) {
                encoder->sample_fmt = wanted;
                break;
            }
        }
    }

    // The intermediate WAV is created as stereo. Rebuild a native/default layout
    // from the channel count instead of copying a possibly UNSPEC decoder layout.
    const int output_channels = decoder->ch_layout.nb_channels > 0
        ? decoder->ch_layout.nb_channels : 2;
    av_channel_layout_uninit(&encoder->ch_layout);
    av_channel_layout_default(&encoder->ch_layout, output_channels);
    if (encoder->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC ||
        encoder->ch_layout.nb_channels <= 0) {
        set_error("Unable to create a native channel layout for: " + format);
        output_cleanup();
        return AVERROR(EINVAL);
    }
    encoder->time_base = AVRational{1, encoder->sample_rate};
    if (output->oformat->flags & AVFMT_GLOBALHEADER) encoder->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    if ((ret = avcodec_open2(encoder.get(), encoder_codec, nullptr)) < 0) {
        const char* sample_name = av_get_sample_fmt_name(encoder->sample_fmt);
        set_error(std::string("avcodec_open2 (") + format + "): " + ff_error(ret) +
                  ", rate=" + std::to_string(encoder->sample_rate) +
                  ", channels=" + std::to_string(encoder->ch_layout.nb_channels) +
                  ", order=" + std::to_string(static_cast<int>(encoder->ch_layout.order)) +
                  ", sample_fmt=" + (sample_name ? sample_name : "unknown") +
                  ", bits=" + std::to_string(encoder->bits_per_raw_sample));
        output_cleanup(); return ret;
    }
    AVStream* output_stream = avformat_new_stream(output, nullptr);
    if (!output_stream) { output_cleanup(); return AVERROR(ENOMEM); }
    output_stream->time_base = encoder->time_base;
    if ((ret = avcodec_parameters_from_context(output_stream->codecpar, encoder.get())) < 0) {
        set_error("avcodec_parameters_from_context: " + ff_error(ret)); output_cleanup(); return ret;
    }
    CopiedCover copied_cover;
    ret = copy_metadata_and_cover(original_path, output, output_stream, copied_cover);
    if (ret < 0) {
        set_error("copy_metadata_and_cover: " + ff_error(ret));
        free_copied_cover(copied_cover);
        output_cleanup();
        return ret;
    }
    if (!(output->oformat->flags & AVFMT_NOFILE) && (ret = avio_open(&output->pb, output_path, AVIO_FLAG_WRITE)) < 0) {
        set_error("avio_open: " + ff_error(ret)); free_copied_cover(copied_cover); output_cleanup(); return ret;
    }
    AVDictionary* mux_options = nullptr;
    if (spec.codec_id == AV_CODEC_ID_MP3) {
        av_dict_set(&mux_options, "id3v2_version", "3", 0);
        av_dict_set(&mux_options, "write_id3v1", "0", 0);
    }
    ret = avformat_write_header(output, &mux_options);
    av_dict_free(&mux_options);
    if (ret < 0) {
        set_error(std::string("avformat_write_header (") + format + "): " + ff_error(ret));
        output_cleanup();
        return ret;
    }

    SwrContext* raw_swr = nullptr;
    ret = swr_alloc_set_opts2(&raw_swr, &encoder->ch_layout, encoder->sample_fmt, encoder->sample_rate,
                              &decoder->ch_layout, decoder->sample_fmt, decoder->sample_rate, 0, nullptr);
    if (ret < 0 || !raw_swr) { output_cleanup(); return ret < 0 ? ret : AVERROR(ENOMEM); }
    std::unique_ptr<SwrContext, SwrCloser> swr(raw_swr);
    if ((ret = swr_init(swr.get())) < 0) { output_cleanup(); return ret; }
    std::unique_ptr<AVAudioFifo, FifoCloser> fifo(av_audio_fifo_alloc(
        encoder->sample_fmt, encoder->ch_layout.nb_channels, 1));
    std::unique_ptr<AVPacket, PacketCloser> packet(av_packet_alloc());
    std::unique_ptr<AVFrame, FrameCloser> decoded(av_frame_alloc());
    if (!fifo || !packet || !decoded) { output_cleanup(); return AVERROR(ENOMEM); }

    int64_t pts = 0;
    while (!g_cancelled && av_read_frame(input.get(), packet.get()) >= 0) {
        if (packet->stream_index != stream_index) { av_packet_unref(packet.get()); continue; }
        ret = avcodec_send_packet(decoder.get(), packet.get());
        av_packet_unref(packet.get());
        if (ret < 0) break;
        while ((ret = avcodec_receive_frame(decoder.get(), decoded.get())) >= 0) {
            ret = push_converted(fifo.get(), swr.get(), decoded.get(), encoder->sample_fmt,
                                 encoder->ch_layout.nb_channels, encoder->sample_rate);
            av_frame_unref(decoded.get());
            if (ret < 0) break;
            ret = encode_fifo(fifo.get(), encoder.get(), output, output_stream, packet.get(), pts, false);
            if (ret < 0) break;
        }
        if (ret != AVERROR(EAGAIN) && ret != AVERROR_EOF && ret < 0) break;
        ret = 0;
    }
    if (g_cancelled) ret = AVERROR_EXIT;
    if (ret >= 0) {
        avcodec_send_packet(decoder.get(), nullptr);
        while (avcodec_receive_frame(decoder.get(), decoded.get()) >= 0) {
            ret = push_converted(fifo.get(), swr.get(), decoded.get(), encoder->sample_fmt,
                                 encoder->ch_layout.nb_channels, encoder->sample_rate);
            av_frame_unref(decoded.get());
            if (ret < 0) break;
        }
    }
    if (ret >= 0) ret = encode_fifo(fifo.get(), encoder.get(), output, output_stream, packet.get(), pts, true);
    if (ret >= 0) {
        ret = avcodec_send_frame(encoder.get(), nullptr);
        if (ret >= 0) ret = write_packets(encoder.get(), output, output_stream, packet.get());
    }
    if (ret >= 0) ret = av_write_trailer(output);
    free_copied_cover(copied_cover);
    output_cleanup();
    return ret;
}

void set_tag(AVDictionary** dictionary, const char* key, const char* value) {
    av_dict_set(dictionary, key, value && *value ? value : nullptr, 0);
}

int remux_metadata_file(const char* input_path, const char* output_path,
                        const std::vector<std::pair<std::string,std::string>>& tags,
                        const char* cover_mode, const char* cover_path) {
    AVFormatContext* raw_input=nullptr;
    int ret=avformat_open_input(&raw_input,input_path,nullptr,nullptr);
    if(ret<0)return ret;
    std::unique_ptr<AVFormatContext,InputCloser> input(raw_input);
    if((ret=avformat_find_stream_info(input.get(),nullptr))<0)return ret;
    AVFormatContext* output=nullptr;
    if((ret=avformat_alloc_output_context2(&output,nullptr,nullptr,output_path))<0||!output)return ret<0?ret:AVERROR(EINVAL);
    auto cleanup=[&](){if(output){if(!(output->oformat->flags&AVFMT_NOFILE)&&output->pb)avio_closep(&output->pb);avformat_free_context(output);output=nullptr;}};
    av_dict_copy(&output->metadata,input->metadata,0);
    for(const auto& tag:tags)set_tag(&output->metadata,tag.first.c_str(),tag.second.c_str());
    const std::string mode=cover_mode?cover_mode:"KEEP";
    std::vector<int> stream_map(input->nb_streams,-1);
    CopiedCover cover;
    for(unsigned i=0;i<input->nb_streams;++i){
        AVStream* in_stream=input->streams[i];
        const bool attached=(in_stream->disposition&AV_DISPOSITION_ATTACHED_PIC)!=0||in_stream->attached_pic.size>0;
        if(attached)continue;
        AVStream* out_stream=avformat_new_stream(output,nullptr);if(!out_stream){cleanup();return AVERROR(ENOMEM);}
        stream_map[i]=out_stream->index;
        if((ret=avcodec_parameters_copy(out_stream->codecpar,in_stream->codecpar))<0){cleanup();return ret;}
        out_stream->codecpar->codec_tag=0;out_stream->time_base=in_stream->time_base;
        out_stream->disposition=in_stream->disposition;av_dict_copy(&out_stream->metadata,in_stream->metadata,0);
        if(in_stream->codecpar->codec_type==AVMEDIA_TYPE_AUDIO)for(const auto& tag:tags)set_tag(&out_stream->metadata,tag.first.c_str(),tag.second.c_str());
    }
    auto add_source_cover=[&](AVStream* source)->int{
        const std::string muxer=output->oformat&&output->oformat->name?output->oformat->name:"";
        const bool force=muxer.find("mp3")!=std::string::npos||muxer.find("ipod")!=std::string::npos||muxer.find("mp4")!=std::string::npos||muxer.find("mov")!=std::string::npos;
        if(!force&&avformat_query_codec(output->oformat,source->codecpar->codec_id,FF_COMPLIANCE_NORMAL)>0){
            AVPacket packet{};int r=av_packet_ref(&packet,&source->attached_pic);if(r<0)return r;
            r=attach_cover_stream(output,source,source->codecpar->codec_id,&packet,source->codecpar->width,source->codecpar->height,cover);av_packet_unref(&packet);return r;
        }
        AVPacket* jpeg=nullptr;int width=0,height=0;int r=convert_cover_to_mjpeg(source,&jpeg,&width,&height);if(r<0)return r;
        r=attach_cover_stream(output,source,AV_CODEC_ID_MJPEG,jpeg,width,height,cover);av_packet_free(&jpeg);return r;
    };
    if(mode=="KEEP"){
        for(unsigned i=0;i<input->nb_streams;++i){AVStream* source=input->streams[i];if(((source->disposition&AV_DISPOSITION_ATTACHED_PIC)!=0||source->attached_pic.size>0)&&source->attached_pic.size>0){ret=add_source_cover(source);break;}}
    }else if(mode=="REPLACE"&&cover_path&&*cover_path){
        AVFormatContext* raw_cover=nullptr;
        ret=avformat_open_input(&raw_cover,cover_path,nullptr,nullptr);
        if(ret>=0&&raw_cover){std::unique_ptr<AVFormatContext,InputCloser> cover_input(raw_cover);ret=avformat_find_stream_info(cover_input.get(),nullptr);
            if(ret>=0){int vi=av_find_best_stream(cover_input.get(),AVMEDIA_TYPE_VIDEO,-1,-1,nullptr,0);if(vi>=0){AVPacket packet{};while(av_read_frame(cover_input.get(),&packet)>=0){if(packet.stream_index==vi){AVStream fake{};fake.codecpar=cover_input->streams[vi]->codecpar;fake.metadata=cover_input->streams[vi]->metadata;fake.attached_pic=packet;fake.disposition=AV_DISPOSITION_ATTACHED_PIC;ret=add_source_cover(&fake);av_packet_unref(&packet);break;}av_packet_unref(&packet);}}else ret=vi;}}
    }
    if(ret<0){free_copied_cover(cover);cleanup();return ret;}
    if(!(output->oformat->flags&AVFMT_NOFILE)&&(ret=avio_open(&output->pb,output_path,AVIO_FLAG_WRITE))<0){free_copied_cover(cover);cleanup();return ret;}
    AVDictionary* options=nullptr;if(std::string(output->oformat->name).find("mp3")!=std::string::npos)av_dict_set(&options,"id3v2_version","3",0);
    ret=avformat_write_header(output,&options);av_dict_free(&options);if(ret<0){free_copied_cover(cover);cleanup();return ret;}
    AVPacket* packet=av_packet_alloc();if(!packet){free_copied_cover(cover);cleanup();return AVERROR(ENOMEM);}
    while((ret=av_read_frame(input.get(),packet))>=0){int mapped=packet->stream_index<(int)stream_map.size()?stream_map[packet->stream_index]:-1;if(mapped<0){av_packet_unref(packet);continue;}
        AVStream* in_stream=input->streams[packet->stream_index];AVStream* out_stream=output->streams[mapped];
        av_packet_rescale_ts(packet,in_stream->time_base,out_stream->time_base);packet->stream_index=mapped;packet->pos=-1;
        ret=av_interleaved_write_frame(output,packet);av_packet_unref(packet);if(ret<0)break;
    }
    av_packet_free(&packet);if(ret==AVERROR_EOF)ret=0;if(ret>=0)ret=av_write_trailer(output);
    free_copied_cover(cover);cleanup();return ret;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_uyatame_ucrt_dsre_DsreNativeEncoder_transcodeWav(
    JNIEnv* env, jobject, jstring input, jstring output, jstring format,
    jstring original) {
    const char* in_path = env->GetStringUTFChars(input, nullptr);
    const char* out_path = env->GetStringUTFChars(output, nullptr);
    const char* kind = env->GetStringUTFChars(format, nullptr);
    const char* original_path = env->GetStringUTFChars(original, nullptr);
    const int result = transcode(in_path, out_path, kind, original_path);
    env->ReleaseStringUTFChars(input, in_path);
    env->ReleaseStringUTFChars(output, out_path);
    env->ReleaseStringUTFChars(format, kind);
    env->ReleaseStringUTFChars(original, original_path);
    if (result < 0) {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        if (g_last_error.empty()) g_last_error = ff_error(result);
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_uyatame_ucrt_dsre_DsreNativeEncoder_cancel(JNIEnv*, jobject) {
    g_cancelled = true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_uyatame_ucrt_dsre_DsreNativeEncoder_lastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_uyatame_ucrt_dsre_DsreNativeEncoder_remuxMetadata(
    JNIEnv* env, jobject, jstring input, jstring output,
    jstring title, jstring artist, jstring album, jstring album_artist, jstring composer,
    jstring genre, jstring date, jstring track, jstring disc, jstring comment,
    jstring copyright_value, jstring lyrics, jstring cover_mode, jstring cover_path) {
    std::vector<jstring> strings={input,output,title,artist,album,album_artist,composer,genre,date,track,disc,comment,copyright_value,lyrics,cover_mode,cover_path};
    std::vector<const char*> values;values.reserve(strings.size());
    for(jstring item:strings)values.push_back(env->GetStringUTFChars(item,nullptr));
    const std::vector<std::pair<std::string,std::string>> tags={
        {"title",values[2]},{"artist",values[3]},{"album",values[4]},{"album_artist",values[5]},
        {"composer",values[6]},{"genre",values[7]},{"date",values[8]},{"track",values[9]},
        {"disc",values[10]},{"comment",values[11]},{"copyright",values[12]},{"lyrics",values[13]},
    };
    const int result=remux_metadata_file(values[0],values[1],tags,values[14],values[15]);
    for(size_t i=0;i<strings.size();++i)env->ReleaseStringUTFChars(strings[i],values[i]);
    if(result<0)set_error(ff_error(result));
    return result;
}
