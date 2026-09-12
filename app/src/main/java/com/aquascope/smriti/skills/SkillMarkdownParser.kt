package com.aquascope.smriti.skills

/**
 * Parses Edge Gallery–style SKILL.md (YAML frontmatter + markdown body).
 */
object SkillMarkdownParser {

    fun parse(raw: String, fallbackId: String): AgentSkill? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.startsWith("---")) {
            return AgentSkill(
                id = fallbackId,
                name = fallbackId,
                description = fallbackId,
                instructions = text
            )
        }
        val end = text.indexOf("---", 3)
        if (end < 0) return null
        val front = text.substring(3, end).trim()
        val body = text.substring(end + 3).trim()
        val meta = linkedMapOf<String, String>()
        front.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim().trim('"')
            if (key.isNotEmpty()) meta[key] = value
        }
        val name = meta["name"]?.ifBlank { null } ?: fallbackId
        val description = meta["description"]?.ifBlank { null } ?: name
        return AgentSkill(
            id = name,
            name = name,
            description = description,
            instructions = body.ifBlank { description },
            source = meta["source"] ?: "bundled",
            tool = meta["tool"]?.ifBlank { null }
        )
    }
}
