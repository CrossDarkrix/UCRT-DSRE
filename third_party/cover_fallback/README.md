# Cover conversion fallback

Compatible attached-picture codecs are copied without re-encoding. If the output muxer rejects the original cover codec, the cover is decoded with FFmpeg 9, converted from common RGB/BGR/RGBA/BGRA/ARGB/ABGR/GRAY8 formats to YUVJ420P, encoded once as MJPEG, and attached with AV_DISPOSITION_ATTACHED_PIC. Metadata title/comment are retained or filled as Album cover / Cover (front). Audio and fixed-length FFT processing are unchanged.
