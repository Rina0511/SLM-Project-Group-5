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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val db = Firebase.firestore

    private var activeModel = ""
    private var currentlyLoadedRows = mutableListOf<List<String>>()

    // Dashboard metrics (simple global counters)
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

    // If user hasn’t selected a model file yet
    private var pendingRunBatch = false
    private var pendingModelName = ""
    private var currentSessionId: String = ""
    private var currentDatasetLabel: String = ""
    private var currentDatasetKey: String = ""   // NEW


    //private var isSessionActive: Boolean = false
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

    // MUST match your JNI in native-lib.cpp
    external fun inferAllergens(modelPath: String, prompt: String): String

    private val allowedAllergens = setOf(
        "milk", "egg", "peanut", "tree nut", "wheat",
        "soy", "fish", "shellfish", "sesame"
    )

    // SAF picker: pick a GGUF file and copy into internal storage
    private val pickModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                Toast.makeText(this, "No model selected.", Toast.LENGTH_SHORT).show()
                pendingRunBatch = false
                return@registerForActivityResult
            }
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val modelName = pendingModelName.ifEmpty { "selected_model.gguf" }
            val ok = copyUriToInternalModels(uri, modelName)

            if (ok) {
                Toast.makeText(this, "Model saved: $modelName", Toast.LENGTH_SHORT).show()
                if (pendingRunBatch) {
                    pendingRunBatch = false
                    runAllAnalysis() // retry after model saved
                }
            } else {
                Toast.makeText(this, "Failed to save model.", Toast.LENGTH_LONG).show()
                pendingRunBatch = false
            }
        }


    private fun getOrCreateSessionId(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val existing = prefs.getString(KEY_SESSION_ID, "") ?: ""
        if (existing.isNotBlank()) return existing

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_SESSION_ID, newId).apply()
        return newId
    }

    // optional: if you want a "New Experiment" button later
    private fun resetSessionId() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit().remove(KEY_SESSION_ID).apply()
        currentSessionId = getOrCreateSessionId()
        Toast.makeText(this, "New benchmark session: $currentSessionId", Toast.LENGTH_SHORT).show()
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

        // Optional: long-press Run to select model file manually
        findViewById<Button>(R.id.btnRunBatch).setOnLongClickListener {
            activeModel = findViewById<Spinner>(R.id.spinnerModel).selectedItem.toString()
            pendingModelName = activeModel
            pendingRunBatch = false
            pickModelLauncher.launch(arrayOf("*/*"))
            true
        }
        findViewById<Button>(R.id.btnImportModel).setOnClickListener {
            activeModel = findViewById<Spinner>(R.id.spinnerModel).selectedItem.toString()
            pendingModelName = activeModel
            pendingRunBatch = false
            pickModelLauncher.launch(arrayOf("*/*"))
        }

    }


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

    private fun performLoad() {
        val spinner = findViewById<Spinner>(R.id.spinnerDataset)
        val pos = spinner.selectedItemPosition
        val allData = loadCSV()
        currentDatasetLabel = findViewById<Spinner>(R.id.spinnerDataset).selectedItem.toString()

        currentDatasetLabel = spinner.selectedItem.toString()

        currentDatasetKey = if (pos < 20) {
            String.format("DS_%02d", pos + 1)
        } else {
            "FULL_200"
        }



        currentlyLoadedRows = if (pos < 20) {
            allData.subList(pos * 10, (pos + 1) * 10).toMutableList()
        } else {
            allData.take(200).toMutableList()
        }

        val container = findViewById<LinearLayout>(R.id.resultsContainer)
        container.removeAllViews()
        resetMetrics()

        currentlyLoadedRows.forEach { row ->
            // row indices based on your earlier usage:
            // id=row[0], name=row[1], link=row[2], ingredients=row[3], expected=row[13]
            addFoodItemToUI(container, row[0], row[1], row[13], row[3], row[2])
        }
    }

    private fun runAllAnalysis() {

        // 1) Must load dataset first
        if (currentlyLoadedRows.isEmpty()) {
            Toast.makeText(this, "Load dataset first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentSessionId.isBlank()) {
            currentSessionId = getOrCreateSessionId()
        }


        // 3) Model selection
        activeModel = findViewById<Spinner>(R.id.spinnerModel).selectedItem.toString()

        // 4) Model file must exist in internal storage
        val modelFile = File(getModelsDir(), activeModel)
        if (!modelFile.exists() || modelFile.length() < 1024) {
            pendingRunBatch = true
            pendingModelName = activeModel
            Toast.makeText(
                this,
                "Select GGUF for: $activeModel (it will be copied into app storage)",
                Toast.LENGTH_LONG
            ).show()
            pickModelLauncher.launch(arrayOf("*/*"))
            return
        }

        val modelPath = modelFile.absolutePath

        // 5) Show progress UI
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val analyzingSection = findViewById<LinearLayout>(R.id.analyzingSection)
        analyzingSection.visibility = View.VISIBLE
        progressBar.max = currentlyLoadedRows.size
        progressBar.progress = 0

        lifecycleScope.launch(Dispatchers.IO) {

            currentlyLoadedRows.forEachIndexed { index, row ->

                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.tvCurrentItemName).text =
                        "Processing ${index + 1} of ${currentlyLoadedRows.size}: ${row[1]}"
                    progressBar.progress = index + 1
                }

                val ingredients = row[3]
                val expectedStr = row[13]
                val expectedSet = parseAllergenSet(expectedStr)

                val prompt =
                    "You are an allergen extraction system.\n" +
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

                val start = System.nanoTime()
                val nativeOut = inferAllergens(modelPath, prompt)
                val end = System.nanoTime()
                val latencyMs = (end - start) / 1_000_000

                val pssAfter = MemoryReader.totalPssKb()

                // native returns: "TTFT_MS=...;ITPS=...;OTPS=...;OET_MS=...|pred"
                val (predTextRaw, metaRaw) = splitPredictionAndMeta(nativeOut)

                val predSet = parseAllergenSet(predTextRaw)
                val aiOut = if (predSet.isEmpty()) "EMPTY" else predSet.joinToString(", ")

                val mMeta = if (metaRaw.isNotEmpty()) parseMetricsMap(metaRaw) else emptyMap()

                val m = InferenceMetrics(
                    latencyMs = latencyMs,
                    javaHeapKb = MemoryReader.javaHeapKb(),
                    nativeHeapKb = MemoryReader.nativeHeapKb(),
                    totalPssKb = pssAfter,
                    ttft = mMeta["TTFT_MS"] ?: 0L,
                    itps = mMeta["ITPS"] ?: 0L,
                    otps = mMeta["OTPS"] ?: 0L,
                    oet = mMeta["OET_MS"] ?: 0L
                )

                updateGlobalMetrics(expectedSet, predSet, m, ingredients)

                withContext(Dispatchers.Main) {
                    val resultsContainer = findViewById<LinearLayout>(R.id.resultsContainer)
                    val itemLayout = resultsContainer.getChildAt(index) as? LinearLayout
                    itemLayout?.let {
                        updateItemUI(
                            it,
                            row[1],
                            expectedStr,
                            aiOut,
                            m,
                            ingredients,
                            row[2]
                        )
                    }
                }

                // IMPORTANT: must include sessionId + dataset in firestore record
                saveToFirebase(row[0], row[1], ingredients, expectedStr, aiOut, m)
            }

            withContext(Dispatchers.Main) {
                analyzingSection.visibility = View.GONE
                Toast.makeText(
                    this@MainActivity,
                    "Batch Analysis Completed (${currentlyLoadedRows.size} items)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }


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
            // icons added here
            text = "🍽️ #$id $name\n✅ Expected: $exp\n🤖 Predicted: Pending..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(android.graphics.Color.BLACK)   // <-- IMPORTANT
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

        // Rebuild full text with icons
        // NOTE: you don't need "base split" anymore
        val titleLine = txt.text.toString().lineSequence().firstOrNull() ?: "🍽️ $name"
        txt.text =
            "$titleLine\n" +
                    "✅ Expected: $exp\n" +
                    "🤖 Predicted: $shownPred"

        txt.setTextColor(
            if (isMatch) android.graphics.Color.parseColor("#2E7D32")
            else android.graphics.Color.RED
        )

        // Update Read More to show latest prediction + metrics
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
        AlertDialog.Builder(this)
            .setTitle("Item Detail: $name")
            .setMessage(
                "🔗 Source: $link\n\n" +
                        "🌿 INGREDIENTS:\n$ing\n\n" +
                        "📊 ALLERGEN ANALYSIS:\n" +
                        "Expected: $exp\n" +
                        "Predicted: $pred\n\n" +
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

    /** If your native returns "PRED||META:K=V;K=V", this splits it.
     *  If not, it returns (nativeOut, "").
     */
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
        // expects "TTFT_MS=123;ITPS=45;OTPS=67;OET_MS=89"
        return meta.split(";")
            .mapNotNull { kv ->
                val p = kv.split("=")
                if (p.size == 2) (p[0].trim() to (p[1].trim().toLongOrNull() ?: 0L)) else null
            }.toMap()
    }

    private fun parseAllergenSet(text: String): Set<String> {
        val clean = text.lowercase().trim()
        if (clean.isEmpty() || clean == "empty") return emptySet()

        // Split by comma, trim, filter allowed
        return clean.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeAllergen(it) }
            .filter { it in allowedAllergens }
            .toSet()
    }

    private fun normalizeAllergen(a: String): String {
        // Normalise common variants
        return when (a.trim().lowercase()) {
            "treenut", "tree nuts", "tree_nut", "tree-nut" -> "tree nut"
            else -> a.trim().lowercase()
        }
    }

    private fun updateGlobalMetrics(exp: Set<String>, pred: Set<String>, m: InferenceMetrics, ing: String) {
        totalTP += pred.intersect(exp).size
        totalFP += pred.subtract(exp).size
        totalFN += exp.subtract(pred).size

        totalItemsInBatch++
        if (exp == pred) exactMatches++

        // simple hallucination check: predicted allergen string appears in ingredients
        // (not perfect, but ok for a basic count)
        if (pred.any { !ing.lowercase().contains(it) }) hallucinationCount++

        totalLatency += m.latencyMs
        totalTTFT += m.ttft
        totalOTPS += m.otps
        lastPSS = m.totalPssKb
    }

    private fun saveToFirebase(
        id: String,
        name: String,
        ing: String,
        exp: String,
        pred: String,
        m: InferenceMetrics
    ) {
        // Optional per-item counts (for Quality table)
        val expSet = parseAllergenSet(exp)
        val predSet = parseAllergenSet(pred)

        val tp = predSet.intersect(expSet).size
        val fp = predSet.subtract(expSet).size
        val fn = expSet.subtract(predSet).size
        val tn = allowedAllergens.size - tp - fp - fn

        val isAbstain = predSet.isEmpty()

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
        db.collection("project_benchmarks").add(record)


    }



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

    private fun setupNavigation() {
        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_dashboard) {
                openDashboard()
                true
            } else false
        }
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

    private fun openDashboard() {
        // sessionId is always available from prefs
        if (currentSessionId.isBlank()) currentSessionId = getOrCreateSessionId()

        // but dataset MUST be chosen (because dashboard filters by datasetLabel)
        if (currentDatasetLabel.isBlank()) {
            Toast.makeText(this, "Load a dataset first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentDatasetKey.isBlank()) {
            Toast.makeText(this, "Load a dataset first.", Toast.LENGTH_SHORT).show()
            return
        }


        val intent = Intent(this, DashboardActivity::class.java).apply {
            putExtra("SESSION_ID", currentSessionId)
            putExtra("DATASET_KEY", currentDatasetKey)
            putExtra("DATASET_LABEL", currentDatasetLabel)
            putExtra("MODEL_NAME", activeModel)
        }
        startActivity(intent)
    }


    // --- Model storage helpers (internal, safe for Android 11+) ---

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
}
