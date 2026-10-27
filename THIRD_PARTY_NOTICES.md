# Third-party notices

このアプリは、以下のソフトウェアとデータを利用しています。ライセンスの全文は `app/src/main/assets/licenses/` にあり、アプリ内でも確認できます。

| 名前 | 用途 | ライセンス |
|---|---|---|
| [FFmpeg](https://ffmpeg.org/) 9.0.2 | DSRE audio decoding, encoding, resampling, format writing, and cover conversion | LGPL v2.1 or later; GPL/nonfree options disabled |
| [LAME](https://lame.sourceforge.io/) 3.100 | MP3 encoding through FFmpeg libmp3lame; statically linked into shared libavcodec.so | LGPL |
| [zlib](https://www.zlib.net/) | Compression support used by the bundled FFmpeg build | zlib License |
| [jump3r](https://codeberg.org/sciss/jump3r) 1.0.5（LAME 3.98.4のJava移植） | MP3エンコード | LGPL v2.1以降 |
| [JNA](https://github.com/java-native-access/jna) 5.14.0 | USB DAC直接出力（usbfsの呼び出し） | Apache License 2.0（Apache-2.0 / LGPL-2.1のデュアルライセンスから選択） |
| [Spotify App Remote SDK](https://github.com/spotify/android-sdk) 0.8.0 | 公式Spotifyアプリでの再生の操作（ビルド時にGitHubのリリースから取得し、SHA-256を確認して使用） | Apache License 2.0 |
| [Gson](https://github.com/google/gson) 2.10.1 | Spotify App Remote SDKが使うJSONの処理 | Apache License 2.0 |
| Kotlin / kotlinx.coroutines | 言語・非同期処理 | Apache License 2.0 |
| AndroidX（Core, Activity, Lifecycle, DataStore, DocumentFile） | Androidの基盤 | Apache License 2.0 |
| Jetpack Compose / Material 3 / Material Icons | 画面 | Apache License 2.0 |
| [MusicBrainz](https://musicbrainz.org) | 曲情報 | CC0 1.0（コアデータ） |
| [Cover Art Archive](https://coverartarchive.org) | ジャケット画像 | 各画像の権利者による |


## FFmpeg / LAME / zlib

The application ships FFmpeg 9.0.2 as ABI-specific shared libraries: libavutil, libswresample, libswscale, libavcodec, and libavformat. The build does not enable `--enable-gpl`, `--enable-version3`, or `--enable-nonfree`.

LAME 3.100 is used for MP3 encoding and is statically linked into the shared `libavcodec.so`. zlib is also used by the FFmpeg build. Corresponding source locations, configuration records, hashes, and reproduction/relinking information are recorded under `third_party/ffmpeg/` and exposed in the app's About / License information screen.

## jump3r（LGPL）について

jump3rは改変せず、独立したライブラリ（Maven: `de.sciss:jump3r:1.0.5`）として利用しています。ソースコードは上記のリポジトリとMaven Centralから入手できます。LGPLに基づき、このプロジェクトのソースコードから、ライブラリを差し替えたアプリをビルドできます。

## 本アプリ独自の実装

FLACエンコーダー、MP4・ID3タグの処理、USB光学ドライブの制御（SCSI/MMC）、USBオーディオ（UAC1/UAC2）ドライバー、DSD（DSF/DSDIFF）の読み込み・DoP・PCM変換、ID3v2の読み取り、`javax.sound.sampled` 互換クラスは本アプリ独自の実装で、本アプリと同じGPL v3で提供しています。AACのエンコードと曲の再生には、Android OS内蔵のコーデックを使用しています。
