# Licensing notice

このBuilder自体はMITライセンスです。

既定構成ではFFmpegの`--enable-gpl`、`--enable-version3`、`--enable-nonfree`を使用しません。
MP3 EncoderとしてLAMEを利用します。LAMEおよびFFmpegの正確なライセンス条件、ソース提供、通知、再リンク要件は、配布するバージョンとリンク構成に対して確認してください。

配布時には少なくとも次を保存・提供できる状態にしてください。

- 使用したFFmpegソース
- 使用したLAMEソース
- ビルドスクリプト
- `build-manifest.json`
- `build-info/<ABI>/ffmpeg-config.h`
- FFmpegおよびLAMEのライセンス文書

この文書は法的助言ではありません。
