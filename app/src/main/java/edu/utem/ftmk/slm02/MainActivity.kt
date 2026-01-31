package edu.utem.ftmk.slm02

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID



class MainActivity : AppCompatActivity() {

    private val db = Firebase.firestore

    private var activeModel = ""
    private var currentlyLoadedRows = mutableListOf<List<String>>()

    // Dashboard/global counters (optional)
    private var totalTP = 0
    private var totalFP = 0
    private var totalFN = 0
    private var exactMatches = 0
    private var hallucinationCount = 0
    private var totalLatency = 0.0
    private var totalTTFT = 0.0
    private var totalOTPS = 0.0
    private var totalItemsInBatch = 0
    private var lastPSS = 0L

    // Model picker flow
    private var pendingRunBatch = false
    private var pendingModelName = ""

    private var currentSessionId: String = ""
    private var currentDatasetLabel: String = ""
    private var currentDatasetKey: String = ""

    private val PREFS = "bench_prefs"
    private val KEY_SESSION_ID = "experiment_session_id"

    companion object {
        init {
            System.loadLibrary("native-lib")
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("llama")
        }
    }

    // MUST match JNI
    external fun inferAllergens(modelPath: String, prompt: String): String

    private val allowedAllergens = setOf(
        "milk", "egg", "peanut", "tree nut", "wheat",
        "soy", "fish", "shellfish", "sesame"
    )


    // Pick GGUF and copy into internal storage
    private val pickModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                Toast.makeText(this, "No model selected.", Toast.LENGTH_SHORT).show()
                pendingRunBatch = false
                return@registerForActivityResult
            }

            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val modelName = pendingModelName.ifEmpty { "selected_model.gguf" }
            val ok = copyUriToInternalModels(uri, modelName)

            if (ok) {
                Toast.makeText(this, "Model saved: $modelName", Toast.LENGTH_SHORT).show()
                if (pendingRunBatch) {
                    pendingRunBatch = false
                    runAllAnalysis()
                }
            } else {
                Toast.makeText(this, "Failed to save model.", Toast.LENGTH_LONG).show()
                pendingRunBatch = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupSpinners()
        setupNavigation()

        currentSessionId = getOrCreateSessionId()
        Toast.makeText(this, "Benchmark Session: $currentSessionId", Toast.LENGTH_SHORT).show()

        findViewById<Button>(R.id.btnLoad).setOnClickListener { performLoad() }
        findViewById<Button>(R.id.btnRunBatch).setOnClickListener { runAllAnalysis() }

        // Optional: long press Run to pick model
        findViewById<Button>(R.id.btnRunBatch).setOnLongClickListener {
            activeModel = findViewById<Spinner>(R.id.spinnerModel).selectedItem.toString()
            pendingModelName = activeModel
            pendingRunBatch = false
            pickModelLauncher.launch(arrayOf("*/*"))
            true
        }

        // Import model button
        findViewById<Button>(R.id.btnImportModel).setOnClickListener {
            activeModel = findViewById<Spinner>(R.id.spinnerModel).selectedItem.toString()
            pendingModelName = activeModel
            pendingRunBatch = false
            pickModelLauncher.launch(arrayOf("*/*"))
        }
    }

    // -------------------------
    // Session ID (Prefs)
    // -------------------------
    private fun getOrCreateSessionId(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val existing = prefs.getString(KEY_SESSION_ID, "") ?: ""
        if (existing.isNotBlank()) return existing

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_SESSION_ID, newId).apply()
        return newId
    }

    // Optional helper
    private fun resetSessionId() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit().remove(KEY_SESSION_ID).apply()
        currentSessionId = getOrCreateSessionId()
        Toast.makeText(this, "New benchmark session: $currentSessionId", Toast.LENGTH_SHORT).show()
    }

    // -------------------------
    // UI setup
    // -------------------------
    private fun setupSpinners() {
        val models = listOf(
            "Llama-3.2-1B",
            "Llama-3.2-3B",
            "qwen2.5-1.5b",
            "qwen2.5-3b",
            "Phi-3-mini-4k",
            "Phi-3.5-mini",
            "Vikhr-Gemma-2B"
        )
        findViewById<Spinner>(R.id.spinnerModel).adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)

        val sets = (1..20).map { "Dataset $it (Items ${(it - 1) * 10 + 1}-${it * 10})" }.toMutableList()
        sets.add("Full Dataset (200 items)")
        findViewById<Spinner>(R.id.spinnerDataset).adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sets)
    }

    private fun setupNavigation() {
        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_dashboard) {
                openDashboard()
                true
            } else false
        }
    }

    // -------------------------
    // Load dataset
    // -------------------------
    private fun performLoad() {
        val spinner = findViewById<Spinner>(R.id.spinnerDataset)
        val pos = spinner.selectedItemPosition

        val allData = loadCSV()

        currentDatasetLabel = spinner.selectedItem.toString()
        currentDatasetKey = if (pos < 20) String.format("DS_%02d", pos + 1) else "FULL_200"

        currentlyLoadedRows = if (pos < 20) {
            allData.subList(pos * 10, (pos + 1) * 10).toMutableList()
        } else {
            allData.take(200).toMutableList()
        }

        val container = findViewById<LinearLayout>(R.id.resultsContainer)
        container.removeAllViews()
        resetMetrics()

        currentlyLoadedRows.forEach { row ->
            // Expected column index 13, Ingredients 3, Link 2, Name 1, Id 0 (based on your dataset)
            addFoodItemToUI(container, row[0], row[1], row[13], row[3], row[2])
        }

        Toast.makeText(this, "Loaded: $currentDatasetLabel", Toast.LENGTH_SHORT).show()
    }

    // -------------------------
    // Batch run (skip if exists)
    // -------------------------
    private fun runAllAnalysis() {

        // 1️⃣ Must load dataset first
        if (currentlyLoadedRows.isEmpty()) {
            Toast.makeText(this, "Load dataset first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentSessionId.isBlank()) currentSessionId = getOrCreateSessionId()

        // 2️⃣ Model selection
        activeModel = findViewById<Spinner>(R.id.spinnerModel).selectedItem.toString()

        // 3️⃣ Model file check
        val modelFile = File(getModelsDir(), activeModel)
        if (!modelFile.exists() || modelFile.length() < 1024) {
            pendingRunBatch = true
            pendingModelName = activeModel
            Toast.makeText(
                this,
                "Select GGUF for: $activeModel (will be copied into app storage)",
                Toast.LENGTH_LONG
            ).show()
            pickModelLauncher.launch(arrayOf("*/*"))
            return
        }

        val modelPath = modelFile.absolutePath

        // 4️⃣ Progress UI
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val analyzingSection = findViewById<LinearLayout>(R.id.analyzingSection)
        val tvCurrent = findViewById<TextView>(R.id.tvCurrentItemName)

        val totalItems = currentlyLoadedRows.size

        analyzingSection.visibility = View.VISIBLE
        progressBar.max = totalItems
        progressBar.progress = 0
        tvCurrent.text = "Checking existing results..."

        lifecycleScope.launch(Dispatchers.IO) {

            // ✅ Query all docs for this session+dataset+model
            val snap = db.collection("project_benchmarks")
                .whereEqualTo("sessionId", currentSessionId)
                .whereEqualTo("datasetKey", currentDatasetKey)
                .whereEqualTo("model", activeModel)
                .get()
                .await()

            // ✅ Build unique dataId set + (optional) latest doc per dataId
            val existingDataIds = HashSet<String>()
            val latestDocByDataId =
                HashMap<String, com.google.firebase.firestore.DocumentSnapshot>()

            for (doc in snap.documents) {
                val dataId = doc.getString("dataId") ?: continue
                existingDataIds.add(dataId)

                // if duplicates exist in Firestore, keep one (you can prefer newest timestamp)
                val prev = latestDocByDataId[dataId]
                if (prev == null) {
                    latestDocByDataId[dataId] = doc
                } else {
                    val prevTs = prev.getTimestamp("timestamp")?.toDate()?.time ?: 0L
                    val curTs = doc.getTimestamp("timestamp")?.toDate()?.time ?: 0L
                    if (curTs >= prevTs) latestDocByDataId[dataId] = doc
                }
            }

            val existingUniqueCount = existingDataIds.size
            val totalItems = currentlyLoadedRows.size

            // ================================
            // ✅ CASE A: ALL ITEMS EXIST (UNIQUE) → LOAD ONLY
            // ================================
            if (existingUniqueCount >= totalItems) {

                // load UI directly using docs we already fetched (no extra reads)
                currentlyLoadedRows.forEachIndexed { index, row ->
                    val dataId = row[0]
                    val doc = latestDocByDataId[dataId] ?: return@forEachIndexed

                    val name = row[1]
                    val link = row[2]
                    val ingredients = row[3]
                    val expectedStr = row[13]

                    val predStr = doc.getString("predictedAllergens") ?: "EMPTY"

                    val m = InferenceMetrics(
                        latencyMs = doc.getLong("latencyMs") ?: 0L,
                        javaHeapKb = doc.getLong("javaHeapKb") ?: 0L,
                        nativeHeapKb = doc.getLong("nativeHeapKb") ?: 0L,
                        totalPssKb = doc.getLong("pssKb") ?: 0L,
                        ttft = doc.getLong("ttft") ?: 0L,
                        itps = doc.getLong("itps") ?: 0L,
                        otps = doc.getLong("otps") ?: 0L,
                        oet = doc.getLong("oet") ?: 0L
                    )

                    val predSet = parseAllergenSet(predStr)
                    val shownPred =
                        if (predSet.isEmpty()) "No allergens detected" else predSet.joinToString(", ")

                    withContext(Dispatchers.Main) {
                        progressBar.progress = index + 1
                        tvCurrent.text = "Loading ${index + 1} of $totalItems: $name"

                        val resultsContainer = findViewById<LinearLayout>(R.id.resultsContainer)
                        val itemLayout = resultsContainer.getChildAt(index) as? LinearLayout
                        itemLayout?.let {
                            updateItemUI(it, name, expectedStr, shownPred, m, ingredients, link)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    analyzingSection.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "Loaded existing results ($totalItems items).",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            // ================================
            // ✅ CASE B: SOME MISSING → ONLY RUN MISSING ONES
            // ================================
            currentlyLoadedRows.forEachIndexed { index, row ->

                val dataId = row[0]
                val name = row[1]
                val link = row[2]
                val ingredients = row[3]
                val expectedStr = row[13]

                withContext(Dispatchers.Main) {
                    tvCurrent.text = "Processing ${index + 1} of $totalItems: $name"
                    progressBar.progress = index + 1
                }

                // ✅ If already exists (unique), LOAD it (NO inference)
                val existingDoc = latestDocByDataId[dataId]
                if (existingDoc != null) {
                    val predStr = existingDoc.getString("predictedAllergens") ?: "EMPTY"

                    val m = InferenceMetrics(
                        latencyMs = existingDoc.getLong("latencyMs") ?: 0L,
                        javaHeapKb = existingDoc.getLong("javaHeapKb") ?: 0L,
                        nativeHeapKb = existingDoc.getLong("nativeHeapKb") ?: 0L,
                        totalPssKb = existingDoc.getLong("pssKb") ?: 0L,
                        ttft = existingDoc.getLong("ttft") ?: 0L,
                        itps = existingDoc.getLong("itps") ?: 0L,
                        otps = existingDoc.getLong("otps") ?: 0L,
                        oet = existingDoc.getLong("oet") ?: 0L
                    )

                    val predSet = parseAllergenSet(predStr)
                    val shownPred =
                        if (predSet.isEmpty()) "No allergens detected" else predSet.joinToString(", ")

                    withContext(Dispatchers.Main) {
                        val resultsContainer = findViewById<LinearLayout>(R.id.resultsContainer)
                        val itemLayout = resultsContainer.getChildAt(index) as? LinearLayout
                        itemLayout?.let {
                            updateItemUI(it, name, expectedStr, shownPred, m, ingredients, link)
                        }
                    }
                    return@forEachIndexed
                }

                // ✅ Missing -> RUN inference and SAVE using deterministic docId
                val docId = makeDocId(currentSessionId, currentDatasetKey, activeModel, dataId)

                val prompt = buildPrompt(ingredients)
                val start = System.nanoTime()
                val nativeOut = inferAllergens(modelPath, prompt)
                val end = System.nanoTime()
                val latencyMs = (end - start) / 1_000_000
                val pssAfter = MemoryReader.totalPssKb()

                val (predTextRaw, metaRaw) = splitPredictionAndMeta(nativeOut)
                val predSet = parseAllergenSet(predTextRaw)
                val predStr = if (predSet.isEmpty()) "EMPTY" else predSet.joinToString(", ")

                val metaMap = if (metaRaw.isNotEmpty()) parseMetricsMap(metaRaw) else emptyMap()

                val m = InferenceMetrics(
                    latencyMs = latencyMs,
                    javaHeapKb = MemoryReader.javaHeapKb(),
                    nativeHeapKb = MemoryReader.nativeHeapKb(),
                    totalPssKb = pssAfter,
                    ttft = metaMap["TTFT_MS"] ?: 0L,
                    itps = metaMap["ITPS"] ?: 0L,
                    otps = metaMap["OTPS"] ?: 0L,
                    oet = metaMap["OET_MS"] ?: 0L
                )

                val shownPred =
                    if (predSet.isEmpty()) "No allergens detected" else predSet.joinToString(", ")

                withContext(Dispatchers.Main) {
                    val resultsContainer = findViewById<LinearLayout>(R.id.resultsContainer)
                    val itemLayout = resultsContainer.getChildAt(index) as? LinearLayout
                    itemLayout?.let {
                        updateItemUI(it, name, expectedStr, shownPred, m, ingredients, link)
                    }
                }

                saveToFirebase(docId, dataId, name, ingredients, expectedStr, predStr, m)
            }

            withContext(Dispatchers.Main) {
                analyzingSection.visibility = View.GONE
                Toast.makeText(
                    this@MainActivity,
                    "Batch Analysis Completed ($totalItems items)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }


        // -------------------------
    // UI item rendering
    // -------------------------
    private fun addFoodItemToUI(
        container: LinearLayout,
        id: String,
        name: String,
        exp: String,
        ing: String,
        link: String
    ) {
        val itemView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 16)
        }

        val txt = TextView(this).apply {
            text = "🍽️ #$id $name\n✅ Expected: $exp\n🤖 Predicted: Pending..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(android.graphics.Color.BLACK)
        }
        itemView.addView(txt)

        val btnReadMore = Button(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Read More"
            setOnClickListener {
                val placeholder = InferenceMetrics(0, 0, 0, 0, 0, 0, 0, 0)
                showDetailsDialog(name, exp, "Pending Analysis...", placeholder, ing, link)
            }
        }

        itemView.addView(btnReadMore)
        container.addView(itemView)
    }

    private fun updateItemUI(
        layout: LinearLayout,
        name: String,
        exp: String,
        pred: String,
        m: InferenceMetrics,
        ing: String,
        link: String
    ) {
        val txt = layout.getChildAt(0) as TextView

        val expSet = parseAllergenSet(exp)
        val predSet = parseAllergenSet(pred)


        val isMatch = expSet == predSet
        val shownPred = if (predSet.isEmpty()) "No allergens detected" else predSet.joinToString(", ")

        val titleLine = txt.text.toString().lineSequence().firstOrNull() ?: "🍽️ $name"
        txt.text =
            "$titleLine\n" +
                    "✅ Expected: $exp\n" +
                    "🤖 Predicted: $shownPred"

        txt.setTextColor(
            if (isMatch) android.graphics.Color.parseColor("#2E7D32")
            else android.graphics.Color.RED
        )

        val btn = if (layout.childCount >= 2) layout.getChildAt(1) as Button else null
        btn?.setOnClickListener { showDetailsDialog(name, exp, shownPred, m, ing, link) }
    }

    private fun showDetailsDialog(
        name: String,
        exp: String,
        pred: String,
        m: InferenceMetrics,
        ing: String,
        link: String
    ) {
        val expSet = parseAllergenSet(exp)
        val predSet = parseAllergenSet(pred)
        val exactMatchText = if (expSet == predSet) "Match ✅" else "Mismatch ❌"

        AlertDialog.Builder(this)
            .setTitle("Item Detail: $name")
            .setMessage(
                "🔗 Source: $link\n\n" +
                        "🌿 INGREDIENTS:\n$ing\n\n" +
                        "📊 ALLERGEN ANALYSIS:\n" +
                        "Expected: $exp\n" +
                        "Predicted: $pred\n" +
                        "Exact Match: $exactMatchText\n\n" +
                        "📈 PERFORMANCE METRICS:\n" +
                        "Latency: ${m.latencyMs} ms\n" +
                        "TTFT: ${m.ttft} ms\n" +
                        "ITPS: ${m.itps} tok/s\n" +
                        "OTPS: ${m.otps} tok/s\n" +
                        "OET: ${m.oet} ms\n\n" +
                        "💾 MEMORY SNAPSHOT:\n" +
                        "Java Heap: ${m.javaHeapKb} KB\n" +
                        "Native Heap: ${m.nativeHeapKb} KB\n" +
                        "Total PSS: ${m.totalPssKb} KB"
            )
            .setPositiveButton("CLOSE", null)
            .show()
    }


    // -------------------------
    // Native output parsing
    // -------------------------
    private fun splitPredictionAndMeta(nativeOut: String): Pair<String, String> {
        val s = nativeOut.trim()
        val parts = s.split("|", limit = 2)
        return if (parts.size == 2) {
            val meta = parts[0].trim()
            val pred = parts[1].trim()
            pred to meta
        } else {
            s to ""
        }
    }

    private fun parseMetricsMap(meta: String): Map<String, Long> {
        return meta.split(";")
            .mapNotNull { kv ->
                val p = kv.split("=")
                if (p.size == 2) (p[0].trim() to (p[1].trim().toLongOrNull() ?: 0L)) else null
            }.toMap()
    }

    private fun parseAllergenSet(text: String): Set<String> {
        val clean = text.lowercase().trim()
        if (clean.isEmpty() || clean == "empty") return emptySet()

        return clean.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeAllergen(it) }
            .filter { it in allowedAllergens }
            .toSet()
    }

    private fun normalizeAllergen(a: String): String {
        return when (a.trim().lowercase()) {
            "treenut", "tree nuts", "tree_nut", "tree-nut" -> "tree nut"
            else -> a.trim().lowercase()
        }
    }

    // -------------------------
    // Metrics aggregation (optional)
    // -------------------------
    private fun updateGlobalMetrics(exp: Set<String>, pred: Set<String>, m: InferenceMetrics, ing: String) {
        totalTP += pred.intersect(exp).size
        totalFP += pred.subtract(exp).size
        totalFN += exp.subtract(pred).size

        totalItemsInBatch++
        if (exp == pred) exactMatches++

        val ingredientsLower = ing.lowercase()
        if (pred.any { !ingredientsLower.contains(it) }) hallucinationCount++

        totalLatency += m.latencyMs
        totalTTFT += m.ttft
        totalOTPS += m.otps
        lastPSS = m.totalPssKb
    }

    // -------------------------
    // Firestore save (per-item metrics included)
    // -------------------------
    private suspend fun saveToFirebase(
        docId: String,
        id: String,
        name: String,
        ing: String,
        exp: String,
        pred: String,
        m: InferenceMetrics
    ) {
        val expSet = parseAllergenSet(exp)
        val predSet = parseAllergenSet(pred)


        val tp = predSet.intersect(expSet).size
        val fp = predSet.subtract(expSet).size
        val fn = expSet.subtract(predSet).size
        val tn = allowedAllergens.size - tp - fp - fn

        val itemPrecision = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
        val itemRecall = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
        val itemF1 = if ((2 * tp + fp + fn) == 0) 0.0 else (2.0 * tp) / (2 * tp + fp + fn)
        val itemHamming = (fp + fn).toDouble() / allowedAllergens.size.toDouble()

        val ingredientsLower = ing.lowercase()
        val hallucinationFlag = predSet.any { !ingredientsLower.contains(it) }
        val overPredictionFlag = predSet.subtract(expSet).isNotEmpty()

        val abstentionExpected = expSet.isEmpty()
        val abstentionCorrect = abstentionExpected && predSet.isEmpty()

        val record = hashMapOf(
            "sessionId" to currentSessionId,
            "datasetKey" to currentDatasetKey,
            "dataset" to currentDatasetLabel,
            "model" to activeModel,

            "dataId" to id,
            "name" to name,
            "ingredients" to ing,
            "expectedAllergens" to exp,
            "predictedAllergens" to pred,

            "tp" to tp, "fp" to fp, "fn" to fn, "tn" to tn,

            "itemPrecision" to itemPrecision,
            "itemRecall" to itemRecall,
            "itemF1" to itemF1,
            "itemHammingLoss" to itemHamming,

            "hallucinationFlag" to hallucinationFlag,
            "overPredictionFlag" to overPredictionFlag,
            "abstentionExpected" to abstentionExpected,
            "abstentionCorrect" to abstentionCorrect,

            "latencyMs" to m.latencyMs,
            "ttft" to m.ttft,
            "itps" to m.itps,
            "otps" to m.otps,
            "oet" to m.oet,

            "javaHeapKb" to m.javaHeapKb,
            "nativeHeapKb" to m.nativeHeapKb,
            "pssKb" to m.totalPssKb,

            "timestamp" to FieldValue.serverTimestamp()
        )

        // IMPORTANT: actually save it (and wait for completion)
        db.collection("project_benchmarks")
            .document(docId)
            .set(record)
            .await()
    }


    private suspend fun loadExistingResultsOnly(
        progressBar: ProgressBar,
        tvCurrent: TextView
    ) {
        val totalItems = currentlyLoadedRows.size



        currentlyLoadedRows.forEachIndexed { index, row ->
            val dataId = row[0]
            val name = row[1]
            val link = row[2]
            val ingredients = row[3]
            val expectedStr = row[13]

            val docId = makeDocId(currentSessionId, currentDatasetKey, activeModel, dataId)

            val snap = db.collection("project_benchmarks")
                .document(docId)
                .get()
                .await()

            if (!snap.exists()) return@forEachIndexed

            val predStr = snap.getString("predictedAllergens") ?: "EMPTY"

            val m = InferenceMetrics(
                latencyMs = snap.getLong("latencyMs") ?: 0L,
                javaHeapKb = snap.getLong("javaHeapKb") ?: 0L,
                nativeHeapKb = snap.getLong("nativeHeapKb") ?: 0L,
                totalPssKb = snap.getLong("pssKb") ?: 0L,
                ttft = snap.getLong("ttft") ?: 0L,
                itps = snap.getLong("itps") ?: 0L,
                otps = snap.getLong("otps") ?: 0L,
                oet = snap.getLong("oet") ?: 0L
            )

            val predSet = parseAllergenSet(predStr)
            val shownPred =
                if (predSet.isEmpty()) "No allergens detected" else predSet.joinToString(", ")

            withContext(Dispatchers.Main) {
                progressBar.progress = index + 1
                tvCurrent.text = "Loading ${index + 1} of $totalItems: $name"

                val resultsContainer = findViewById<LinearLayout>(R.id.resultsContainer)
                val itemLayout = resultsContainer.getChildAt(index) as? LinearLayout
                itemLayout?.let {
                    updateItemUI(it, name, expectedStr, shownPred, m, ingredients, link)
                }
            }
        }
    }

    // -------------------------
    // CSV loading
    // -------------------------
    private fun loadCSV(): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        assets.open("foodpreprocessed.csv").bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                rows.add(
                    line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
                        .map { it.replace("\"", "").trim() }
                )
            }
        }
        return rows
    }

    // -------------------------
    // Dashboard navigation
    // -------------------------
    private fun openDashboard() {
        if (currentSessionId.isBlank()) currentSessionId = getOrCreateSessionId()

        if (currentDatasetLabel.isBlank() || currentDatasetKey.isBlank()) {
            Toast.makeText(this, "Load a dataset first.", Toast.LENGTH_SHORT).show()
            return
        }

        // ensure latest model
        activeModel = findViewById<Spinner>(R.id.spinnerModel).selectedItem.toString()

        val intent = Intent(this, DashboardActivity::class.java).apply {
            putExtra("SESSION_ID", currentSessionId)
            putExtra("DATASET_KEY", currentDatasetKey)
            putExtra("DATASET_LABEL", currentDatasetLabel)
            putExtra("MODEL_NAME", activeModel)
        }
        startActivity(intent)
    }

    private fun resetMetrics() {
        totalTP = 0
        totalFP = 0
        totalFN = 0
        exactMatches = 0
        hallucinationCount = 0
        totalLatency = 0.0
        totalTTFT = 0.0
        totalOTPS = 0.0
        totalItemsInBatch = 0
        lastPSS = 0L
    }

    // -------------------------
    // Prompt builder
    // -------------------------
    private fun buildPrompt(ingredients: String): String {
        return "You are an allergen extraction system.\n" +
                "Extract ONLY allergens that are clearly present in the ingredients.\n" +
                "Allowed allergens: milk, egg, peanut, tree nut, wheat, soy, fish, shellfish, sesame.\n" +
                "Rules:\n" +
                "1) Output ONLY a comma-separated list using ONLY the allowed words, lowercase.\n" +
                "2) If none are present, output EMPTY.\n" +
                "3) Do NOT explain.\n" +
                "Mapping hints:\n" +
                "- milk: milk, cream, butter, cheese, whey, casein, lactose, yogurt\n" +
                "- egg: egg, albumen, mayonnaise\n" +
                "- wheat: wheat, flour, gluten, semolina\n" +
                "- soy: soy, soya, soy lecithin\n" +
                "- fish: fish, tuna, salmon, cod\n" +
                "- shellfish: shrimp, prawn, crab, lobster\n" +
                "- peanut: peanut, groundnut\n" +
                "- tree nut: almond, cashew, walnut, hazelnut, pistachio\n" +
                "- sesame: sesame, tahini\n" +
                "Ingredients: $ingredients\n" +
                "Answer:"
    }

    // -------------------------
    // Internal model storage (Android 11+ safe)
    // -------------------------
    private fun getModelsDir(): File {
        val dir = File(filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun copyUriToInternalModels(uri: Uri, targetFileName: String): Boolean {
        return try {
            val target = File(getModelsDir(), targetFileName)
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    private fun makeDocId(sessionId: String, datasetKey: String, model: String, dataId: String): String {
        return "${sessionId}_${datasetKey}_${model}_${dataId}"
    }



}


