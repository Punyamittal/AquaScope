package com.aquascope.smriti.skills

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * Native stand-ins for Edge Gallery JS / intent tools (no WebView required).
 */
class SkillToolRunner(context: Context) {

    private val app = context.applicationContext

    fun run(skill: AgentSkill, question: String): String? {
        return when (skill.tool?.lowercase(Locale.US)) {
            "wikipedia" -> wikipedia(question)
            "hash" -> hash(question)
            "email" -> email(question)
            else -> null
        }
    }

    private fun wikipedia(question: String): String? {
        val topic = extractTopic(question) ?: return "No clear topic to look up."
        return try {
            val encoded = URLEncoder.encode(topic, "UTF-8")
            val api =
                "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
            val conn = (URL(api).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("User-Agent", "SMRITI-Aqua/1.0 (Android; Edge Gallery skill port)")
                setRequestProperty("Accept", "application/json")
            }
            conn.inputStream.bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val title = json.optString("title").ifBlank { topic }
                val extract = json.optString("extract").trim()
                if (extract.isBlank()) {
                    "Wikipedia had no summary for “$title”."
                } else {
                    "Wikipedia — $title: ${extract.take(700)}"
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wikipedia failed", t)
            "Wikipedia lookup failed (${t.message ?: "network error"})."
        }
    }

    private fun hash(question: String): String {
        val text = extractQuoted(question)
            ?: question.replace(Regex("(?i)(calculate|compute|get|what is|what's|the|hash|of|sha-?256|checksum)\\s*"), " ")
                .trim()
                .ifBlank { question.trim() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "SHA-256 of “${text.take(80)}”: $digest"
    }

    private fun email(question: String): String {
        val to = Regex("""[\w.+-]+@[\w.-]+\.\w+""").find(question)?.value
        val subject = extractAfter(question, listOf("subject", "about")) ?: "Note from SMRITI"
        val body = extractQuoted(question) ?: question
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                if (!to.isNullOrBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
            "Opened the mail app to compose${if (to != null) " to $to" else ""}."
        } catch (t: Throwable) {
            Log.w(TAG, "email intent failed", t)
            "No email app available on this phone."
        }
    }

    private fun extractTopic(question: String): String? {
        val q = question.trim()
        val patterns = listOf(
            Regex("""(?i)(?:who is|what is|what's|tell me about|history of|wikipedia)\s+(.+)$"""),
            Regex("""(?i)look up\s+(.+)$""")
        )
        for (p in patterns) {
            val m = p.find(q) ?: continue
            val topic = m.groupValues[1].trim().trim('?', '.', '!')
            if (topic.length >= 2) return topic.take(80)
        }
        return q.take(60).takeIf { it.length >= 3 }
    }

    private fun extractQuoted(question: String): String? {
        val m = Regex("""["“](.+?)["”]""").find(question) ?: return null
        return m.groupValues[1].trim().ifBlank { null }
    }

    private fun extractAfter(question: String, keys: List<String>): String? {
        for (k in keys) {
            val m = Regex("(?i)$k\\s*[:=]\\s*(.+)").find(question) ?: continue
            return m.groupValues[1].trim().take(120)
        }
        return null
    }

    companion object {
        private const val TAG = "SmritiSkillTools"
    }
}
