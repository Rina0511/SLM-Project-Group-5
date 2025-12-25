package edu.utem.ftmk.slm02


/**
 * BITP 3453 Mobile Application Development
 *
 * @author Emaliana Kasmuri FTMK, UTeM
 *
 * Purpose:
 * Represent the metrics to measure the inference performance
 */

data class InferenceMetrics (

    // Total time taken to complete the inference
    val latencyMs: Long,

    // Memory snapshot
    val javaHeapKb: Long,
    val nativeHeapKb: Long,
    val totalPssKb: Long,

    // Efficiency metrics

    // Time-to-First-Token
    val ttft: Long,

    // Input-Token-Per-Second
    val itps: Long,

    // Output-Token-Per-Second
    val otps: Long,

    // Output-Evaluation-Time
    val oet: Long

)
