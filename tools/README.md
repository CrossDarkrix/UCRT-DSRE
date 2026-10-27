# ffmpeg-android-audio-metadata-builder の使い方

## 依存関係のインストール

```bash
pkg update
pkg install -y python make pkg-config git curl wget tar xz-utils cmake ninja
```

NDKを [ここ](https://github.com/HomuHomu833/android-ndk-custom/releases) からダウンロードしておく

SDKは [ここ](https://github.com/HomuHomu833/android-sdk-custom/releases) からダウンロード

## 以下を任意の名前のshファイルにして実行しておく。

```
SDK_CMAKE="$HOME/android-sdk/cmake/3.22.1/bin"
TERMUX_CMAKE="$(command -v cmake)"
TERMUX_NINJA="$(command -v ninja)"

test -x "$TERMUX_CMAKE" || {
    echo "ERROR: Termux cmake not found"
    exit 1
}

test -x "$TERMUX_NINJA" || {
    echo "ERROR: Termux ninja not found"
    exit 1
}

mv "$SDK_CMAKE/cmake" "$SDK_CMAKE/cmake.x86_64.disabled"
mv "$SDK_CMAKE/ninja" "$SDK_CMAKE/ninja.x86_64.disabled"

ln -s  "$TERMUX_CMAKE" "$SDK_CMAKE/cmake"
ln -s "$TERMUX_NINJA" "$SDK_CMAKE/ninja"

chmod 755 "$SDK_CMAKE/cmake" "$SDK_CMAKE/ninja
```

例: ```bash
chmod +x move_ninja_cmake && ./move_ninja_cmake
```

先ほどダウンロードした NDK, SDKを $HOME直下にそれぞれ android-ndk, android-sdk として配置しておく。

## 次に NDKを次のように指定しておく

```bash
export ANDROID_NDK_ROOT="$HOME/android-ndk"
export ANDROID_NDK_TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-aarch64"
```

# Termuxの場合

```bash
unzip ffmpeg-android-audio-metadata-builder_v9.0.2-termux.zip && cd ffmpeg-android-audio-metadata-builder
```

```bash
chmod +x termux-build.sh
./termux-build.sh --clean --jobs 4
```

としてffmpeg をビルドしておく

# PCの場合

```bash
sudo apt update
sudo apt install -y python3 make pkg-config xz-utils
export ANDROID_NDK_ROOT="$HOME/Android/Sdk/ndk/28.2.13676358"
```

```bash
unzip ffmpeg-android-audio-metadata-builder_v9.0.2.zip && cd ffmpeg-android-audio-metadata-builder
```

```bash
./build.sh --clean
```
