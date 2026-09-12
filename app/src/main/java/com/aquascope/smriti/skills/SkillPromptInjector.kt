package com.aquascope.smriti.skills

/**
 * Builds the skill block Edge Gallery appends into the system/prompt context.
 */
object SkillPromptInjector {

    fun catalogBlurb(skills: List<AgentSkill>): String {
        if (skills.isEmpty()) return ""
        return buildString {
            appendLine("AVAILABLE_SKILLS:")
            skills.forEach { s ->
                appendLine("- ${s.name}: ${s.description}")
            }
        }.trim()
    }

    fun activeBlock(match: SkillMatch?): String {
        if (match == null) return ""
        return buildString {
            appendLine("ACTIVE_SKILL: ${match.skill.name}")
            appendLine(match.skill.instructions.trim())
            if (!match.toolResult.isNullOrBlank()) {
                appendLine()
                appendLine("SKILL_TOOL_RESULT:")
                appendLine(match.toolResult!!.trim())
            }
        }.trim()
    }
}
