package com.aquascope.smriti.llm

/**
 * Recommended on-device models for SMRITI Ask (iQOO 15 / Snapdragon 8 Elite class).
 * Models are not bundled. Install via in-app download, file picker, or adb.
 */
object LocalModelCatalog {

    data class Entry(
        val id: String,
        val displayName: String,
        val fileName: String,
        val approxRamGb: Double,
        val notes: String,
        val downloadHint: String,
        val downloadUrl: String? = null,
        val licenseUrl: String? = null,
        val sizeBytes: Long = 0L,
        val requiresAccessToken: Boolean = false
    ) {
        val canDownload: Boolean get() = !downloadUrl.isNullOrBlank()
    }

    /** Prefer this for 12 GB iQOO 15. Gated — needs Hugging Face license + token. */
    val gemma3_1b = Entry(
        id = "gemma3-1b-it-int4",
        displayName = "Gemma 3 1B (INT4)",
        fileName = "gemma3-1b-it-int4.task",
        approxRamGb = 1.5,
        notes = "Best default for Ask SMRITI — light, grounded rephrase only.",
        downloadHint = "Hugging Face: litert-community / Gemma3-1B-IT INT4 (.task)",
        downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true",
        licenseUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT",
        sizeBytes = 554_661_246L,
        requiresAccessToken = true
    )

    /** Comfortable on 16 GB iQOO 15. Import if you already have the GPU INT4 build. */
    val gemma2_2b = Entry(
        id = "gemma2-2b-it-gpu-int4",
        displayName = "Gemma 2 2B (GPU INT4)",
        fileName = "gemma-2-2b-it-gpu-int4.bin",
        approxRamGb = 2.5,
        notes = "Stronger phrasing; still keep retrieval as source of truth.",
        downloadHint = "MediaPipe / Kaggle Gemma-2 2B IT GPU INT4 — import the file"
    )

    /** Public MediaPipe .task — works without a Hugging Face token. */
    val qwen25_15b = Entry(
        id = "qwen25-15b-instruct-q8",
        displayName = "Qwen 2.5 1.5B (INT8)",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        approxRamGb = 2.5,
        notes = "No Hugging Face login required. Larger download than Gemma 3 1B.",
        downloadHint = "Hugging Face: litert-community / Qwen2.5-1.5B-Instruct q8 (.task)",
        downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task?download=true",
        licenseUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct",
        sizeBytes = 1_597_913_616L,
        requiresAccessToken = false
    )

    val recommended = listOf(gemma3_1b, qwen25_15b, gemma2_2b)

    val downloadable: List<Entry> = recommended.filter { it.canDownload }

    const val HF_TOKEN_URL = "https://huggingface.co/settings/tokens"

    fun preferredFileNames(): List<String> = listOf(
        gemma3_1b.fileName,
        "gemma-3-1B-it-int4.task",
        "Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
        qwen25_15b.fileName,
        gemma2_2b.fileName,
        "model.task",
        "gemma.task",
        "llm.task"
    )
}
