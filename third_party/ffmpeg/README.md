# FFmpeg audio integration

This directory records how the bundled Android FFmpeg libraries were built.
The application packages ABI-specific shared libraries from `app/src/main/cpp/ffmpeg/libs`.

Enabled DSRE output encoders:

- FFmpeg native ALAC
- FFmpeg native FLAC
- LAME MP3 through FFmpeg `libmp3lame`, fixed at 320 kbit/s

The LAME-enabled build replaces the earlier ALAC/FLAC-only FFmpeg binaries. Build manifests and
per-ABI FFmpeg configuration files are retained here so another developer can reproduce and audit
the exact feature configuration. The FFmpeg and LAME source archives and the builder used for a
release must be retained with release records as described in `NOTICE.md`.
