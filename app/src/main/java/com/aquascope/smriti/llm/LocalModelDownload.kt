package com.aquascope.smriti.llm

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    object Idle : DownloadState()
    data class Running(
        val entryId: String,
        val bytes: Long,
        val total: Long
    ) : DownloadState()
    data class Succeeded(val entryId: String, val fileName: String) : DownloadState()
    data class Failed(
        val entryId: String,
        val message: String,
        val needsToken: Boolean
    ) : DownloadState()
}

object LocalModelDownloadPolicy {
    private val HF_TOKEN = Regex("hf_[A-Za-z0-9]{8,}")

    fun normalizeToken(raw: String?): String? = extractHfToken(raw)

    fun extractHfToken(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val stripped = trimmed.removePrefix("Bearer ").trim()
        val match = HF_TOKEN.find(stripped)
        if (match != null) return match.value
        return stripped.ifEmpty { null }
    }

    fun maskToken(token: String): String {
        val t = token.trim()
        if (t.isBlank()) return ""
        val tail = t.takeLast(4)
        return if (t.length <= 8) "••••$tail" else "${t.take(3)}••••$tail"
    }

    fun needsAccessToken(httpCode: Int): Boolean = httpCode == 401 || httpCode == 403

    fun isHtmlContentType(contentType: String?): Boolean {
        val type = contentType?.lowercase(Locale.US) ?: return false
        return type.contains("text/html") || type.contains("application/xhtml")
    }

    fun looksLikeHtml(firstBytes: ByteArray): Boolean {
        val text = firstBytes.decodeToString().trimStart().lowercase(Locale.US)
        return text.startsWith("<!doctype") || text.startsWith("<html")
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        return if (mb < 1024) String.format(Locale.US, "%.1f MB", mb)
        else String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun isCompleteEnough(actualBytes: Long, expectedBytes: Long): Boolean {
        if (actualBytes < LocalModelStore.MIN_BYTES) return false
        if (expectedBytes <= 0L) return true
        return actualBytes >= expectedBytes * 8 / 10
    }

    /** Bearer belongs only on huggingface.co — CDN/xethub hosts reject it with 403. */
    fun shouldAttachHfToken(host: String): Boolean {
        val h = host.lowercase(Locale.US)
        return h == "huggingface.co" || h == "www.huggingface.co"
    }
}

/**
 * User-initiated model download into filesDir/smriti_models/.
 * App launch never starts a download.
 */
class LocalModelDownloader(
    context: Context,
    private val store: LocalModelStore
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val next = if (LocalModelDownloadPolicy.shouldAttachHfToken(req.url.host)) {
                req
            } else {
                req.newBuilder().removeHeader("Authorization").build()
            }
            chain.proceed(next)
        }
        .build()

    @Volatile private var job: Job? = null
    @Volatile private var call: Call? = null

    fun isBusy(): Boolean = job?.isActive == true

    fun start(entry: LocalModelCatalog.Entry, accessToken: String?): String? {
        if (isBusy()) return "Download already running"
        val url = entry.downloadUrl ?: run {
            _state.value = DownloadState.Failed(entry.id, "No download URL for this model.", false)
            return "No download URL for this model."
        }
        val token = LocalModelDownloadPolicy.extractHfToken(accessToken)
        if (entry.requiresAccessToken && token.isNullOrBlank()) {
            return "Paste a Hugging Face token, tap Save token, then Download."
        }
        job = scope.launch {
            val wakeLock = acquireWakeLock()
            ModelDownloadService.start(appContext, "Downloading ${entry.displayName}")
            try {
                download(entry, url, token)
            } catch (c: CancellationException) {
                call?.cancel()
                _state.value = DownloadState.Idle
                throw c
            } catch (t: Throwable) {
                if (call?.isCanceled() == true) {
                    _state.value = DownloadState.Idle
                } else {
                    Log.w(TAG, "Download failed", t)
                    _state.value = DownloadState.Failed(
                        entry.id,
                        t.message ?: "Download failed",
                        t is AccessTokenRequiredException
                    )
                }
            } finally {
                call = null
                ModelDownloadService.stop(appContext)
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
        return null
    }

    fun cancel() {
        call?.cancel()
        job?.cancel()
    }

    fun consumeTerminal() {
        when (_state.value) {
            is DownloadState.Succeeded, is DownloadState.Failed -> _state.value = DownloadState.Idle
            else -> Unit
        }
    }

    private suspend fun download(
        entry: LocalModelCatalog.Entry,
        url: String,
        token: String?
    ) {
        val staging = store.stagingFile(entry.fileName)
        val existing = staging.length()
        val remaining = if (entry.sizeBytes > 0) (entry.sizeBytes - existing).coerceAtLeast(0) else entry.sizeBytes
        val need = if (remaining > 0) remaining else LocalModelStore.MIN_BYTES
        if (store.usableSpace() < need + 8_000_000L) {
            _state.value = DownloadState.Failed(entry.id, "Not enough storage for this model.", false)
            return
        }

        _state.value = DownloadState.Running(entry.id, existing, entry.sizeBytes)

        if (entry.sizeBytes > 0 && existing >= entry.sizeBytes && existing > LocalModelStore.MIN_BYTES) {
            val file = store.promoteStaging(entry.fileName)
            _state.value = DownloadState.Succeeded(entry.id, file.name)
            return
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                if (existing > 0) header("Range", "bytes=$existing-")
            }
            .build()

        val executed = http.newCall(request).also { call = it }.execute()
        executed.use { response ->
            val code = response.code
            val contentType = response.header("Content-Type")
            if (LocalModelDownloadPolicy.needsAccessToken(code) ||
                LocalModelDownloadPolicy.isHtmlContentType(contentType)
            ) {
                throw AccessTokenRequiredException(tokenMessage(entry, code))
            }
            if (code != 200 && code != HTTP_PARTIAL) {
                throw IllegalStateException("Download failed (HTTP $code).")
            }

            val append = code == HTTP_PARTIAL && existing > 0
            if (!append && staging.exists()) {
                staging.delete()
            }
            val declared = response.body?.contentLength() ?: -1L
            val total = when {
                append && declared > 0 -> existing + declared
                declared > 0 -> declared
                entry.sizeBytes > 0 -> entry.sizeBytes
                else -> -1L
            }
            if (total in 1 until LocalModelStore.MIN_BYTES) {
                throw IllegalStateException("Server did not return a model file.")
            }

            val body = response.body ?: throw IllegalStateException("Empty download body.")
            body.byteStream().use { input ->
                FileOutputStream(staging, append).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var copied = if (append) existing else 0L
                    var lastEmit = 0L
                    val header = ByteArray(24)
                    var headerFilled = 0
                    var headerChecked = false
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (!headerChecked) {
                            val take = minOf(n, header.size - headerFilled)
                            System.arraycopy(buf, 0, header, headerFilled, take)
                            headerFilled += take
                            if (headerFilled >= header.size) {
                                if (LocalModelDownloadPolicy.looksLikeHtml(header)) {
                                    throw AccessTokenRequiredException(tokenMessage(entry, code))
                                }
                                headerChecked = true
                            }
                        }
                        output.write(buf, 0, n)
                        copied += n
                        if (copied - lastEmit >= EMIT_EVERY_BYTES || lastEmit == 0L) {
                            lastEmit = copied
                            _state.value = DownloadState.Running(entry.id, copied, total)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }

            if (staging.length() < LocalModelStore.MIN_BYTES) {
                staging.delete()
                throw IllegalStateException("Downloaded file is too small to be a model.")
            }
            val got = staging.length()
            val complete = LocalModelDownloadPolicy.isCompleteEnough(got, entry.sizeBytes) ||
                (total > 0 && got >= total)
            if (!complete) {
                throw IllegalStateException(
                    "Download looks incomplete (${LocalModelDownloadPolicy.formatBytes(got)}). " +
                        "Tap Download to resume — the partial file is kept."
                )
            }
            val file = store.promoteStaging(entry.fileName)
            _state.value = DownloadState.Succeeded(entry.id, file.name)
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? {
        return runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smriti:modeldl").apply {
                setReferenceCounted(false)
                acquire(45 * 60 * 1000L)
            }
        }.getOrNull()
    }

    private fun tokenMessage(entry: LocalModelCatalog.Entry, code: Int): String {
        val name = entry.displayName
        return if (code == 403 || entry.requiresAccessToken) {
            "Hugging Face blocked $name (HTTP $code). Accept the Gemma license, paste a token that starts with hf_, tap Save token, then retry."
        } else {
            "Sign in to Hugging Face to download $name (HTTP $code)."
        }
    }

    private class AccessTokenRequiredException(message: String) : IllegalStateException(message)

    companion object {
        private const val TAG = "SmritiModelDl"
        private const val HTTP_PARTIAL = 206
        private const val EMIT_EVERY_BYTES = 512 * 1024L
        private const val USER_AGENT = "SMRITI-Aqua/1.0 (Android) huggingface_hub/0.26.0"
    }
}
