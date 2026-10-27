# FFmpeg, LAME, and zlib license integration

The About / License information screen includes:

- FFmpeg 9.0.2 attribution and LGPL v2.1-or-later profile
- LAME 3.100 attribution, LGPL notice, and static-link disclosure
- zlib attribution and zlib License text
- FFmpeg / LAME build and relinking information
- Third-party notices
- Existing full GNU LGPL v2.1 text

The runtime assets point to the corresponding upstream source releases. Exact ABI configure records, component flags, binary hashes, build script, and integration notes remain in `third_party/ffmpeg/` and `tools/ffmpeg/` in the source project.

The bundled build is recorded as not enabling `--enable-gpl`, `--enable-version3`, or `--enable-nonfree`. FFmpeg is packaged as shared libraries; LAME is statically linked into shared `libavcodec.so`.
