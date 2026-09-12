package com.aquascope.smriti.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillMarkdownParserTest {

    @Test
    fun `parses gallery frontmatter`() {
        val raw = """
            ---
            name: kitchen-adventure
            description: Start a kitchen adventure.
            source: google-ai-edge/gallery
            ---

            # Kitchen Adventure
            Play along.
        """.trimIndent()
        val skill = SkillMarkdownParser.parse(raw, "kitchen-adventure")
        assertNotNull(skill)
        assertEquals("kitchen-adventure", skill!!.id)
        assertTrue(skill.description.contains("kitchen adventure", ignoreCase = true))
        assertTrue(skill.instructions.contains("Play along"))
        assertEquals("google-ai-edge/gallery", skill.source)
    }

    @Test
    fun `matches leak skill`() {
        val skills = listOf(
            AgentSkill("leak-explainer", "leak-explainer", "Explain leaks", "…"),
            AgentSkill("home-memory-brief", "home-memory-brief", "Brief the home", "…")
        )
        val hit = SkillMatcher.match("Is this a leak under the sink?", skills)
        assertEquals("leak-explainer", hit?.id)
    }
}
