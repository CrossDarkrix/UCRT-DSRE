# FFmpeg 9.0.2 libswscale integration

This project uses the submitted matched FFmpeg 9.0.2 headers and shared libraries for arm64-v8a and armeabi-v7a: libavutil, libswresample, libswscale, libavcodec, and libavformat. CMake imports and links libswscale. Cover conversion now decodes the selected/embedded image and uses sws_getContext plus sws_scale to convert any supported decoded pixel format to MJPEG YUVJ420P before attaching it. The previous narrow manual RGB converter is no longer used by the cover path.
