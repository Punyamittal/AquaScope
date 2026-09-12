package com.aquascope.smriti.llm

import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.SmritiAnswer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmritiAnswerComposerTest {

    private val rule = SmritiAnswer(
        text = "I detected 2 acoustic deviation record(s). Status remains UNCONFIRMED / POSSIBLE — not CONFIRMED.",
        evidenceState = EvidenceState.POSSIBLE,
        relatedEvents = emptyList(),
        evidence = null
    )

    @Test
    fun `rejects invented confirmed leak`() {
        assertFalse(
            SmritiAnswerComposer.passesGroundingCheck(
                "Yes, there is a confirmed leak in the kitchen.",
                rule
            )
        )
    }

    @Test
    fun `allows rephrase without new claims`() {
        assertTrue(
            SmritiAnswerComposer.passesGroundingCheck(
                "I found two unusual acoustic readings. This looks possible but is not a confirmed leak.",
                rule
            )
        )
    }

    @Test
    fun `prompt includes canonical answer and forbids invention`() {
        val prompt = GroundedPromptBuilder.build("Is it a leak?", rule, emptyList())
        assertTrue(prompt.contains("CANONICAL_ANSWER"))
        assertTrue(prompt.contains("Do NOT invent"))
        assertTrue(prompt.contains(rule.text))
    }
}
