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
            if (f.isFile && f.length() > MIN_BYTES) return f
        }
        return listModelFiles().firstOrNull { it.length() > MIN_BYTES }
    }

    fun status(): LocalModelStatus {
        val file = findInstalled()
        return if (file != null) {
            LocalModelStatus(
                ready = true,
                path = file.absolutePath,
                displayName = file.name,
                sizeBytes = file.length(),
                message = "Local model ready: ${file.name}"
            )
        } else {
            LocalModelStatus(
                ready = false,
                path = null,
                displayName = null,
                sizeBytes = 0L,
                message = "No local model. Install Gemma 3 1B .task for iQOO 15."
            )
        }
    }

    fun importFromUri(context: Context, uri: Uri, targetName: String? = null): File {
        val name = targetName?.takeIf { it.isNotBlank() }
            ?: queryDisplayName(context, uri)
            ?: "model.task"
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(root, if (isModelFile(safe)) safe else "$safe.task")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        } ?: error("Could not open selected file")
        require(out.length() > MIN_BYTES) { "Model file too small / empty" }
        return out
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
            return n.endsWith(".task") || n.endsWith(".bin") || n.endsWith(".tflite")
        }
    }
}

data class LocalModelStatus(
    val ready: Boolean,
    val path: String?,
    val displayName: String?,
    val sizeBytes: Long,
    val message: String
)
