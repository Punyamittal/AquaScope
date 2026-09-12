package com.aquascope.smriti.llm

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * On-device model files under filesDir/smriti_models/.
 * Downloads are user-initiated only — app launch never fetches a model.
 */
class LocalModelStore(context: Context) {

    private val root = File(context.filesDir, "smriti_models").also { it.mkdirs() }

    fun rootDir(): File = root

    fun fileFor(name: String): File = File(root, name)

    fun stagingFile(name: String): File = File(root, "$name.part")

    fun isPresent(name: String): Boolean {
        val f = fileFor(name)
        return f.isFile && f.length() > MIN_BYTES
    }

    fun usableSpace(): Long = root.usableSpace

    fun promoteStaging(name: String): File {
        val staging = stagingFile(name)
        require(staging.isFile && staging.length() > MIN_BYTES) { "Incomplete download" }
        val dest = fileFor(name)
        if (dest.exists()) dest.delete()
        if (!staging.renameTo(dest)) {
            staging.copyTo(dest, overwrite = true)
            staging.delete()
        }
        require(dest.isFile && dest.length() > MIN_BYTES) { "Could not install downloaded model" }
        return dest
    }

    fun listModelFiles(): List<File> =
        root.listFiles()
            ?.filter { it.isFile && isModelFile(it.name) }
            ?.sortedByDescending { it.length() }
            .orEmpty()

    fun findInstalled(): File? {
        val preferred = LocalModelCatalog.preferredFileNames()
        for (name in preferred) {
            val f = File(root, name)
            if (f.isFile && f.length() > MIN_BYTES && isLoadableMediaPipe(name)) return f
        }
        val files = listModelFiles().filter { it.length() > MIN_BYTES && isLoadableMediaPipe(it.name) }
        return files.firstOrNull { isPreferredTask(it.name) } ?: files.firstOrNull()
    }

    fun status(): LocalModelStatus {
        val file = findInstalled()
        if (file != null) {
            return LocalModelStatus(
                ready = true,
                path = file.absolutePath,
                displayName = file.name,
                sizeBytes = file.length(),
                message = "Local model file found: ${file.name}"
            )
        }
        val gallery = listModelFiles().firstOrNull {
            it.length() > MIN_BYTES && it.name.endsWith(".litertlm", ignoreCase = true)
        }
        return if (gallery != null) {
            LocalModelStatus(
                ready = false,
                path = gallery.absolutePath,
                displayName = gallery.name,
                sizeBytes = gallery.length(),
                message = GALLERY_NPU_MESSAGE
            )
        } else {
            LocalModelStatus(
                ready = false,
                path = null,
                displayName = null,
                sizeBytes = 0L,
                message = MISSING_TASK_MESSAGE
            )
        }
    }

    fun importFromUri(context: Context, uri: Uri, targetName: String? = null): File {
        val name = targetName?.takeIf { it.isNotBlank() }
            ?: queryDisplayName(context, uri)
            ?: "model.task"
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(root, if (isModelFile(safe)) safe else "$safe.task")
        val tmp = File(root, ".import.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output) }
        } ?: error("Could not open selected file")
        try {
            validateCopied(tmp, safe)
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return out
    }

    fun importFromFile(src: File): File {
        require(src.isFile) { "Missing file ${src.name}" }
        validateCopied(src, src.name)
        val dest = File(root, if (isModelFile(src.name)) src.name else "${src.name}.task")
        src.copyTo(dest, overwrite = true)
        require(dest.length() > MIN_BYTES) { "Copy of ${src.name} was empty" }
        return dest
    }

    /** Copies a Gemma/Qwen bundle already sitting in public Downloads. */
    fun importFromPublicDownloads(): File {
        val found = findInPublicDownloads()
            ?: error(
                "No Gemma .task in Downloads. Edge Gallery keeps models inside its own app — " +
                    "share/export the file to Downloads, or use Import and pick gemma3-1b-it-int4.task."
            )
        return importFromFile(found)
    }

    fun findInPublicDownloads(): File? {
        val dirs = listOf(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            File("/storage/emulated/0/Download"),
            File("/sdcard/Download")
        )
        val preferred = LocalModelCatalog.preferredFileNames()
        val seen = HashSet<String>()
        val candidates = mutableListOf<File>()
        for (dir in dirs) {
            val key = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
            if (!seen.add(key) || !dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile || f.length() <= MIN_BYTES) continue
                if (preferred.contains(f.name) ||
                    (looksLikeGemmaBundle(f.name) && isLoadableMediaPipe(f.name))
                ) {
                    candidates += f
                }
            }
        }
        return candidates.firstOrNull { isPreferredTask(it.name) } ?: candidates.firstOrNull()
    }

    private fun validateCopied(file: File, displayName: String) {
        require(file.isFile && file.length() > MIN_BYTES) {
            "Model file too small. Gemma 3 1B is about 530 MB. Edge Gallery's in-app download is not visible to this app."
        }
        val header = ByteArray(24)
        val n = file.inputStream().use { it.read(header) }
        if (n > 0 && LocalModelDownloadPolicy.looksLikeHtml(header.copyOf(n))) {
            error("That file is a web page, not a model. Import the .task binary, not a Hugging Face HTML page.")
        }
        if (displayName.lowercase().endsWith(".litertlm")) {
            // Allowed on disk; MediaPipe may still reject NPU Gallery bundles.
        }
    }

    fun deleteAll() {
        listModelFiles().forEach { it.delete() }
        root.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment
    }

    companion object {
        const val MIN_BYTES = 1_000_000L // >1 MB sanity

        fun isModelFile(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".task") || n.endsWith(".bin") ||
                n.endsWith(".tflite") || n.endsWith(".litertlm")
        }

        fun isPreferredTask(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".task") || n.endsWith(".bin")
        }

        fun looksLikeGemmaBundle(name: String): Boolean {
            val n = name.lowercase()
            if (!isModelFile(n)) return false
            return n.contains("gemma") || n.contains("qwen") ||
                n == "model.task" || n == "llm.task"
        }

        fun isLoadableMediaPipe(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".task") || n.endsWith(".bin") || n.endsWith(".tflite")
        }

        const val GALLERY_NPU_MESSAGE =
            "Found an Edge Gallery NPU file (.litertlm). This app cannot use Gallery's copy. " +
                "Download Gemma 3 1B INT4 (.task) on this System screen, or Import gemma3-1b-it-int4.task."

        const val MISSING_TASK_MESSAGE =
            "No MediaPipe .task in this app. Downloading Gemma in Edge Gallery does not install it here — " +
                "Gallery keeps models in its own sandbox. Download Gemma 3 1B on this screen."
    }
}

data class LocalModelStatus(
    val ready: Boolean,
    val path: String?,
    val displayName: String?,
    val sizeBytes: Long,
    val message: String
)
