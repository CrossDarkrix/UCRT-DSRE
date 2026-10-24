# Android integration

生成された`output/jniLibs`をAndroidアプリへ配置します。

```text
app/src/main/jniLibs/
├── arm64-v8a/
└── armeabi-v7a/
```

LAMEは`libavcodec.so`へ静的リンクされているため、`libmp3lame.so`は不要です。

Kotlin側の読み込み例:

```kotlin
object FfmpegNativeLoader {
    init {
        System.loadLibrary("avutil")
        System.loadLibrary("swresample")
        System.loadLibrary("avcodec")
        System.loadLibrary("avformat")
        System.loadLibrary("dsre_audio")
    }
}
```

JNI側でMP3を選ぶ場合、Encoder名は`libmp3lame`、Muxer名は`mp3`を使用します。

```cpp
const AVCodec* codec = avcodec_find_encoder_by_name("libmp3lame");
encoderContext->bit_rate = static_cast<int64_t>(mp3Kbps) * 1000;
```

`libdsre_audio.so`はこのBuilderでは生成しません。生成されたFFmpegヘッダーとABI別共有ライブラリを使って、アプリ側CMakeからビルドしてください。
