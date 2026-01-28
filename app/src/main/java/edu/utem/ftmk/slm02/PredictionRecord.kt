package edu.utem.ftmk.slm02

data class PredictionRecord(
    val dataId: String = "",
    val name: String = "",
    val ingredients: String = "",
    val allergens: String = "",
    val mappedAllergens: String = "",
    val predictedAllergens: String = "",
    val timestamp: Any? = null, // Will use Firebase ServerValue.TIMESTAMP
    val latencyMs: Long = 0,
    val ttft: Long = 0,
    val itps: Long = 0,
    val otps: Long = 0,
    val oet: Long = 0
)
