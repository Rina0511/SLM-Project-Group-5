package edu.utem.ftmk.slm02


/**
 * BITP 3453 Mobile Application Development
 *
 * @author Emaliana Kasmuri FTMK, UTeM
 *
 * Purpose:
 * Represent the metrics to measure the inference performance
 */


data class InferenceMetrics(
    val latencyMs: Long,
    val javaHeapKb: Long,
    val nativeHeapKb: Long,
    val totalPssKb: Long,
    val ttft: Long,
    val itps: Long,
    val otps: Long,
    val oet: Long
)