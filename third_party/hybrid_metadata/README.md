# FFmpeg 9 audio + metadata logic hybrid

The project keeps the original FFmpeg 9 headers and shared libraries from UCRT-DSRE-main6.
No FFmpeg 7 shared library is packaged or loaded.
Container tags, audio-stream tags and directly compatible attached-picture packets are copied from the original source using the FFmpeg 9 API.
The original UCRT-DSRE-main6 audio decode, resample, DSP input, FIFO and encode path is retained.
