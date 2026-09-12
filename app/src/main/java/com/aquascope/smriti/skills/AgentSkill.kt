package com.aquascope.smriti.skills

/**
 * Edge Gallery–compatible skill definition (SKILL.md frontmatter + body).
 * Apache-2.0 skills adapted from google-ai-edge/gallery plus SMRITI home skills.
 */
data class AgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val source: String = "smriti",
    val tool: String? = null,
    val enabledDefault: Boolean = true
) {
    val isTextOnly: Boolean get() = tool.isNullOrBlank()
}

data class SkillMatch(
    val skill: AgentSkill,
    val toolResult: String? = null
)
