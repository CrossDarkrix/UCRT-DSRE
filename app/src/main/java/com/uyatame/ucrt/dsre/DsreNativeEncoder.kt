package com.uyatame.ucrt.dsre

object DsreNativeEncoder {
    init {
        System.loadLibrary("avutil")
        System.loadLibrary("swresample")
        System.loadLibrary("avcodec")
        System.loadLibrary("avformat")
        System.loadLibrary("dsre_audio")
    }

    external fun transcodeWav(inputPath: String, outputPath: String, format: String, originalPath: String): Int
    external fun remuxMetadata(inputPath: String, outputPath: String,
        title: String, artist: String, album: String, albumArtist: String, composer: String,
        genre: String, date: String, track: String, disc: String, comment: String,
        copyright: String, lyrics: String, coverMode: String, coverPath: String): Int
    external fun cancel()
    external fun lastError(): String
}
