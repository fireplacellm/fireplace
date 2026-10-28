package com.fireplace

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.fireplace.agents.Agent
import com.fireplace.agents.AgentCatalog
import com.fireplace.data.ChatStore
import com.fireplace.data.Conversation
import com.fireplace.data.Catalog
import com.fireplace.data.DownloadBus
import com.fireplace.data.DownloadFile
import com.fireplace.data.DownloadItem
import com.fireplace.data.DownloadService
import com.fireplace.data.Downloader
import com.fireplace.engine.EngineException
import com.fireplace.engine.LlamaEngine
import com.fireplace.rag.CitationReport
import com.fireplace.rag.Citations
import com.fireplace.rag.DeepResearch
import com.fireplace.rag.Hit
import com.fireplace.rag.PackReader
import com.fireplace.rag.Prompt
import com.fireplace.rag.Retriever
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SourceRef(val n: Int, val title: String, val docId: String, val text: String = "")

data class ChatMessage(
    val role: String,              // "user" | "assistant" | "system"
    val text: String,
    val sources: List<SourceRef> = emptyList(),
    val citationPassRate: Double? = null,
    val streaming: Boolean = false,
)

enum class Status { LOADING, READY, NO_MODEL, NO_PACKS, ERROR }

/** Research = retrieval-grounded, cited answers. Chat = casual, no forced grounding. */
enum class Mode { RESEARCH, CHAT }

data class PackInfo(
    val id: String,
    val passages: Int,          // 0 if not loaded (disabled)
    val title: String,
    val description: String,
    val sizeBytes: Long,
    val enabled: Boolean,
    val loaded: Boolean,        // currently open in the retriever
)

/** A local GGUF model the user can switch between (fast vs. rich). */
data class ModelInfo(val fileName: String, val label: String, val sizeBytes: Long, val selected: Boolean)

/** How well a model fits this device's RAM (drives the catalogue badge).
 *  est RAM ≈ size × 1.15; GREAT ≤ 0.65·RAM, OK ≤ 0.90·RAM, else INCOMPATIBLE. */
enum class ModelFit { GREAT, OK, INCOMPATIBLE }

/** One row in the model catalogue: a catalog model or an on-disk "added by you"
 *  model, with its install/active state and device fit. */
data class ModelCatalogEntry(
    val id: String,
    val name: String,
    val desc: String,
    val sizeBytes: Long,
    val fileName: String,
    val minRamGb: Double,
    val recommended: Boolean,
    val onDisk: Boolean,
    val active: Boolean,
    val fit: ModelFit,
    val downloadItem: DownloadItem?,   // null = local model not in the catalog
) {
    val sizeLabel: String get() =
        if (sizeBytes < 1_073_741_824L) "%.0f MB".format(sizeBytes / 1_048_576.0)
        else "%.1f GB".format(sizeBytes / 1_073_741_824.0)
}

/**
 * Drives the chat: loads packs + engine, runs retrieve → ground → stream-generate
 * → verify-citations. Mirrors pipeline/orchestrator.py. Generation runs on a
 * background thread; streamed tokens update Compose state live.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    val messages = mutableStateListOf<ChatMessage>()
    var status by mutableStateOf(Status.LOADING); private set
    var statusDetail by mutableStateOf(""); private set
    var generating by mutableStateOf(false); private set
    var genStatus by mutableStateOf(""); private set   // narrated phase while generating
    var importMsg by mutableStateOf<String?>(null)
    var pods by mutableStateOf<List<String>>(emptyList()); private set
    var packInfos by mutableStateOf<List<PackInfo>>(emptyList()); private set
    var mode by mutableStateOf(Mode.RESEARCH)
    // Deep research: a multi-pass pipeline (outline → per-section drafting over real
    // sources → assembly) for a more thorough, structured, well-cited answer. Slower.
    var deepResearch by mutableStateOf(false)
    var deviceInfo by mutableStateOf(""); private set
    var models by mutableStateOf<List<ModelInfo>>(emptyList()); private set
    /** Label of the currently active model (shown in the top bar), e.g. "Qwen2.5 0.5B". */
    val activeModelName: String get() = models.firstOrNull { it.selected }?.label ?: ""

    // First-run onboarding: show the intro carousel until the user finishes it.
    private val appPrefs by lazy { getApplication<Application>().getSharedPreferences("fp_app", Context.MODE_PRIVATE) }
    var showOnboarding by mutableStateOf(false)
    fun completeOnboarding() {
        appPrefs.edit().putBoolean("onboarded", true).apply(); showOnboarding = false
        // First run just finished: fetch a default model so the app works immediately.
        maybeAutoDownloadDefault()
    }

    // In-app setup download (streaming; INTERNET used only here, never at inference).
    val catalogModels: List<DownloadItem> get() = Catalog.models
    val catalogPack: DownloadItem get() = Catalog.pack
    val catalogEmbedder: DownloadItem get() = Catalog.embedder
    val packHostConfigured: Boolean get() = Catalog.hostConfigured
    /** True once a knowledge pack is on disk (the bundled starter, or a download). */
    var packInstalled by mutableStateOf(false); private set
    var downloading by mutableStateOf(false); private set
    var downloadProgress by mutableStateOf<Downloader.Progress?>(null); private set
    var downloadSpeedBps by mutableStateOf(0.0); private set     // bytes/sec, smoothed
    var downloadEtaSec by mutableStateOf(0L); private set        // seconds remaining, 0 = unknown
    var downloadingModelId by mutableStateOf<String?>(null); private set  // which catalogue model is downloading
    // The full model catalogue (catalog models + any on-disk "added by you"), with
    // per-device fit + install/active state. Drives the ModelCatalog screen.
    var modelCatalog by mutableStateOf<List<ModelCatalogEntry>>(emptyList()); private set
    fun deviceRam(): Double = deviceRamGb()

    // Rolling window for download speed/ETA.
    private var dlPrevBytes = 0L
    private var dlPrevMs = 0L

    /** Download the chosen catalog items in a foreground service, then hot-reload
     *  the engine + packs. The service keeps the (multi-GB) transfer alive even if
     *  the app is backgrounded or the screen turns off. */
    /** Shared transfer driver: streams [files] in the foreground service, tracks
     *  progress + smoothed speed/ETA, and calls [onDone] on completion. */
    private fun startDownload(files: List<DownloadFile>, totalBytes: Long, onDone: (Boolean, String?) -> Unit) {
        if (files.isEmpty()) return
        downloading = true
        downloadProgress = Downloader.Progress(0, totalBytes, "")
        downloadSpeedBps = 0.0; downloadEtaSec = 0L
        dlPrevBytes = 0L; dlPrevMs = System.currentTimeMillis()
        val app = getApplication<Application>()
        // Compose snapshot state is thread-safe to set from the service's callback.
        DownloadBus.observe(
            onProgress = { p ->
                downloadProgress = p
                val now = System.currentTimeMillis(); val dt = now - dlPrevMs
                if (dt > 400) {
                    val db = p.doneBytes - dlPrevBytes
                    if (db > 0) {
                        val bps = db * 1000.0 / dt
                        downloadSpeedBps = if (downloadSpeedBps <= 0.0) bps else downloadSpeedBps * 0.6 + bps * 0.4
                        val remain = (p.totalBytes - p.doneBytes).coerceAtLeast(0)
                        downloadEtaSec = if (downloadSpeedBps > 1) (remain / downloadSpeedBps).toLong() else 0L
                    }
                    dlPrevBytes = p.doneBytes; dlPrevMs = now
                }
            },
            onComplete = { ok, err ->
                DownloadBus.clearObservers()
                downloading = false; downloadProgress = null; downloadSpeedBps = 0.0; downloadEtaSec = 0L
                onDone(ok, err)
            },
        )
        androidx.core.content.ContextCompat.startForegroundService(app, DownloadService.startIntent(app, files))
    }

    fun downloadItems(items: List<DownloadItem>) {
        if (downloading) return
        startDownload(items.flatMap { it.files }, items.sumOf { it.totalBytes }) { ok, err ->
            if (ok) { status = Status.LOADING; reloadModelAndPacks() } else importMsg = err
        }
    }

    /** Download one catalog model from the Model catalogue. If the app has no usable
     *  model yet, the new one becomes active; otherwise it's just added and the user
     *  can switch to it with [selectModel]. */
    fun downloadModel(entry: ModelCatalogEntry) {
        val item = entry.downloadItem ?: return
        if (downloading) return
        downloadingModelId = item.id
        startDownload(item.files, item.totalBytes) { ok, err ->
            downloadingModelId = null
            if (ok) {
                val hadModel = status == Status.READY && engine != null
                if (hadModel) { refreshModels() }
                else { modelPrefs.edit().putString("model", entry.fileName).apply(); status = Status.LOADING; reloadModelAndPacks() }
            } else importMsg = err
        }
    }

    /** Delete a model's weights. If it was active, switch to another installed model,
     *  or drop to the setup screen when none remain. */
    fun deleteModel(fileName: String) = Thread {
        if (downloading) return@Thread
        val wasActive = fileName == modelFile.name
        try { if (wasActive) { engine?.close(); engine = null } } catch (_: Exception) {}
        File(ggufDir, fileName).delete()
        if (modelPrefs.getString("model", null) == fileName) modelPrefs.edit().remove("model").apply()
        if (wasActive) {
            val next = ggufFiles().firstOrNull()
            if (next != null) {
                modelPrefs.edit().putString("model", next.name).apply()
                status = Status.LOADING
                try { loadEngine() } catch (e: Exception) { status = Status.ERROR; statusDetail = e.message ?: "model load failed" }
            } else { status = Status.NO_MODEL; statusDetail = ggufDir.absolutePath }
        }
        refreshModels()
        importMsg = "Removed ${modelLabel(fileName)}."
    }.start()

    private fun fitFor(sizeBytes: Long, minRamGb: Double, ram: Double): ModelFit {
        val estGb = sizeBytes * 1.15 / 1_073_741_824.0
        return when {
            ram + 0.3 < minRamGb -> ModelFit.INCOMPATIBLE
            estGb <= ram * 0.65 -> ModelFit.GREAT
            estGb <= ram * 0.90 -> ModelFit.OK
            else -> ModelFit.INCOMPATIBLE
        }
    }

    /** Rebuild [modelCatalog] from the catalog + on-disk files + device RAM. */
    private fun refreshModelCatalog() {
        val ram = deviceRamGb()
        val activeName = modelFile.name
        val onDisk = ggufFiles().associateBy { it.name }
        val catalogNames = Catalog.models.map { it.files.first().name }.toSet()
        val entries = ArrayList<ModelCatalogEntry>()
        for (m in Catalog.models) {
            val fname = m.files.first().name
            entries.add(ModelCatalogEntry(
                m.id, m.name, m.desc, m.totalBytes, fname, m.minRamGb, m.recommended,
                onDisk = onDisk.containsKey(fname),
                active = onDisk.containsKey(fname) && fname == activeName,
                fit = fitFor(m.totalBytes, m.minRamGb, ram), downloadItem = m,
            ))
        }
        for ((name, f) in onDisk) if (name !in catalogNames) {
            entries.add(ModelCatalogEntry(
                "local:$name", modelLabel(name), "Added to this phone.", f.length(), name, 0.0, false,
                onDisk = true, active = name == activeName, fit = ModelFit.OK, downloadItem = null,
            ))
        }
        modelCatalog = entries
    }

    /** The best model to auto-install on first run: the recommended one that fits,
     *  else the smallest comfortably-fitting model. */
    private fun recommendedEntry(): ModelCatalogEntry? {
        refreshModelCatalog()
        val ram = deviceRamGb()
        val fitting = modelCatalog.filter { it.downloadItem != null && !it.onDisk && it.fit != ModelFit.INCOMPATIBLE }
        val great = fitting.filter { it.fit == ModelFit.GREAT }
        // Weak phone (<6 GB): the smallest good model. A bigger one can't stay resident
        // in RAM here - its weights get paged from flash and decode thrashes (measured
        // 0.4 tok/s for the 1.5B vs fast for the 0.5B). 6 GB+ gets the "recommended" balance.
        return if (ram < 6.0) {
            (great.ifEmpty { fitting }).minByOrNull { it.sizeBytes }
        } else {
            fitting.firstOrNull { it.recommended && it.fit == ModelFit.GREAT }
                ?: great.minByOrNull { it.sizeBytes }
                ?: fitting.minByOrNull { it.sizeBytes }
        }
    }

    /** On first run (after onboarding), download a sensible default model so the app
     *  is usable out of the box - the user can change it later in the catalogue.
     *  Guarded by a pref so we try it only once and never nag. */
    fun maybeAutoDownloadDefault() {
        if (status != Status.NO_MODEL || downloading) return
        if (appPrefs.getBoolean("auto_dl_attempted", false)) return
        val pick = recommendedEntry() ?: return
        appPrefs.edit().putBoolean("auto_dl_attempted", true).apply()
        downloadModel(pick)
    }

    fun cancelDownload() {
        val app = getApplication<Application>()
        app.startService(DownloadService.cancelIntent(app))
        DownloadBus.clearObservers()
        downloading = false; downloadProgress = null; downloadingModelId = null
    }

    /** Reload model + packs after a download, without reopening the data stores. */
    private fun reloadModelAndPacks() = Thread {
        try {
            if (embedder == null)
                embedder = try { com.fireplace.rag.Embedder.load(getApplication<Application>().getExternalFilesDir(null)!!) } catch (_: Exception) { null }
            if (reranker == null)
                reranker = try { com.fireplace.rag.Reranker.load(getApplication<Application>().getExternalFilesDir(null)!!) } catch (_: Exception) { null }
            reloadPacks()
            if (!modelFile.exists()) { status = Status.NO_MODEL; statusDetail = ggufDir.absolutePath; return@Thread }
            loadEngine(); refreshModels()
        } catch (e: Exception) { status = Status.ERROR; statusDetail = e.message ?: "reload failed" }
    }.start()

    // Specialized agents (declarative personas + data scope). The chosen agent
    // shapes the system prompt, the retrieval scope, and the suggested prompts.
    var agent by mutableStateOf(AgentCatalog.default); private set
    val agents: List<Agent> get() = AgentCatalog.all

    // Chat history (persisted locally; survives restarts).
    var conversations by mutableStateOf<List<Conversation>>(emptyList()); private set
    var currentConvId by mutableStateOf<String?>(null); private set

    private var engine: LlamaEngine? = null
    private var retriever: Retriever? = null
    private var embedder: com.fireplace.rag.Embedder? = null
    private var reranker: com.fireplace.rag.Reranker? = null
    private val readers = ArrayList<PackReader>()
    private val packFiles = HashMap<String, File>()
    private var store: ChatStore? = null
    @Volatile private var stopRequested = false
    @Volatile private var activeModelMb = 500L   // used to scale the generation watchdog

    // Enable/disable state for installed packs (user-managed catalog). Personal
    // pods and any pack with no stored preference default to enabled.
    private val packPrefs by lazy {
        getApplication<Application>().getSharedPreferences("fp_packs", Context.MODE_PRIVATE)
    }
    private fun isEnabled(id: String): Boolean = packPrefs.getBoolean("enabled:$id", true)

    private val modelPrefs by lazy {
        getApplication<Application>().getSharedPreferences("fp_model", Context.MODE_PRIVATE)
    }
    private val ggufDir: File get() = getApplication<Application>().getExternalFilesDir(null)!!
    private fun ggufFiles(): List<File> =
        ggufDir.listFiles { f -> f.extension.equals("gguf", true) }?.sortedBy { it.length() } ?: emptyList()

    /** The model to load: the user's saved choice, else model.gguf, else the
     *  smallest installed .gguf (smallest = fastest, a safe default). */
    private val modelFile: File
        get() {
            val saved = modelPrefs.getString("model", null)?.let { File(ggufDir, it) }
            if (saved != null && saved.exists()) return saved
            val legacy = File(ggufDir, "model.gguf")
            if (legacy.exists()) return legacy
            return ggufFiles().firstOrNull() ?: legacy
        }

    /** Friendly label from a GGUF filename, e.g. "qwen2.5-3b-instruct-q4_k_m" → "Qwen2.5 3B". */
    private fun modelLabel(name: String): String {
        val b = name.removeSuffix(".gguf")
        val size = Regex("(\\d+(?:\\.\\d+)?)\\s*b", RegexOption.IGNORE_CASE).find(b)?.groupValues?.get(1)
        val family = when {
            b.contains("qwen", true) -> "Qwen"
            b.contains("llama", true) -> "Llama"
            b.contains("gemma", true) -> "Gemma"
            b.contains("phi", true) -> "Phi"
            else -> b.take(10)
        }
        return if (size != null) "$family ${size}B" else b
    }
    private val packsDir: File
        get() {
            val base = getApplication<Application>().getExternalFilesDir("packs")
                ?: File(getApplication<Application>().filesDir, "packs")
            base.mkdirs(); return base
        }
    private val workDir: File
        get() = File(getApplication<Application>().filesDir, "packwork").apply { mkdirs() }

    init {
        showOnboarding = !appPrefs.getBoolean("onboarded", false)
        // Open the chat store + load history HERE, on the main thread during init (a cheap
        // ~16ms SQLite read), NOT on the background bootstrap thread. A state write from a
        // raw background thread this early in cold-start doesn't reliably trigger Compose
        // recomposition (the drawer kept showing "No past chats yet" until an unrelated
        // state write happened), so the drawer missed the history until the first prompt.
        // Setting it in init means the very first composition already sees it.
        store = try { ChatStore(getApplication()).also { conversations = it.listConversations() } } catch (_: Exception) { null }
        Thread { bootstrap() }.start()
    }

    private fun bootstrap() {
        try {
            // First run: install the knowledge pack + embedder bundled in the APK so
            // Research works offline immediately (no download/hosting needed).
            com.fireplace.data.BundledAssets.installIfNeeded(getApplication())

            // Dense retrieval embedder (optional): <externalFiles>/embedder/{model.onnx,vocab.txt}.
            embedder = try { com.fireplace.rag.Embedder.load(getApplication<Application>().getExternalFilesDir(null)!!) } catch (_: Exception) { null }
            // Cross-encoder reranker (optional): <externalFiles>/reranker/{model.onnx,vocab.txt}.
            reranker = try { com.fireplace.rag.Reranker.load(getApplication<Application>().getExternalFilesDir(null)!!) } catch (_: Exception) { null }

            // A model alone is enough to chat; a knowledge pack is optional (it
            // just adds grounded, cited research). So we never block on "no packs".
            val packs = packsDir.listFiles { f -> f.extension == "fpack" }?.sortedBy { it.name } ?: emptyList()
            packInstalled = packs.isNotEmpty()
            // Open only the packs the user has enabled; the rest still appear in
            // the catalog (via manifest metadata) so they can be turned on later.
            for (p in packs) {
                val meta = try { PackReader.readMeta(p) } catch (e: Exception) { statusDetail = "pack ${p.name}: ${e.message}"; continue }
                packFiles[meta.id] = p
                if (!isEnabled(meta.id)) continue
                try { readers.add(PackReader.open(p, workDir)) }
                catch (e: Exception) { statusDetail = "pack ${p.name}: ${e.message}" }
            }
            retriever = Retriever(readers, embedder, reranker = reranker)
            refreshPackState()

            loadEngine()
            refreshModels()
            // Returning user who already onboarded but has no model (e.g. deleted it):
            // fetch the default so they're never stuck on a dead screen.
            if (!showOnboarding) maybeAutoDownloadDefault()
        } catch (e: EngineException) {
            status = Status.ERROR; statusDetail = "engine: ${e.message}"
            refreshModelCatalog()
        } catch (e: Exception) {
            status = Status.ERROR; statusDetail = e.message ?: "unknown error"
            refreshModelCatalog()
        }
    }

    /** (Re)load the selected model, sizing context + threads to the device. */
    private fun loadEngine() {
        val mf = modelFile
        if (!mf.exists()) { status = Status.NO_MODEL; statusDetail = ggufDir.absolutePath; return }
        val ramGb = deviceRamGb()
        val nCtx = when { ramGb < 6 -> 2048; ramGb < 10 -> 3072; else -> 4096 }
        // Leave a core for the UI thread so streaming stays smooth during inference
        // (otherwise a fully-pegged CPU freezes the UI on the "thinking" dots).
        val nThreads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)
        val modelMb = mf.length() / (1024 * 1024)
        activeModelMb = modelMb
        // Pin the weights in RAM when the model comfortably fits (≈ model×1.25 < half
        // of device RAM) so the OS can't evict its mmap pages and thrash decode from
        // flash. The big MoE never fits, so it streams (mlock=false).
        val mlock = modelMb * 1.25 < ramGb * 1024 * 0.5
        engine = LlamaEngine.load(mf.absolutePath, nCtx = nCtx, nThreads = nThreads, mlock = mlock)
        deviceInfo = "${modelLabel(mf.name)} · ${modelMb} MB · device RAM ${"%.1f".format(ramGb)} GB · ctx $nCtx · $nThreads threads" +
            (if (mlock) " · pinned" else "") +
            (if (modelMb > ramGb * 1024 * 0.6) "  ⚠ large for this device - may be slow" else "")
        status = Status.READY
        prewarmPrefix()
    }

    /** Pre-process the fixed prompt prefix (grounding system rule + the one-shot
     *  citation example) into the KV cache at load, so the user's FIRST query reuses
     *  it instead of paying to prefill ~290 tokens - cutting first-answer latency. */
    private fun prewarmPrefix() = Thread {
        val eng = engine ?: return@Thread
        try {
            val fam = Prompt.family(modelFile.name)
            // A minimal grounded prompt: its prefix (system + example + "Sources:")
            // is the shared prefix of every real research prompt.
            val warm = Prompt.build("warm up", listOf(Hit("w", "w", "Warm up", "warm up", 0.0)), family = fam)
            eng.generate(warm, maxTokens = 1) { !stopRequested && !generating }
        } catch (_: Exception) { /* best-effort; never blocks the UI */ }
    }.apply { isDaemon = true; start() }

    private fun refreshModels() {
        val sel = modelFile.name
        models = ggufFiles().map { ModelInfo(it.name, modelLabel(it.name), it.length(), it.name == sel) }
        refreshModelCatalog()
    }

    /** Switch the active model (fast vs. rich). Reloads the engine off-thread. */
    fun selectModel(fileName: String) {
        if (fileName == modelFile.name) return
        modelPrefs.edit().putString("model", fileName).apply()
        Thread {
            status = Status.LOADING
            try { engine?.close() } catch (_: Exception) {}
            engine = null
            try { loadEngine() } catch (e: Exception) { status = Status.ERROR; statusDetail = e.message ?: "model load failed" }
            refreshModels()
        }.start()
    }

    /** True on an Android emulator, where the software CPU runs LLM inference
     *  far slower than real hardware - we lighten the load so it stays usable. */
    private val isEmulator: Boolean by lazy {
        val fp = android.os.Build.FINGERPRINT ?: ""
        val model = android.os.Build.MODEL ?: ""
        fp.startsWith("generic") || fp.contains("emulator", true) || fp.contains("sdk_gphone", true) ||
            model.contains("sdk", true) || model.contains("emulator", true) ||
            (android.os.Build.PRODUCT ?: "").contains("sdk", true)
    }

    /** Physical RAM in GB, used to size model context/threads to the device. */
    private fun deviceRamGb(): Double {
        return try {
            val am = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        } catch (_: Exception) { 4.0 }
    }

    /** Size the retrieval context to the device. On-device prefill is the main
     *  latency cost, and it grows with the number of source characters, so a weaker
     *  phone gets a leaner context (fewer, shorter sources) to keep the model
     *  answering within the watchdog - a 12 GB flagship gets the full, rich context
     *  for longer answers. The instant extractive preview covers the wait either way. */
    private fun retrievalConfig(): com.fireplace.rag.RetrievalConfig {
        val ram = deviceRamGb()
        return when {
            // Low-RAM: lean context AND a lower dense cap, so the brute-force dense
            // scan over a big pack (tens of MB) doesn't churn the page cache and evict
            // the mmap'd model - which would make decode thrash from flash. Big packs
            // fall back to indexed BM25 (title-boosted), which stays memory-light.
            // <6 GB: keep the brute-force dense scan off a huge pack (it would churn the
            // page cache and evict the mmap'd model), but give the improved lexical search
            // more and longer passages than before so the answer is fuller.
            // Weak phones: keep the prompt SMALL so prefill (and thus time-to-first-token)
            // stays low. On-device prefill of the whole context dominates latency here: a
            // large context on a slow 0.5B meant 30-40 s before the model wrote anything.
            // Fewer, shorter sources + a smaller rerank pool keep the answer starting fast.
            ram < 6.0 -> com.fireplace.rag.RetrievalConfig(rerankM = 4, rerankPool = 8, budgetChars = 1200, maxDense = 150_000)
            // 6-8 GB: dense retrieval ON for the Wikipedia pack (611k passages) - the single
            // biggest grounding win - with a moderate context budget.
            ram < 8.0 -> com.fireplace.rag.RetrievalConfig(rerankM = 6, rerankPool = 12, budgetChars = 2200, maxDense = 1_000_000)
            // 12 GB flagship (bounty target): dense on, the richest context for full,
            // well-grounded research answers.
            else -> com.fireplace.rag.RetrievalConfig(rerankM = 8, rerankPool = 18, budgetChars = 3400, maxDense = 2_000_000)
        }
    }

    fun stop() { stopRequested = true; engine?.cancel() }

    // ---------- Agents ----------

    /** Switch the active specialized agent. Starts a fresh conversation and
     *  picks the agent's default mode (grounded agents default to Research). */
    fun selectAgent(a: Agent) {
        agent = a
        mode = if (a.groundByDefault) Mode.RESEARCH else Mode.CHAT
        newConversation()
    }

    // ---------- Chat history ----------

    private fun refreshConversations() { store?.let { conversations = it.listConversations() } }

    /** Begin a new, empty conversation (row is created lazily on first message). */
    fun newConversation() {
        currentConvId = null
        messages.clear()
    }

    /** Load a saved conversation into the chat view. If this conversation is the
     *  one currently generating, its last assistant row is shown as still-streaming
     *  so the "composing" indicator reappears and live token updates resume (they
     *  flow through [showAssistant], which is gated on [currentConvId]). */
    fun loadConversation(id: String) {
        val s = store ?: return
        Thread {
            val msgs = s.messages(id)
            val isGenerating = id == genConvId
            val rebuilt = msgs.mapIndexed { i, m ->
                val streaming = isGenerating && i == msgs.lastIndex && m.role == "assistant"
                ChatMessage(m.role, m.text, parseSources(m.sourcesJson), m.passRate, streaming)
            }
            conversations.firstOrNull { it.id == id }?.let { c -> agent = AgentCatalog.byId(c.agentId) }
            // Point the live-update gate at this chat BEFORE swapping the list, so an
            // in-flight token update lands on the freshly loaded messages.
            currentConvId = id
            messages.clear()
            messages.addAll(rebuilt)
        }.start()
    }

    fun deleteConversation(id: String) = Thread {
        store?.deleteConversation(id)
        if (currentConvId == id) newConversation()
        refreshConversations()
    }.start()

    private fun ensureConversation(firstQuestion: String): String? {
        val s = store ?: return null
        currentConvId?.let { return it }
        val title = firstQuestion.trim().replace(Regex("\\s+"), " ").take(48).ifBlank { "New chat" }
        val id = s.createConversation(agent.id, title)
        currentConvId = id
        refreshConversations()
        return id
    }

    private fun serializeSources(srcs: List<SourceRef>): String {
        val arr = JSONArray()
        srcs.forEach { arr.put(JSONObject().apply { put("n", it.n); put("title", it.title); put("docId", it.docId); put("text", it.text) }) }
        return arr.toString()
    }

    private fun parseSources(json: String): List<SourceRef> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SourceRef(o.optInt("n", i + 1), o.optString("title"), o.optString("docId"), o.optString("text"))
        }
    } catch (_: Exception) { emptyList() }

    private fun reloadPacks() {
        synchronized(readers) {
            readers.forEach { it.close() }; readers.clear(); packFiles.clear()
            val packs = packsDir.listFiles { f -> f.extension == "fpack" }?.sortedBy { it.name } ?: emptyList()
            for (p in packs) {
                val meta = try { PackReader.readMeta(p) } catch (_: Exception) { continue }
                packFiles[meta.id] = p
                if (!isEnabled(meta.id)) continue
                try { readers.add(PackReader.open(p, workDir)) } catch (_: Exception) {}
            }
            retriever = Retriever(readers, embedder, reranker = reranker)
            refreshPackState()
            if (status == Status.NO_PACKS && packFiles.isNotEmpty() && engine != null) status = Status.READY
        }
    }

    /** Build the full catalog: every installed .fpack, loaded or not. */
    private fun refreshPackState() {
        val loaded = readers.associateBy { it.packId }
        pods = readers.map { it.packId }
        packInfos = packFiles.keys.sorted().mapNotNull { id ->
            val f = packFiles[id] ?: return@mapNotNull null
            val rd = loaded[id]
            val meta = try { PackReader.readMeta(f) } catch (_: Exception) { return@mapNotNull null }
            PackInfo(
                id = id,
                passages = rd?.passageCount ?: 0,
                title = meta.title,
                description = meta.description,
                sizeBytes = meta.sizeBytes,
                enabled = isEnabled(id),
                loaded = rd != null,
            )
        }
    }

    /** Turn an installed pack on/off. Disabled packs stay installed but are not
     *  loaded into the retriever, so research answers only draw on enabled packs. */
    fun setPackEnabled(id: String, enabled: Boolean) = Thread {
        packPrefs.edit().putBoolean("enabled:$id", enabled).apply()
        reloadPacks()
        importMsg = if (enabled) "Enabled '$id'." else "Disabled '$id'."
    }.start()

    /** Remove an installed pack (deletes the .fpack and reloads). */
    fun deletePack(id: String) = Thread {
        synchronized(readers) {
            packFiles[id]?.delete()
            packPrefs.edit().remove("enabled:$id").apply()
            reloadPacks()
            importMsg = "Removed '$id'."
        }
    }.start()

    // The conversation currently being generated into (if any). Streamed text is
    // persisted to this conversation's DB row incrementally, and the live view is
    // only updated while the user is actually looking at it - so a generation keeps
    // running (and is never lost) when the user navigates away and comes back.
    @Volatile private var genConvId: String? = null
    private var lastPersistMs = 0L
    private var lastViewMs = 0L

    /** Replace the visible assistant row (the last assistant message) - but ONLY if
     *  the user is currently viewing the conversation [convId] that is generating.
     *  Safe to call from the generation thread; a no-op when viewing another chat. */
    private fun showAssistant(convId: String?, text: String, sources: List<SourceRef>, passRate: Double?, streaming: Boolean) {
        if (convId == null || currentConvId != convId) return
        val i = messages.indexOfLast { it.role == "assistant" }
        if (i >= 0) messages[i] = ChatMessage("assistant", text, sources, passRate, streaming)
    }

    /**
     * Deep research: a retrieval-informed outline → per-section drafting over ONE shared
     * numbered source pool → assembly (streamed). Returns (final answer, the global source
     * pool it cites). More thorough and better-grounded than a single pass because the
     * outline is grounded in what the packs contain and each section is written over the
     * real cited sources - see [DeepResearch]. Several passes; slower.
     */
    private fun runDeepResearch(
        question: String,
        broad: List<Hit>,
        scoped: List<PackReader>,
        agent: Agent,
        fam: String,
        gotFirstToken: java.util.concurrent.atomic.AtomicBoolean,
        convId: String?,
        asstRowId: String?,
    ): Pair<String, List<Hit>> {
        val eng = engine ?: return "" to broad
        gotFirstToken.set(true)   // deep mode narrates phases; there is no stall to catch

        fun gen(prompt: String, max: Int, onStream: ((String) -> Unit)? = null): String {
            val sb = StringBuilder()
            eng.generate(prompt, maxTokens = max) { piece ->
                sb.append(piece)
                onStream?.invoke(Prompt.stripThink(sb.toString()))
                !stopRequested && !Prompt.isLooping(sb.toString())
            }
            return Prompt.stripThink(sb.toString())
        }

        // Phase 1 - plan the sections. On a weaker phone (<6 GB) the small model can't
        // reliably produce the "HEADING | query" outline and the extra pass is slow, so
        // derive sections deterministically from the top real source topics instead
        // (grounded by construction). A capable phone uses the model to plan a better
        // outline, falling back to topics if it doesn't follow the format.
        genStatus = "Planning the research…"
        val k = DeepResearch.sectionCount(question)
        val sections = if (deviceRamGb() < 6.0) {
            DeepResearch.sectionsFromTitles(broad.map { it.title }, k, question)
        } else {
            val outlineText = gen(DeepResearch.outline(question, broad.map { it.title }, k, fam), 160)
            if (stopRequested) return "" to broad
            DeepResearch.parseOutline(outlineText, question, k)
                .ifEmpty { DeepResearch.sectionsFromTitles(broad.map { it.title }, k, question) }
        }

        // Phase 2 - per-section retrieval into one global numbered pool (dedup + cap).
        val pool = LinkedHashMap<String, Hit>()
        broad.take(3).forEach { pool[it.passageId] = it }   // seed with the strongest broad hits
        val cfg = retrievalConfig()
        for ((idx, sec) in sections.withIndex()) {
            if (stopRequested) break
            genStatus = "Researching ${idx + 1}/${sections.size} · ${sec.first}"
            val secHits = try { Retriever(scoped, embedder, cfg, reranker).retrieve(sec.second) } catch (_: Exception) { emptyList() }
            for (h in secHits) { if (pool.size >= 10) break; pool.putIfAbsent(h.passageId, h) }
        }
        val poolHits = pool.values.toList()
        val poolSrcs = poolHits.mapIndexed { i, h -> SourceRef(i + 1, h.title, h.docId, h.text) }
        val poolSrcsJson = serializeSources(poolSrcs)
        // Shared numbered source block - identical across sections so the engine's KV
        // prefix cache is reused and each section only re-prefills its heading.
        val sourceBlock = buildString {
            poolHits.forEachIndexed { i, h ->
                append("[").append(i + 1).append("] (").append(h.title).append(") ").append(Prompt.neutralize(h.text)).append("\n")
            }
        }

        // Phase 3 - draft each section over the real numbered sources; show it build.
        val drafts = ArrayList<Pair<String, String>>()
        for ((idx, sec) in sections.withIndex()) {
            if (stopRequested) break
            genStatus = "Writing ${idx + 1}/${sections.size} · ${sec.first}"
            val d = gen(DeepResearch.section(sourceBlock, sec.first, agent.persona, agent.safetyLine, fam), 320)
            drafts.add(sec.first to d)
            val building = drafts.joinToString("\n\n") { (h, t) -> "## $h\n$t" }
            showAssistant(convId, building, poolSrcs, null, streaming = true)
            asstRowId?.let { store?.updateMessage(it, building, poolSrcsJson) }
        }
        if (stopRequested || drafts.isEmpty()) {
            return drafts.joinToString("\n\n") { (h, t) -> "## $h\n$t" } to poolHits
        }

        // Phase 4 - assemble into one coherent answer (streamed = what the user reads).
        genStatus = "Composing the final answer…"
        val cap = if (deviceRamGb() < 6.0) 560 else 900
        lastViewMs = 0L; lastPersistMs = 0L
        val finalText = gen(DeepResearch.assembly(question, drafts, agent.persona, fam), cap) { partial ->
            val now = System.currentTimeMillis()
            if (now - lastViewMs > 120) { lastViewMs = now; showAssistant(convId, partial, poolSrcs, null, streaming = true) }
            if (now - lastPersistMs > 600) { lastPersistMs = now; asstRowId?.let { store?.updateMessage(it, partial, poolSrcsJson) } }
        }
        return (finalText.ifBlank { drafts.joinToString("\n\n") { (h, t) -> "## $h\n$t" } }) to poolHits
    }

    fun send(question: String) {
        val eng = engine ?: return
        if (generating || question.isBlank()) return
        // Show the turn instantly so the UI feels responsive; the slow work runs below.
        messages.add(ChatMessage("user", question))
        messages.add(ChatMessage("assistant", "", streaming = true))
        generating = true; stopRequested = false

        // Auto-ground factual questions even in Chat mode. A small model answering a lookup
        // ("who was president in 2010") from memory produces confident fake facts; if the
        // question looks factual and packs are installed, retrieve and cite instead.
        val havePacks = synchronized(readers) { readers.isNotEmpty() }
        val useResearch = mode == Mode.RESEARCH || (havePacks && com.fireplace.rag.Router.looksFactual(question))
        genStatus = if (useResearch) "Searching your packs…" else "Thinking…"
        val activeAgent = agent
        Thread {
            // Abort any in-flight KV prewarm so it can never block this query on the
            // engine lock. cancel() is atomic (no lock); the prewarm's prefill sees it
            // and returns. generate() below resets the flag at its start.
            eng.cancel()
            // Create/locate the conversation up front so streamed text can be persisted
            // incrementally - the generation then survives navigating away and back.
            val convId = ensureConversation(question)
            genConvId = convId
            store?.addMessage(convId ?: "", "user", question)
            val asstRowId = convId?.let { store?.addMessage(it, "assistant", "") }
            lastPersistMs = 0L
            // Watchdog: never let a generation hang. cancel() interrupts prefill
            // AND decode (JNI checks the flag each step). Scale the budget to the
            // model size so a big model gets more time than a small one.
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            val gotFirstToken = java.util.concurrent.atomic.AtomicBoolean(false)
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            val startMs = System.currentTimeMillis()
            // Generous: prompt prefill legitimately takes ~1 min on a slow
            // software-CPU device before the first token. These only catch a
            // genuine hang; a real phone finishes long before either fires.
            // Deep research runs several passes over a few minutes and narrates each
            // phase, so there's no "stall" to catch; give it a long total budget.
            val deep = useResearch && deepResearch
            // Output-token cap, computed up front because the watchdog timeout is derived
            // from it: a full answer at this cap MUST be able to finish before the timeout,
            // or generation gets cancelled mid-stream (the "stuck, half-written" bug).
            val lowRam = deviceRamGb() < 6.0
            val cap = when {
                isEmulator -> if (useResearch) 320 else 192
                // Shorter cap on a slow 6 GB phone so a complete answer finishes in a few
                // minutes rather than grinding to the watchdog; bigger devices get more.
                lowRam -> if (useResearch) 360 else 240
                else -> if (useResearch) 900 else 448
            }
            // Watchdog budgets. stallMs: give up only if NO first token ever arrives (prefill
            // genuinely hung). totalMs: scaled to the cap and a conservative per-token time so
            // a full answer is never cut off, plus headroom for retrieval + prefill.
            // Measured on a throttled 6 GB phone: ~1 tok/s decode (weights can't stay hot
            // in RAM), so budget ~1100 ms/token there, or the watchdog cuts a real answer
            // off mid-stream. A 12 GB device is several times faster.
            val perTokMs = if (isEmulator) 1700L else if (lowRam) 1100L else 260L
            val stallMs = if (deep) Long.MAX_VALUE else 130_000L
            val totalMs = if (deep) 600_000L else (90_000L + cap * perTokMs).coerceIn(180_000L, 480_000L)
            val watchdog = Thread {
                while (!done.get()) {
                    val el = System.currentTimeMillis() - startMs
                    if ((!gotFirstToken.get() && el > stallMs) || el > totalMs) { timedOut.set(true); eng.cancel(); break }
                    try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
                }
            }.apply { isDaemon = true; start() }
            try {
                // Scope retrieval to the active agent's packs so a focused pack (crypto,
                // travel, first-aid, arXiv) isn't drowned out by the big Wikipedia pack.
                // If the agent's scope matches nothing installed, fall back to all packs.
                val scoped = if (useResearch) synchronized(readers) {
                    val inScope = readers.filter { activeAgent.matchesScope(it.packId) }
                    if (inScope.isNotEmpty()) inScope else readers.toList()
                } else emptyList()
                val tRet = System.currentTimeMillis()
                val hits: List<Hit> = if (useResearch) Retriever(scoped, embedder, retrievalConfig(), reranker).retrieve(question) else emptyList()
                if (useResearch) android.util.Log.i("fireplace-perf", "retrieve_ms=${System.currentTimeMillis() - tRet} hits=${hits.size} dense=${embedder != null}")
                // Abstain instead of hallucinate: if a factual question matched nothing in
                // the installed packs, say so plainly rather than letting the model answer
                // from memory with ungrounded (often wrong) facts. Deep research does its
                // own per-section retrieval, so don't short-circuit it here.
                if (useResearch && !deep && hits.isEmpty()) {
                    val msg = "I don't have anything about that in your installed packs, so I won't guess. " +
                        "Try enabling more packs (open Packs), switching to a broader one, or rephrasing the question."
                    showAssistant(convId, msg, emptyList(), null, streaming = false)
                    asstRowId?.let { store?.updateMessage(it, msg) }
                    refreshConversations()
                    return@Thread
                }
                if (useResearch) genStatus = "Reading ${hits.size} source${if (hits.size == 1) "" else "s"}…"
                // Sources + an INSTANT extractive answer, shown the moment retrieval
                // finishes (~2s) so the user reads grounded, cited content while the
                // model prefills behind it. The stream replaces it on the first token;
                // it also stands as the fallback if generation times out.
                val srcs = if (useResearch) hits.mapIndexed { i, h -> SourceRef(i + 1, h.title, h.docId, h.text) } else emptyList()
                val srcsJson = if (useResearch) serializeSources(srcs) else "[]"
                val preview = if (useResearch) Prompt.extractivePreview(question, hits) else ""
                android.util.Log.i("fireplace-perf", "preview_len=${preview.length} srcs=${srcs.size} useResearch=$useResearch cap=$cap")
                if (useResearch) {
                    // Instant grounded, cited answer while the model prefills behind it.
                    showAssistant(convId, preview, srcs, null, streaming = true)
                    asstRowId?.let { store?.updateMessage(it, preview, srcsJson) }
                    genStatus = "Composing a fuller answer…"
                }
                val fam = Prompt.family(modelFile.name)   // Gemma vs Qwen use different chat templates
                val prompt = if (useResearch)
                    Prompt.build(question, hits, activeAgent.persona, activeAgent.safetyLine, fam)
                else
                    Prompt.buildChat(question, persona = activeAgent.persona, safety = activeAgent.safetyLine, family = fam)
                val sb = StringBuilder()
                if (!useResearch) genStatus = "Composing the answer…"
                // `cap` (output tokens) and its matching watchdog budget were computed up
                // front, above, so a full answer finishes before the timeout.
                lastViewMs = 0L
                // Which sources the final answer is grounded in (the deep pipeline builds
                // its own global pool, so these can be reassigned below).
                var usedHits = hits
                var usedSrcs = srcs
                var usedSrcsJson = srcsJson
                var answer: String
                if (deep) {
                    val (ft, pool) = runDeepResearch(question, hits, scoped, activeAgent, fam, gotFirstToken, convId, asstRowId)
                    answer = ft
                    if (pool.isNotEmpty()) {
                        usedHits = pool
                        usedSrcs = pool.mapIndexed { i, h -> SourceRef(i + 1, h.title, h.docId, h.text) }
                        usedSrcsJson = serializeSources(usedSrcs)
                    }
                } else {
                    val decStart = System.currentTimeMillis()
                    var decodeN = 0
                    eng.generate(prompt, maxTokens = cap) { piece ->
                        val firstToken = piece.isNotEmpty() && !gotFirstToken.get()
                        if (firstToken) { gotFirstToken.set(true); genStatus = "Writing the answer…" }
                        if (piece.isNotEmpty()) decodeN++
                        sb.append(piece)
                        val nowMs = System.currentTimeMillis()
                        // Refresh the on-screen text at ~8 fps, not every token: recomposing
                        // the growing answer on each token steals CPU from the decode threads
                        // and slows generation. The final text is rendered after the loop.
                        if (firstToken || nowMs - lastViewMs > 120) {
                            lastViewMs = nowMs
                            showAssistant(convId, Prompt.stripThink(sb.toString()), srcs, null, streaming = true)
                        }
                        // Persist the growing answer to the DB periodically, so navigating
                        // away and back (which reloads from the DB) shows the work in progress
                        // and never loses it.
                        if (nowMs - lastPersistMs > 600) {
                            lastPersistMs = nowMs
                            asstRowId?.let { store?.updateMessage(it, Prompt.stripThink(sb.toString()), srcsJson) }
                        }
                        // Stop early if a small model starts looping (saves the budget).
                        !stopRequested && !Prompt.isLooping(sb.toString())
                    }
                    answer = Prompt.tidy(sb.toString())
                    val decMs = System.currentTimeMillis() - decStart
                    android.util.Log.i("fireplace-perf",
                        "decode pieces=$decodeN raw=${sb.length} answer=${answer.length} preview=${preview.length} " +
                        "decode_ms=$decMs timedOut=${timedOut.get()} stop=$stopRequested head=[${sb.toString().replace("\n"," ").take(90)}]")
                }
                done.set(true)
                // Resolve the final assistant text + grounding, write it to the DB once,
                // and reflect it in the view only if the user is still on this chat.
                val finalText: String
                val finalRate: Double?
                if (answer.isBlank() && useResearch && preview.isNotBlank()) {
                    // Generation gave nothing (e.g. timed out) - keep the instant
                    // extractive answer we already showed; it's grounded and cited.
                    val note = "_From the sources directly (the model was slow to add to this)._\n\n"
                    finalText = note + preview
                    finalRate = Citations.verify(preview, usedHits).passRate
                } else if (answer.isBlank()) {
                    finalText = if (timedOut.get())
                        "⏱️ That took too long on this device. Try a shorter question, or switch to a smaller model in Packs ▸ Model."
                    else "I couldn't find an answer for that. Try rephrasing, or check that the right packs are enabled."
                    finalRate = null
                } else if (useResearch) {
                    // Attach inline [n] citations the small model forgot, so every claim
                    // shows its source and the answer verifies as grounded.
                    val attributed = Citations.attribute(answer, usedHits)
                    val report: CitationReport = Citations.verify(attributed, usedHits)
                    // Make uncertainty visible: flag unsupported cited claims + a caution
                    // banner when grounding is weak (the trustworthy-grounding edge).
                    var shown = Citations.annotate(attributed, report)
                    // Only note "stopped early" if the answer really is short (truly cut
                    // off) - not when a full answer merely took a while on a slow device.
                    if (timedOut.get() && attributed.length < 400)
                        shown += "\n\n_(stopped early - this device was slow; a smaller model answers faster.)_"
                    finalText = shown
                    finalRate = report.passRate
                } else {
                    finalText = answer
                    finalRate = null
                }
                showAssistant(convId, finalText, usedSrcs, finalRate, streaming = false)
                asstRowId?.let { store?.updateMessage(it, finalText, usedSrcsJson, finalRate) }
                refreshConversations()
            } catch (e: Exception) {
                val err = "⚠️ ${e.message}"
                showAssistant(convId, err, emptyList(), null, streaming = false)
                asstRowId?.let { store?.updateMessage(it, err) }
            } finally {
                done.set(true)
                watchdog.interrupt()
                generating = false
                genStatus = ""
                genConvId = null
            }
        }.start()
    }

    override fun onCleared() {
        engine?.close()
        readers.forEach { it.close() }
        embedder?.close(); reranker?.close()
        store?.close()
    }
}
