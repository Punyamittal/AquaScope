package com.aquascope.smriti.skills

import android.content.Context
import android.util.Log

/** Loads bundled Edge Gallery–compatible skills from assets/skills/. */
class SkillCatalog(context: Context) {

    private val app = context.applicationContext
    private val cached by lazy { loadAll() }

    fun all(): List<AgentSkill> = cached

    fun enabled(prefs: SkillPreferences): List<AgentSkill> =
        all().filter { prefs.isEnabled(it.id) }

    private fun loadAll(): List<AgentSkill> {
        val am = app.assets
        val roots = runCatching { am.list("skills") }.getOrNull().orEmpty()
        return roots.mapNotNull { dir ->
            val path = "skills/$dir/SKILL.md"
            val raw = runCatching {
                am.open(path).bufferedReader().use { it.readText() }
            }.onFailure { Log.w(TAG, "skip $path: ${it.message}") }.getOrNull()
                ?: return@mapNotNull null
            SkillMarkdownParser.parse(raw, dir)
        }.sortedBy { it.name }
    }

    companion object {
        private const val TAG = "SmritiSkills"
    }
}
