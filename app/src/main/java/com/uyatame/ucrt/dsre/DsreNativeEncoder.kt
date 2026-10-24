package com.uyatame.ucrt.dsre

object DsreNativeEncoder {
    init {
        System.loadLibrary("avutil")
        System.loadLibrary("swresample")
        System.loadLibrary("avcodec")
        System.loadLibrary("avformat")
        System.loadLibrary("dsre_audio")
    }

    external fun transcodeWav(inputPath: String, outputPath: String, format: String): Int
    external fun cancel()
    external fun lastError(): String
}
