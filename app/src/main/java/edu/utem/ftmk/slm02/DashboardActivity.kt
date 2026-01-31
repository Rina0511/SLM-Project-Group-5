package edu.utem.ftmk.slm02

import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.io.File
import java.io.FileWriter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.Font
import org.apache.poi.ss.usermodel.CellStyle
import java.io.FileOutputStream
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.firebase.firestore.DocumentSnapshot
import org.apache.poi.ss.usermodel.*



class DashboardActivity : AppCompatActivity() {

    private val db = Firebase.firestore

    private var sessionId: String = ""
    private var datasetLabel: String = ""
    private var datasetKey: String = ""
    private var activeModel: String = "--"

    private val allowedAllergens = listOf(
        "milk", "egg", "peanut", "tree nut", "wheat",
        "soy", "fish", "shellfish", "sesame"
    )
    private val labelCount = allowedAllergens.size

    data class ModelAgg(
        val model: String,
        var itemCount: Int = 0,

        // Label-level totals (micro)
        var tp: Int = 0,
        var fp: Int = 0,
        var fn: Int = 0,
        var tn: Int = 0,

        // Item-level tallies
        var exactMatches: Int = 0,
        var hallucinationItems: Int = 0,
        var overPredictionItems: Int = 0,
        var abstentionTotal: Int = 0,
        var abstentionCorrect: Int = 0,

        // Efficiency sums
        var sumLatency: Long = 0,
        var sumTtft: Long = 0,
        var sumItps: Long = 0,
        var sumOtps: Long = 0,
        var sumOet: Long = 0,
        var sumJavaHeapKb: Long = 0,
        var sumNativeHeapKb: Long = 0,
        var sumPssKb: Long = 0,

        // Per-item metric sums (average later)
        var sumItemPrecision: Double = 0.0,
        var sumItemRecall: Double = 0.0,
        var sumItemF1: Double = 0.0,
        var sumItemHamming: Double = 0.0,

        // Macro F1 set after per-label calculation
        var f1Macro: Double = 0.0
    ) {
        // ---- QUALITY (label-level micro) ----
        fun precisionMicro(): Double = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
        fun recallMicro(): Double = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)

        fun f1Micro(): Double {
            val denom = (2 * tp + fp + fn).toDouble()
            return if (denom == 0.0) 0.0 else (2.0 * tp) / denom
        }

        fun emr(): Double = if (itemCount == 0) 0.0 else exactMatches.toDouble() / itemCount

        fun hammingLoss(labelCount: Int): Double {
            val denom = (itemCount * labelCount).toDouble()
            return if (denom == 0.0) 0.0 else (fp + fn).toDouble() / denom
        }

        fun fnr(): Double = if (tp + fn == 0) 0.0 else fn.toDouble() / (tp + fn)

        // ---- QUALITY (per-item averages) ----
        fun avgItemPrecision(): Double = if (itemCount == 0) 0.0 else sumItemPrecision / itemCount
        fun avgItemRecall(): Double = if (itemCount == 0) 0.0 else sumItemRecall / itemCount
        fun avgItemF1(): Double = if (itemCount == 0) 0.0 else sumItemF1 / itemCount
        fun avgItemHamming(): Double = if (itemCount == 0) 0.0 else sumItemHamming / itemCount

        // ---- SAFETY ----
        fun hallucinationRatePct(): Double =
            if (itemCount == 0) 0.0 else hallucinationItems.toDouble() * 100.0 / itemCount

        fun overPredictionRatePct(): Double =
            if (itemCount == 0) 0.0 else overPredictionItems.toDouble() * 100.0 / itemCount

        fun abstentionAccuracyPct(): Double =
            if (abstentionTotal == 0) 0.0 else abstentionCorrect.toDouble() * 100.0 / abstentionTotal

        // ---- EFFICIENCY ----
        fun avgLatency(): Double = if (itemCount == 0) 0.0 else sumLatency.toDouble() / itemCount
        fun avgTtft(): Double = if (itemCount == 0) 0.0 else sumTtft.toDouble() / itemCount
        fun avgItps(): Double = if (itemCount == 0) 0.0 else sumItps.toDouble() / itemCount
        fun avgOtps(): Double = if (itemCount == 0) 0.0 else sumOtps.toDouble() / itemCount
        fun avgOet(): Double = if (itemCount == 0) 0.0 else sumOet.toDouble() / itemCount
        fun avgJavaHeapKb(): Double = if (itemCount == 0) 0.0 else sumJavaHeapKb.toDouble() / itemCount
        fun avgNativeHeapKb(): Double = if (itemCount == 0) 0.0 else sumNativeHeapKb.toDouble() / itemCount
        fun avgPssKb(): Double = if (itemCount == 0) 0.0 else sumPssKb.toDouble() / itemCount
    }

    private var latestAggList: List<ModelAgg> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        sessionId = intent.getStringExtra("SESSION_ID") ?: ""
        datasetKey = intent.getStringExtra("DATASET_KEY") ?: ""
        datasetLabel = intent.getStringExtra("DATASET_LABEL") ?: ""
        activeModel = intent.getStringExtra("MODEL_NAME") ?: "--"

        findViewById<TextView>(R.id.tvHardware).text =
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}"
        findViewById<TextView>(R.id.tvOS).text =
            "OS: Android ${Build.VERSION.RELEASE}"
        findViewById<TextView>(R.id.tvModelName).text =
            "Active Model: $activeModel"
        findViewById<TextView>(R.id.tvDataset).text =
            "Dataset: $datasetLabel ($datasetKey)"

        if (sessionId.isBlank() || datasetKey.isBlank()) {
            Toast.makeText(this, "Missing sessionId/datasetKey. Run analysis first.", Toast.LENGTH_LONG).show()
            return
        }

        loadAggregatesFromFirestore()

        findViewById<Button>(R.id.btnExportCSV).setOnClickListener { exportAggToXlsx() }
    }

    private fun loadAggregatesFromFirestore() {
        Toast.makeText(this, "Loading results from Firebase...", Toast.LENGTH_SHORT).show()

        db.collection("project_benchmarks")
            .whereEqualTo("sessionId", sessionId)
            .whereEqualTo("datasetKey", datasetKey)
            .get()
            .addOnSuccessListener { snap ->

                val map = linkedMapOf<String, ModelAgg>()

                // For macro-F1
                val perLabel = mutableMapOf<String, Triple<IntArray, IntArray, IntArray>>()

                // Prevent duplicates if somehow duplicated docs exist
                val seen = HashSet<String>()

                for (doc in snap.documents) {
                    val model = doc.getString("model") ?: continue
                    val dataId = doc.getString("dataId") ?: ""

                    val key = "$model|$dataId"
                    if (!seen.add(key)) continue  // <-- MUST be inside the loop

                    val agg = map.getOrPut(model) { ModelAgg(model = model) }
                    agg.itemCount++

                    // item-level confusion
                    val tpItem = (doc.getLong("tp") ?: 0L).toInt()
                    val fpItem = (doc.getLong("fp") ?: 0L).toInt()
                    val fnItem = (doc.getLong("fn") ?: 0L).toInt()
                    val tnItem = (doc.getLong("tn") ?: 0L).toInt()

                    agg.tp += tpItem
                    agg.fp += fpItem
                    agg.fn += fnItem
                    agg.tn += tnItem

                    // per-item averages
                    agg.sumItemPrecision += doc.getDouble("itemPrecision") ?: 0.0
                    agg.sumItemRecall += doc.getDouble("itemRecall") ?: 0.0
                    agg.sumItemF1 += doc.getDouble("itemF1") ?: 0.0
                    agg.sumItemHamming += doc.getDouble("itemHammingLoss") ?: 0.0

                    // safety
                    val hallucinationFlag = doc.getBoolean("hallucinationFlag") ?: false
                    val overPredictionFlag = doc.getBoolean("overPredictionFlag") ?: false
                    val abstentionExpected = doc.getBoolean("abstentionExpected") ?: false
                    val abstentionCorrect = doc.getBoolean("abstentionCorrect") ?: false

                    if (hallucinationFlag) agg.hallucinationItems++
                    if (overPredictionFlag) agg.overPredictionItems++
                    if (abstentionExpected) {
                        agg.abstentionTotal++
                        if (abstentionCorrect) agg.abstentionCorrect++
                    }

                    // exact match (from strings)
                    val expectedStr = doc.getString("expectedAllergens") ?: ""
                    val predictedStr = doc.getString("predictedAllergens") ?: ""

                    val expSet = parseAllergenSet(expectedStr)
                    val predSet = parseAllergenSet(predictedStr)
                    if (expSet == predSet) agg.exactMatches++

                    // efficiency
                    agg.sumLatency += doc.getLong("latencyMs") ?: 0L
                    agg.sumTtft += doc.getLong("ttft") ?: 0L
                    agg.sumItps += doc.getLong("itps") ?: 0L
                    agg.sumOtps += doc.getLong("otps") ?: 0L
                    agg.sumOet += doc.getLong("oet") ?: 0L
                    agg.sumJavaHeapKb += doc.getLong("javaHeapKb") ?: 0L
                    agg.sumNativeHeapKb += doc.getLong("nativeHeapKb") ?: 0L
                    agg.sumPssKb += doc.getLong("pssKb") ?: 0L

                    // macro-f1 arrays
                    val labelTrip = perLabel.getOrPut(model) {
                        Triple(IntArray(labelCount), IntArray(labelCount), IntArray(labelCount))
                    }

                    for ((i, lab) in allowedAllergens.withIndex()) {
                        val exp = expSet.contains(lab)
                        val pred = predSet.contains(lab)
                        when {
                            exp && pred -> labelTrip.first[i]++
                            !exp && pred -> labelTrip.second[i]++
                            exp && !pred -> labelTrip.third[i]++
                        }
                    }
                }

                // compute macro-F1 per model
                map.values.forEach { agg ->
                    val (tpArr, fpArr, fnArr) = perLabel[agg.model]
                        ?: Triple(IntArray(labelCount), IntArray(labelCount), IntArray(labelCount))

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

                val list = map.values.sortedByDescending { it.avgItemF1() }
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

    // -------------------- UI table helpers --------------------
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

    private fun addRow(modelTable: TableLayout, metricsTable: TableLayout, model: String, metrics: List<String>) {
        TableRow(this).apply {
            addView(makeCell(model))
            modelTable.addView(this)
        }
        TableRow(this).apply {
            metrics.forEach { addView(makeCell(it)) }
            metricsTable.addView(this)
        }
    }

    // -------------------- TABLE 1: QUALITY --------------------
    // I include both: label-level micro + per-item averages (lecturer requirement)
    private fun populateQualityTable(list: List<ModelAgg>) {
        val tModel = findViewById<TableLayout>(R.id.tableQualityModel)
        val tMetrics = findViewById<TableLayout>(R.id.tableQualityMetrics)

        clearTableKeepHeader(tModel)
        clearTableKeepHeader(tMetrics)

        list.forEach { m ->
            val metrics = listOf(
                String.format("%.4f", m.precisionMicro()),
                String.format("%.4f", m.recallMicro()),
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

            val avgItemF1 = m.avgItemF1()
            val accuracyPct = avgItemF1 * 100.0

            card.findViewById<TextView>(R.id.tvAccuracyPercent).text =
                String.format("%.2f%%", accuracyPct)

// ranking MUST use avgItemF1 (0-1), not percent
            val tag = when {
                avgItemF1 >= 0.70 -> "🥇 Strong Prediction"
                avgItemF1 >= 0.40 -> "🥈 Medium Prediction"
                else -> "⚠️ Weak Prediction"
            }

            card.findViewById<TextView>(R.id.tvRankTag).text = tag

            // Strengths (keep your old format)
            card.findViewById<TextView>(R.id.tvStrength).text =
                "Precision: ${String.format("%.3f", m.precisionMicro())}\n" +
                        "Recall: ${String.format("%.3f", m.recallMicro())}\n" +
                        "EMR: ${String.format("%.3f", m.emr())}\n" +
                        "Exact Matches: ${m.exactMatches}/${m.itemCount}"

            // Weaknesses (old format)
            card.findViewById<TextView>(R.id.tvWeakness).text =
                "FP: ${m.fp}  FN: ${m.fn}\n" +
                        "Hallucination: ${String.format("%.1f", m.hallucinationRatePct())}%\n" +
                        "Avg PSS: ${String.format("%.0f", m.avgPssKb())} KB"

            container.addView(card)
        }
    }


    // -------------------- CSV EXPORT --------------------
    // One CSV file (no sheets). Includes per-item metrics fields now.

    private fun exportAggToXlsx() {

        db.collection("project_benchmarks")
            .whereEqualTo("datasetKey", datasetKey)
            .get()
            .addOnSuccessListener { snap ->
                try {
                    val wb = XSSFWorkbook()

                    // ---------- Styles ----------
                    val headerStyle = wb.createCellStyle().apply {
                        fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                        fillPattern = FillPatternType.SOLID_FOREGROUND
                        alignment = HorizontalAlignment.CENTER
                        verticalAlignment = VerticalAlignment.CENTER
                        borderBottom = BorderStyle.THIN
                        borderTop = BorderStyle.THIN
                        borderLeft = BorderStyle.THIN
                        borderRight = BorderStyle.THIN
                    }
                    val headerFont: Font = wb.createFont().apply { bold = true }
                    headerStyle.setFont(headerFont)

                    val normalStyle = wb.createCellStyle().apply {
                        verticalAlignment = VerticalAlignment.TOP
                        borderBottom = BorderStyle.THIN
                        borderTop = BorderStyle.THIN
                        borderLeft = BorderStyle.THIN
                        borderRight = BorderStyle.THIN
                        wrapText = true
                    }

                    fun safeSheetName(name: String): String {
                        val cleaned = name.replace(Regex("""[\\/?*\[\]:]"""), "_")
                        return if (cleaned.length <= 31) cleaned else cleaned.substring(0, 31)
                    }

                    fun createHeaderRow(sheetName: String, headers: List<String>) {
                        val sh = wb.createSheet(sheetName)
                        val row = sh.createRow(0)
                        headers.forEachIndexed { i, h ->
                            val cell = row.createCell(i, CellType.STRING)
                            cell.setCellValue(h)
                            cell.cellStyle = headerStyle
                        }
                        row.heightInPoints = 22f
                    }

                    // ---------- Headers ----------
                    val itemHeaders = listOf(
                        "SessionId", "DatasetKey", "DatasetLabel", "Model",
                        "DataId", "Name", "Ingredients", "Expected", "Predicted",
                        "ExactMatch",
                        "TP", "FP", "FN", "TN",
                        "ItemPrecision", "ItemRecall", "ItemF1", "ItemHammingLoss",
                        "HallucinationFlag", "OverPredictionFlag", "AbstentionExpected", "AbstentionCorrect",
                        "LatencyMs", "TTFT", "ITPS", "OTPS", "OET",
                        "JavaHeapKb", "NativeHeapKb", "PSSKb", "Timestamp"
                    )

                    val summaryHeaders = listOf(
                        "SessionId", "DatasetKey", "DatasetLabel", "Model", "Count",
                        "AvgItemPrecision", "AvgItemRecall", "AvgItemF1", "AvgItemHammingLoss",
                        "PrecisionMicro", "RecallMicro", "F1Micro", "F1Macro", "EMR", "HammingLoss", "FNR",
                        "TP", "FP", "FN", "TN",
                        "HallucinationRatePct", "OverPredictionRatePct", "AbstentionAccuracyPct",
                        "AvgLatencyMs", "AvgTTFTMs", "AvgITPS", "AvgOTPS", "AvgOETMs",
                        "AvgJavaHeapKB", "AvgNativeHeapKB", "AvgPSSKB"
                    )

                    // ---------- Group docs by model ----------
                    // ---------- Group docs by model (DEDUP by DataId) ----------
                    val docsByModel = linkedMapOf<String, MutableMap<String, DocumentSnapshot>>()

                    for (doc in snap.documents) {
                        val model = doc.getString("model") ?: "UNKNOWN"
                        val dataId = doc.getString("dataId") ?: continue

                        val modelMap = docsByModel.getOrPut(model) { linkedMapOf() }

                        val existing = modelMap[dataId]
                        if (existing == null) {
                            modelMap[dataId] = doc
                        } else {
                            // keep newest doc if duplicate dataId exists
                            val oldT = existing.getTimestamp("timestamp")?.toDate()?.time ?: 0L
                            val newT = doc.getTimestamp("timestamp")?.toDate()?.time ?: 0L
                            if (newT >= oldT) modelMap[dataId] = doc
                        }
                    }


                    // ---------- Create model sheets ----------
                    docsByModel.forEach { (modelName, docMap) ->

                        val sheetName = safeSheetName(modelName)
                        createHeaderRow(sheetName, itemHeaders)
                        val sh = wb.getSheet(sheetName) ?: return@forEach

                        // ✅ Convert deduped map -> list and sort by DataId (optional but nice)
                        val docs = docMap.values
                            .sortedBy { it.getString("dataId")?.toIntOrNull() ?: Int.MAX_VALUE }

                        docs.forEachIndexed { index, doc ->
                            val r = sh.createRow(index + 1)

                            val model = doc.getString("model") ?: ""
                            val dataId = doc.getString("dataId") ?: ""
                            val name = doc.getString("name") ?: ""
                            val ingredients = doc.getString("ingredients") ?: ""
                            val exp = doc.getString("expectedAllergens") ?: ""
                            val pred = doc.getString("predictedAllergens") ?: ""

                            val expSet = parseAllergenSet(exp)
                            val predSet = parseAllergenSet(pred)
                            val exactMatchText = if (expSet == predSet) "Match" else "Mismatch"

                            val tp = doc.getLong("tp") ?: 0L
                            val fp = doc.getLong("fp") ?: 0L
                            val fn = doc.getLong("fn") ?: 0L
                            val tn = doc.getLong("tn") ?: 0L

                            val itemPrecision = doc.getDouble("itemPrecision") ?: 0.0
                            val itemRecall = doc.getDouble("itemRecall") ?: 0.0
                            val itemF1 = doc.getDouble("itemF1") ?: 0.0
                            val itemHam = doc.getDouble("itemHammingLoss") ?: 0.0

                            val hallucinationFlag = doc.getBoolean("hallucinationFlag") ?: false
                            val overPredictionFlag = doc.getBoolean("overPredictionFlag") ?: false
                            val abstentionExpected = doc.getBoolean("abstentionExpected") ?: false
                            val abstentionCorrect = doc.getBoolean("abstentionCorrect") ?: false

                            val latency = doc.getLong("latencyMs") ?: 0L
                            val ttft = doc.getLong("ttft") ?: 0L
                            val itps = doc.getLong("itps") ?: 0L
                            val otps = doc.getLong("otps") ?: 0L
                            val oet = doc.getLong("oet") ?: 0L
                            val javaHeap = doc.getLong("javaHeapKb") ?: 0L
                            val nativeHeap = doc.getLong("nativeHeapKb") ?: 0L
                            val pss = doc.getLong("pssKb") ?: 0L
                            val ts = doc.getTimestamp("timestamp")?.toDate()?.toString() ?: ""

                            val values: List<Any> = listOf(
                                sessionId, datasetKey, datasetLabel, model,
                                dataId, name, ingredients, exp, pred,
                                exactMatchText,
                                tp, fp, fn, tn,
                                itemPrecision, itemRecall, itemF1, itemHam,
                                hallucinationFlag, overPredictionFlag, abstentionExpected, abstentionCorrect,
                                latency, ttft, itps, otps, oet,
                                javaHeap, nativeHeap, pss, ts
                            )

                            values.forEachIndexed { col, v ->
                                val cell = r.createCell(col)
                                when (v) {
                                    is Long -> cell.setCellValue(v.toDouble())
                                    is Int -> cell.setCellValue(v.toDouble())
                                    is Double -> cell.setCellValue(v)
                                    is Boolean -> cell.setCellValue(v.toString())
                                    else -> cell.setCellValue(v.toString())
                                }
                                cell.cellStyle = normalStyle
                            }
                        }

                        // make key columns wider
                        sh.setColumnWidth(5, 9000)   // Name
                        sh.setColumnWidth(6, 14000)  // Ingredients
                        sh.setColumnWidth(7, 9000)   // Expected
                        sh.setColumnWidth(8, 9000)   // Predicted
                        sh.setColumnWidth(0, 5500)   // SessionId
                        sh.setColumnWidth(3, 5500)   // Model

                        // ✅ Timestamp is LAST column now (ExactMatch added)
                        sh.setColumnWidth(31, 7000)  // Timestamp (index 31)
                    }


                    // ---------- SUMMARY sheet ----------
                    val summarySheetName = "SUMMARY"
                    createHeaderRow(summarySheetName, summaryHeaders)
                    val sumSh = wb.getSheet(summarySheetName)!!

                    latestAggList.forEachIndexed { idx, m ->
                        val r = sumSh.createRow(idx + 1)

                        val values: List<Any> = listOf(
                            sessionId, datasetKey, datasetLabel, m.model, m.itemCount,
                            m.avgItemPrecision(), m.avgItemRecall(), m.avgItemF1(), m.avgItemHamming(),
                            m.precisionMicro(), m.recallMicro(), m.f1Micro(), m.f1Macro,
                            m.emr(), m.hammingLoss(labelCount), m.fnr(),
                            m.tp, m.fp, m.fn, m.tn,
                            m.hallucinationRatePct(), m.overPredictionRatePct(), m.abstentionAccuracyPct(),
                            m.avgLatency(), m.avgTtft(), m.avgItps(), m.avgOtps(), m.avgOet(),
                            m.avgJavaHeapKb(), m.avgNativeHeapKb(), m.avgPssKb()
                        )

                        values.forEachIndexed { col, v ->
                            val cell = r.createCell(col)
                            when (v) {
                                is Int -> cell.setCellValue(v.toDouble())
                                is Long -> cell.setCellValue(v.toDouble())
                                is Double -> cell.setCellValue(v)
                                else -> cell.setCellValue(v.toString())
                            }
                            cell.cellStyle = normalStyle
                        }
                    }

                    sumSh.setColumnWidth(0, 5500)
                    sumSh.setColumnWidth(2, 12000)
                    sumSh.setColumnWidth(3, 9000)

                    // ---------- Write workbook to CACHE and SHARE ----------
                    val outFileName = "Benchmark_Export_${sessionId}_${datasetKey}.xlsx"
                    val cacheFile = File(cacheDir, outFileName)

                    FileOutputStream(cacheFile).use { out ->
                        wb.write(out)
                    }
                    wb.close()

                    val uri = FileProvider.getUriForFile(
                        this,
                        "${packageName}.provider",
                        cacheFile
                    )

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    startActivity(Intent.createChooser(shareIntent, "Share Excel file"))

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
