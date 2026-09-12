package com.aquascope.smriti.skills

import android.content.Context

class SkillPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var skillsMasterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MASTER, value).commit()
        }

    fun isEnabled(skillId: String): Boolean {
        if (!skillsMasterEnabled) return false
        val key = keyFor(skillId)
        return if (prefs.contains(key)) prefs.getBoolean(key, true) else true
    }

    fun setEnabled(skillId: String, enabled: Boolean) {
        prefs.edit().putBoolean(keyFor(skillId), enabled).commit()
    }

    private fun keyFor(id: String) = "skill_$id"

    companion object {
        private const val PREFS = "smriti_skills"
        private const val KEY_MASTER = "master_enabled"
    }
}
