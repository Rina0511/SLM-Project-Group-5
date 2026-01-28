package edu.utem.ftmk.slm02

import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.io.File
import java.io.FileWriter

class DashboardActivity : AppCompatActivity() {

    private val db = Firebase.firestore

    private var sessionId: String = ""
    private var datasetLabel: String = ""
    private var activeModel: String = "--"

    private val allowedAllergens = listOf(
        "milk", "egg", "peanut", "tree nut", "wheat",
        "soy", "fish", "shellfish", "sesame"
    )
    private val labelCount = allowedAllergens.size
    private var datasetKey: String = ""


    data class ModelAgg(
        val model: String,
        var itemCount: Int = 0,

        // Label-level confusion totals across all items (micro)
        var tp: Int = 0,
        var fp: Int = 0,
        var fn: Int = 0,
        var tn: Int = 0,

        // Item-level metrics
        var exactMatches: Int = 0,          // EMR numerator
        var hallucinationItems: Int = 0,    // safety: predicted not in ingredients (rough)
        var overPredictionItems: Int = 0,   // safety: fp > 0 OR predSize > expSize
        var abstentionTotal: Int = 0,       // expected empty count
        var abstentionCorrect: Int = 0,     // expected empty AND predicted empty

        // Efficiency sums (handle missing fields = 0)
        var sumLatency: Long = 0,
        var sumTtft: Long = 0,
        var sumItps: Long = 0,
        var sumOtps: Long = 0,
        var sumOet: Long = 0,
        var sumJavaHeapKb: Long = 0,
        var sumNativeHeapKb: Long = 0,
        var sumPssKb: Long = 0
    ) {
        // ---------- QUALITY ----------
        fun precision(): Double = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
        fun recall(): Double = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)

        // Micro-F1 from totals
        fun f1Micro(): Double {
            val denom = (2 * tp + fp + fn).toDouble()
            return if (denom == 0.0) 0.0 else (2.0 * tp) / denom
        }

        // Macro-F1 computed later by per-label tracking (we’ll compute from arrays in code)
        var f1Macro: Double = 0.0

        fun emr(): Double = if (itemCount == 0) 0.0 else exactMatches.toDouble() / itemCount

        // Hamming Loss = (FP + FN) / (N * L)
        fun hammingLoss(labelCount: Int): Double {
            val denom = (itemCount * labelCount).toDouble()
            return if (denom == 0.0) 0.0 else (fp + fn).toDouble() / denom
        }

        // FNR = FN / (TP + FN)
        fun fnr(): Double = if (tp + fn == 0) 0.0 else fn.toDouble() / (tp + fn)

        // ---------- SAFETY ----------
        fun hallucinationRatePct(): Double =
            if (itemCount == 0) 0.0 else hallucinationItems.toDouble() * 100.0 / itemCount

        fun overPredictionRatePct(): Double =
            if (itemCount == 0) 0.0 else overPredictionItems.toDouble() * 100.0 / itemCount

        fun abstentionAccuracyPct(): Double =
            if (abstentionTotal == 0) 0.0 else abstentionCorrect.toDouble() * 100.0 / abstentionTotal

        // ---------- EFFICIENCY ----------
        fun avgLatency(): Double = if (itemCount == 0) 0.0 else sumLatency.toDouble() / itemCount
        fun avgTtft(): Double = if (itemCount == 0) 0.0 else sumTtft.toDouble() / itemCount
        fun avgItps(): Double = if (itemCount == 0) 0.0 else sumItps.toDouble() / itemCount
        fun avgOtps(): Double = if (itemCount == 0) 0.0 else sumOtps.toDouble() / itemCount
        fun avgOet(): Double = if (itemCount == 0) 0.0 else sumOet.toDouble() / itemCount
        fun avgJavaHeapKb(): Double = if (itemCount == 0) 0.0 else sumJavaHeapKb.toDouble() / itemCount
        fun avgNativeHeapKb(): Double = if (itemCount == 0) 0.0 else sumNativeHeapKb.toDouble() / itemCount
        fun avgPssKb(): Double = if (itemCount == 0) 0.0 else sumPssKb.toDouble() / itemCount
    }

    private var latestAggList: List<ModelAgg> = emptyList() // for CSV export

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        sessionId = intent.getStringExtra("SESSION_ID") ?: ""
        datasetKey = intent.getStringExtra("DATASET_KEY") ?: ""


        datasetLabel = intent.getStringExtra("DATASET_LABEL") ?: "foodpreprocessed.csv (200 samples)"
        activeModel = intent.getStringExtra("MODEL_NAME") ?: "--"

        // Device info
        findViewById<TextView>(R.id.tvHardware).text =
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}"
        findViewById<TextView>(R.id.tvOS).text =
            "OS: Android ${Build.VERSION.RELEASE}"
        findViewById<TextView>(R.id.tvModelName).text =
            "Active Model: $activeModel"
        findViewById<TextView>(R.id.tvDataset).text =
            "Dataset: $datasetLabel"

        if (sessionId.isBlank()) {
            Toast.makeText(this, "No sessionId received. Run analysis first.", Toast.LENGTH_LONG).show()
            return
        }

        loadAggregatesFromFirestore()

        findViewById<Button>(R.id.btnExportCSV).setOnClickListener { exportAggToCSV() }
    }

    private fun loadAggregatesFromFirestore() {
        Toast.makeText(this, "Loading results from Firebase...", Toast.LENGTH_SHORT).show()

        db.collection("project_benchmarks")
            .whereEqualTo("sessionId", sessionId)
            .whereEqualTo("datasetKey", datasetKey)

            .get()
            .addOnSuccessListener { snap ->

                val map = linkedMapOf<String, ModelAgg>()

                // For macro-F1 we need per-label TP/FP/FN counts per model
                val perLabel = mutableMapOf<String, Triple<IntArray, IntArray, IntArray>>() // model -> (tp[], fp[], fn[])

                for (doc in snap.documents) {
                    val model = doc.getString("model") ?: continue

                    val expectedStr = doc.getString("expectedAllergens") ?: ""
                    val predictedStr = doc.getString("predictedAllergens") ?: ""
                    val ingredients = (doc.getString("ingredients") ?: "").lowercase()

                    val latencyMs = doc.getLong("latencyMs") ?: 0L
                    val ttft = doc.getLong("ttft") ?: 0L
                    val itps = doc.getLong("itps") ?: 0L
                    val otps = doc.getLong("otps") ?: 0L
                    val oet = doc.getLong("oet") ?: 0L
                    val javaHeap = doc.getLong("javaHeapKb") ?: 0L
                    val nativeHeap = doc.getLong("nativeHeapKb") ?: 0L
                    val pss = doc.getLong("pssKb") ?: 0L

                    val expSet = parseAllergenSet(expectedStr)
                    val predSet = parseAllergenSet(predictedStr)

                    val agg = map.getOrPut(model) { ModelAgg(model = model) }
                    val labelTp = perLabel.getOrPut(model) {
                        Triple(IntArray(labelCount), IntArray(labelCount), IntArray(labelCount))
                    }

                    // ---- item-level metrics ----
                    agg.itemCount++
                    if (expSet == predSet) agg.exactMatches++

                    if (expSet.isEmpty()) {
                        agg.abstentionTotal++
                        if (predSet.isEmpty()) agg.abstentionCorrect++
                    }

                    // hallucination: predicted allergen not mentioned in ingredients (rough)
                    if (predSet.any { !ingredients.contains(it) }) agg.hallucinationItems++

                    // over-prediction: predicts extra labels vs expected
                    if (predSet.subtract(expSet).isNotEmpty()) agg.overPredictionItems++

                    // ---- label-level confusion (micro) ----
                    for ((i, lab) in allowedAllergens.withIndex()) {
                        val exp = expSet.contains(lab)
                        val pred = predSet.contains(lab)

                        when {
                            exp && pred -> {
                                agg.tp++
                                labelTp.first[i]++
                            }
                            !exp && pred -> {
                                agg.fp++
                                labelTp.second[i]++
                            }
                            exp && !pred -> {
                                agg.fn++
                                labelTp.third[i]++
                            }
                            else -> agg.tn++
                        }
                    }

                    // ---- efficiency sums ----
                    agg.sumLatency += latencyMs
                    agg.sumTtft += ttft
                    agg.sumItps += itps
                    agg.sumOtps += otps
                    agg.sumOet += oet
                    agg.sumJavaHeapKb += javaHeap
                    agg.sumNativeHeapKb += nativeHeap
                    agg.sumPssKb += pss
                }

                // compute macro-F1 per model
                map.values.forEach { agg ->
                    val (tpArr, fpArr, fnArr) = perLabel[agg.model] ?: Triple(IntArray(labelCount), IntArray(labelCount), IntArray(labelCount))
                    var sumF1 = 0.0
                    for (i in 0 until labelCount) {
                        val tp = tpArr[i]
                        val fp = fpArr[i]
                        val fn = fnArr[i]
                        val denom = (2 * tp + fp + fn).toDouble()
                        val f1 = if (denom == 0.0) 0.0 else (2.0 * tp) / denom
                        sumF1 += f1
                    }
                    agg.f1Macro = sumF1 / labelCount
                }

                val list = map.values.sortedByDescending { it.f1Micro() }
                latestAggList = list

                populateQualityTable(list)
                populateSafetyTable(list)
                populateEfficiencyTable(list)
                populateModelCardsAll(list)

                Toast.makeText(this, "Loaded ${snap.size()} records (${list.size} models)", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Firebase load failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun makeCell(text: String): TextView {
        return TextView(this).apply {
            this.text = " $text "
            setTextColor(android.graphics.Color.BLACK)
            setBackgroundResource(R.drawable.table_cell_border)
            setPadding(8, 8, 8, 8)
        }
    }

    private fun clearTableKeepHeader(table: TableLayout) {
        while (table.childCount > 1) table.removeViewAt(1)
    }

    /** Adds one aligned row into (left model table) + (right metrics table) */
    private fun addRow(modelTable: TableLayout, metricsTable: TableLayout, model: String, metrics: List<String>) {
        // left fixed model column
        TableRow(this).apply {
            addView(makeCell(model))
            modelTable.addView(this)
        }

        // right scrollable metrics
        TableRow(this).apply {
            metrics.forEach { addView(makeCell(it)) }
            metricsTable.addView(this)
        }
    }

    // -------------------- TABLE 1: QUALITY --------------------
    private fun populateQualityTable(list: List<ModelAgg>) {
        val tModel = findViewById<TableLayout>(R.id.tableQualityModel)
        val tMetrics = findViewById<TableLayout>(R.id.tableQualityMetrics)

        clearTableKeepHeader(tModel)
        clearTableKeepHeader(tMetrics)

        list.forEach { m ->
            val metrics = listOf(
                String.format("%.4f", m.precision()),
                String.format("%.4f", m.recall()),
                String.format("%.4f", m.f1Micro()),
                String.format("%.4f", m.f1Macro),
                String.format("%.4f", m.emr()),
                String.format("%.4f", m.hammingLoss(labelCount)),
                String.format("%.4f", m.fnr()),
                m.tp.toString(),
                m.fp.toString(),
                m.fn.toString(),
                m.tn.toString()
            )
            addRow(tModel, tMetrics, m.model, metrics)
        }
    }

    // -------------------- TABLE 2: SAFETY --------------------
    private fun populateSafetyTable(list: List<ModelAgg>) {
        val tModel = findViewById<TableLayout>(R.id.tableSafetyModel)
        val tMetrics = findViewById<TableLayout>(R.id.tableSafetyMetrics)

        clearTableKeepHeader(tModel)
        clearTableKeepHeader(tMetrics)

        list.forEach { m ->
            val metrics = listOf(
                String.format("%.2f", m.hallucinationRatePct()),
                String.format("%.2f", m.overPredictionRatePct()),
                String.format("%.2f", m.abstentionAccuracyPct())
            )
            addRow(tModel, tMetrics, m.model, metrics)
        }
    }

    // -------------------- TABLE 3: EFFICIENCY --------------------
    private fun populateEfficiencyTable(list: List<ModelAgg>) {
        val tModel = findViewById<TableLayout>(R.id.tableEfficiencyModel)
        val tMetrics = findViewById<TableLayout>(R.id.tableEfficiencyMetrics)

        clearTableKeepHeader(tModel)
        clearTableKeepHeader(tMetrics)

        list.forEach { m ->
            val metrics = listOf(
                String.format("%.1f", m.avgLatency()),
                String.format("%.1f", m.avgTtft()),
                String.format("%.2f", m.avgItps()),
                String.format("%.2f", m.avgOtps()),
                String.format("%.1f", m.avgOet()),
                String.format("%.1f", m.avgJavaHeapKb()),
                String.format("%.1f", m.avgNativeHeapKb()),
                String.format("%.1f", m.avgPssKb())
            )
            addRow(tModel, tMetrics, m.model, metrics)
        }
    }


    // -------------------- MODEL CARDS --------------------
    private fun populateModelCardsAll(list: List<ModelAgg>) {
        val container = findViewById<LinearLayout>(R.id.modelCardsContainer)
        container.removeAllViews()

        list.forEach { m ->
            val card = layoutInflater.inflate(R.layout.item_model_summarization, container, false)

            card.findViewById<TextView>(R.id.tvCardModelName).text = "● ${m.model}"
            val f1 = m.f1Micro()
            card.findViewById<TextView>(R.id.tvAccuracyPercent).text = String.format("%.2f", f1)

            val tag = when {
                f1 >= 0.70 -> "🥇 Strong Prediction"
                f1 >= 0.40 -> "🥈 Medium Prediction"
                else -> "⚠️ Weak Prediction"
            }
            card.findViewById<TextView>(R.id.tvRankTag).text = tag

            card.findViewById<TextView>(R.id.tvStrength).text =
                "Precision: ${String.format("%.3f", m.precision())}\n" +
                        "Recall: ${String.format("%.3f", m.recall())}\n" +
                        "EMR: ${String.format("%.3f", m.emr())}\n" +
                        "Exact Matches: ${m.exactMatches}/${m.itemCount}"


            card.findViewById<TextView>(R.id.tvWeakness).text =
                "FP: ${m.fp}  FN: ${m.fn}\n" +
                        "Hallucination: ${String.format("%.1f", m.hallucinationRatePct())}%\n" +
                        "Avg PSS: ${String.format("%.0f", m.avgPssKb())} KB"

            container.addView(card)
        }
    }

    // -------------------- CSV EXPORT --------------------
    private fun exportAggToCSV() {

        val file = File(getExternalFilesDir(null), "Benchmark_Export_${sessionId}.csv")

        db.collection("project_benchmarks")
            .whereEqualTo("sessionId", sessionId)
            .whereEqualTo("dataset", datasetLabel)
            .get()
            .addOnSuccessListener { snap ->

                try {
                    FileWriter(file).use { w ->

                        // ===================== ITEM LEVEL =====================
                        w.append("PREDICTION RECORDS (ITEM LEVEL)\n")
                        w.append(
                            "SessionId,Dataset,Model,DataId,Name,Ingredients,Expected,Predicted," +
                                    "LatencyMs,TTFT,ITPS,OTPS,OET,JavaHeapKb,NativeHeapKb,PSSKb,Timestamp\n"
                        )

                        for (doc in snap.documents) {
                            val model = doc.getString("model") ?: ""
                            val dataId = doc.getString("dataId") ?: ""
                            val name = doc.getString("name") ?: ""
                            val ingredients = (doc.getString("ingredients") ?: "").replace(",", " ") // avoid CSV breaking
                            val exp = doc.getString("expectedAllergens") ?: ""
                            val pred = doc.getString("predictedAllergens") ?: ""

                            val latency = doc.getLong("latencyMs") ?: 0
                            val ttft = doc.getLong("ttft") ?: 0
                            val itps = doc.getLong("itps") ?: 0
                            val otps = doc.getLong("otps") ?: 0
                            val oet = doc.getLong("oet") ?: 0
                            val javaHeap = doc.getLong("javaHeapKb") ?: 0
                            val nativeHeap = doc.getLong("nativeHeapKb") ?: 0
                            val pss = doc.getLong("pssKb") ?: 0

                            val ts = doc.getTimestamp("timestamp")?.toDate()?.toString() ?: ""

                            w.append(
                                "${sessionId},${datasetLabel},${model},${dataId},${name}," +
                                        "\"${ingredients}\",${exp},${pred}," +
                                        "${latency},${ttft},${itps},${otps},${oet},${javaHeap},${nativeHeap},${pss},\"${ts}\"\n"
                            )
                        }

                        w.append("\n\n")

                        // ===================== AGG LEVEL =====================
                        if (latestAggList.isNotEmpty()) {

                            w.append("DASHBOARD SUMMARY (MODEL LEVEL)\n")
                            w.append(
                                "SessionId,Dataset,Model,Count,Precision,Recall,F1_Micro,F1_Macro,EMR,HammingLoss,FNR,TP,FP,FN,TN," +
                                        "HallucinationRatePct,OverPredictionRatePct,AbstentionAccuracyPct," +
                                        "AvgLatencyMs,AvgTTFTMs,AvgITPS,AvgOTPS,AvgOETMs,AvgJavaHeapKB,AvgNativeHeapKB,AvgPSSKB\n"
                            )

                            latestAggList.forEach { m ->
                                w.append(
                                    "${sessionId},${datasetLabel},${m.model},${m.itemCount}," +
                                            "${String.format("%.4f", m.precision())},${String.format("%.4f", m.recall())}," +
                                            "${String.format("%.4f", m.f1Micro())},${String.format("%.4f", m.f1Macro)}," +
                                            "${String.format("%.4f", m.emr())},${String.format("%.4f", m.hammingLoss(labelCount))}," +
                                            "${String.format("%.4f", m.fnr())},${m.tp},${m.fp},${m.fn},${m.tn}," +
                                            "${String.format("%.2f", m.hallucinationRatePct())},${String.format("%.2f", m.overPredictionRatePct())},${String.format("%.2f", m.abstentionAccuracyPct())}," +
                                            "${String.format("%.2f", m.avgLatency())},${String.format("%.2f", m.avgTtft())}," +
                                            "${String.format("%.2f", m.avgItps())},${String.format("%.2f", m.avgOtps())},${String.format("%.2f", m.avgOet())}," +
                                            "${String.format("%.2f", m.avgJavaHeapKb())},${String.format("%.2f", m.avgNativeHeapKb())},${String.format("%.2f", m.avgPssKb())}\n"
                                )
                            }
                        }

                    }

                    Toast.makeText(this, "CSV Exported: ${file.absolutePath}", Toast.LENGTH_LONG).show()

                } catch (e: Exception) {
                    Toast.makeText(this, "Export Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }

            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Export Failed (Firebase): ${e.message}", Toast.LENGTH_LONG).show()
            }
    }


    // -------------------- HELPERS --------------------
    private fun parseAllergenSet(text: String): Set<String> {
        val clean = text.lowercase().trim()
        if (clean.isEmpty() || clean == "empty" || clean == "no allergens detected") return emptySet()

        return clean.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeAllergen(it) }
            .filter { it in allowedAllergens.toSet() }
            .toSet()
    }

    private fun normalizeAllergen(a: String): String {
        return when (a.trim().lowercase()) {
            "treenut", "tree nuts", "tree_nut", "tree-nut" -> "tree nut"
            else -> a.trim().lowercase()
        }
    }
}
