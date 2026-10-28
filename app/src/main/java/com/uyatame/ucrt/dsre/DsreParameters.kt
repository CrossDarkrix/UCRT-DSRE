package com.uyatame.ucrt.dsre

enum class DsreEngineMode { LEGACY_OPTIMIZED, OVERSAMPLED_PR }

data class DsreParameters(
    val harmonicCount: Int,
    val strength: Float,
    val targetSampleRate: Int,
    val stereoWidth: Float,
    val dynamic: Float,
    val contextSeconds: Float,
    val engineMode: DsreEngineMode = DsreEngineMode.LEGACY_OPTIMIZED,
) {
    init {
        require(harmonicCount in 1..24)
        require(strength in 0.10f..0.80f)
        require(targetSampleRate in setOf(44100, 48000, 88200, 96000, 192000))
        require(stereoWidth in 0.50f..1.50f)
        require(dynamic in 1.00f..1.50f)
        require(contextSeconds in 0.00f..0.10f)
    }
}
