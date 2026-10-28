package com.fireplace.data

/**
 * The downloadable catalog shown at first run: models (from their public
 * HuggingFace GGUF repos) and the knowledge pack + embedder (hosted by us).
 *
 * Downloads are fetched by [Downloader] at the user's explicit tap. INTERNET is
 * used ONLY for this one-time setup; inference and data import never touch the
 * network, so after setup the app runs entirely offline (verify in airplane mode).
 */
enum class DownloadKind { MODEL, PACK, EMBEDDER }

/** One file to fetch: a URL and where it lands under the app's files dir. */
data class DownloadFile(val url: String, val subdir: String, val name: String, val sizeBytes: Long)

data class DownloadItem(
    val id: String,
    val name: String,
    val desc: String,
    val minRamGb: Double,          // recommended device RAM
    val kind: DownloadKind,
    val files: List<DownloadFile>,
    val recommended: Boolean = false,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    val sizeLabel: String get() = "%.1f GB".format(totalBytes / 1_073_741_824.0).let {
        if (totalBytes < 1_073_741_824L) "%.0f MB".format(totalBytes / 1_048_576.0) else it
    }
}

object Catalog {
    private const val MB = 1_048_576L

    // ---- Models: public HuggingFace GGUF files (range-request friendly) ------
    val models: List<DownloadItem> = listOf(
        DownloadItem(
            "qwen-0.5b", "Qwen2.5 0.5B", "Fast and light - runs well on any phone. Good for quick chat.",
            minRamGb = 2.0, kind = DownloadKind.MODEL,
            files = listOf(DownloadFile(
                "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                "", "qwen2.5-0.5b-instruct.gguf", 468 * MB)),
        ),
        DownloadItem(
            "qwen-1.5b", "Qwen2.5 1.5B", "Fast, accurate and well-cited - the best balance in our tests. Recommended.",
            minRamGb = 4.0, kind = DownloadKind.MODEL, recommended = true,
            files = listOf(DownloadFile(
                "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                "", "qwen2.5-1.5b-instruct.gguf", 1065 * MB)),
        ),
        DownloadItem(
            "gemma-2b", "Gemma 2 2B", "Highest grounding and citation precision - a bit slower, needs more RAM.",
            minRamGb = 5.0, kind = DownloadKind.MODEL,
            files = listOf(DownloadFile(
                "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
                "", "gemma-2-2b-it.gguf", 1629 * MB)),
        ),
        DownloadItem(
            "qwen-3b", "Qwen2.5 3B", "Rich answers; grounds well but slower and larger.",
            minRamGb = 6.0, kind = DownloadKind.MODEL,
            files = listOf(DownloadFile(
                "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
                "", "qwen2.5-3b-instruct.gguf", 2007 * MB)),
        ),
        DownloadItem(
            "qwen-30b", "Qwen3 30B-A3B", "Frontier quality (a 30B mixture-of-experts). Needs a 12 GB phone.",
            minRamGb = 12.0, kind = DownloadKind.MODEL,
            files = listOf(DownloadFile(
                "https://huggingface.co/unsloth/Qwen3-30B-A3B-GGUF/resolve/main/Qwen3-30B-A3B-UD-Q2_K_XL.gguf",
                "", "qwen3-30b-a3b.gguf", 11266 * MB)),
        ),
    )

    // ---- Full knowledge pack + embedder: hosted on a HuggingFace dataset ------
    // The app bundles a ~21k-article Wikipedia starter; this is the optional upgrade
    // to the full 611k-passage corpus. To enable it:
    //   1. cd corpus && python sign_pack.py ../out/wikipedia-clean.fpack   (already signed)
    //   2. huggingface-cli login  (a WRITE token)
    //   3. python upload_packs.py --repo <you>/fireplace-packs
    //   4. set HOST below to the dataset's resolve URL it prints, then rebuild.
    // Until then HOST stays REPLACE_ME → hostConfigured=false → the upgrade download is
    // hidden and the bundled packs work offline out of the box.
    private const val HOST = "https://huggingface.co/datasets/REPLACE_ME/fireplace-packs/resolve/main"

    val pack = DownloadItem(
        "wikipedia", "Full Wikipedia pack",
        "The complete 146,000-article corpus (611k passages) - a deeper upgrade over the bundled starter.",
        minRamGb = 0.0, kind = DownloadKind.PACK, recommended = true,
        // Exact size of out/wikipedia-clean.fpack (signed). Per-file SHA-256 in the pack
        // manifest + the Ed25519 publisher signature are verified on download.
        files = listOf(DownloadFile("$HOST/wikipedia.fpack", "packs", "wikipedia.fpack", 359_965_344L)),
    )

    val embedder = DownloadItem(
        "embedder", "Semantic search add-on", "Lets agents search your imported data pods by meaning.",
        minRamGb = 0.0, kind = DownloadKind.EMBEDDER,
        files = listOf(
            DownloadFile("$HOST/embedder/model.onnx", "embedder", "model.onnx", 22 * MB),
            DownloadFile("$HOST/embedder/vocab.txt", "embedder", "vocab.txt", 1 * MB),
        ),
    )

    /** True until the pack/embedder host has been configured. */
    val hostConfigured: Boolean get() = !HOST.contains("REPLACE_ME")
}
