package com.smriti.aqua.memory

/**
 * Turns a grounded [RecallAnswer] into spoken/display prose.
 *
 * Implementations must NEVER add facts that are not present in the answer
 * produced by [RecallEngine]. The episodic log is the only source of truth;
 * narration is presentation, not knowledge.
 */
interface Narrator {
    fun narrate(a: RecallAnswer): String
}

/**
 * The default narrator: returns the grounded answer text verbatim.
 * Zero risk of embellishment — what the log said is what the user hears.
 */
class TemplateNarrator : Narrator {
    override fun narrate(a: RecallAnswer): String = a.text
}

/**
 * Drop-in point for an on-device LLM narrator (MediaPipe LLM Inference / LiteRT
 * with Gemma 3 270M INT4 on the Qualcomm Hexagon NPU).
 *
 * CURRENT BEHAVIOR: this stub simply falls back to [TemplateNarrator], i.e. it
 * returns [RecallAnswer.text] verbatim. It is safe to ship as-is.
 *
 * ---------------------------------------------------------------------------
 * HOW TO MAKE THIS A REAL LLM NARRATOR (drop-in instructions)
 * ---------------------------------------------------------------------------
 *
 * 1. Model asset
 *    - Download Gemma 3 270M Instruct, INT4 quantized, in `.task` (MediaPipe
 *      LLM Inference) or `.litertlm`/`.tflite` (LiteRT) format.
 *    - Place it in `app/src/main/assets/models/gemma3-270m-it-int4.task`
 *      (already covered by `noCompress "tflite"`; add the extension if needed)
 *      or push it to app-private storage at first run. NO network at runtime:
 *      the model ships with the APK or is sideloaded.
 *
 * 2. Dependency (build.gradle.kts, app module) — one of:
 *    - MediaPipe:  implementation("com.google.mediapipe:tasks-genai:<latest>")
 *    - or LiteRT:  implementation("com.google.ai.edge.litert:litert:<latest>")
 *    Both run fully on-device. Neither requires INTERNET permission (and this
 *    app must never add it).
 *
 * 3. NPU / Hexagon delegation
 *    - Construct the engine with GPU/NPU backend preferences; on Snapdragon
 *      Elite-class parts (iQOO 15) the LiteRT Qualcomm AI Engine Direct (QNN)
 *      delegate targets the Hexagon NPU for INT4 weights. Fall back to GPU,
 *      then CPU (XNNPACK) if the delegate is unavailable:
 *        val options = LlmInference.LlmInferenceOptions.builder()
 *            .setModelPath(modelPath)
 *            .setMaxTokens(1024)
 *            .setPreferredBackend(Backend.CPU) // swap for NPU/GPU delegate when wired
 *            .build()
 *    - Lazy-init on a background thread; the 270M INT4 model loads in <1s and
 *      idles at a few hundred MB RAM.
 *
 * 4. System prompt (this is the safety contract — keep it verbatim):
 *
 *      "You are the narration layer of SMRITI AQUA, an offline acoustic memory
 *       device. You will be given a GROUNDED ANSWER that was computed from an
 *       on-device event log. Rephrase it naturally and warmly in the user's
 *       language. HARD RULES:
 *       1. Use ONLY the facts, numbers, timestamps and statuses present in the
 *          GROUNDED ANSWER. Do not add, infer, or invent any fact.
 *       2. Never confirm a leak, a breakage, or any real-world cause; the log
 *          only contains acoustic observations.
 *       3. If the grounded answer says there is no record, say exactly that.
 *       4. Keep every number and timestamp character-identical."
 *
 *    User turn template:
 *      "GROUNDED ANSWER: {a.text}\nEVIDENCE: {a.evidenceIds}\nUSER QUERY: {q}"
 *
 * 5. Verification guard (do not skip)
 *    - After generation, extract all numbers/timestamps from the LLM output and
 *      check each appears in `a.text`. If any number is missing or any new
 *      number appears, DISCARD the LLM output and return the template text.
 *    - Keep [RecallAnswer.evidenceIds] untouched — narration never changes
 *      what the evidence chips show.
 *
 * 6. Why this is safe even at INT4
 *    - Hard rule: quantisation can degrade fluency but cannot fabricate facts,
 *      because facts come from the log. The model only rewords text that
 *      [RecallEngine] already computed from SQLite rows; the numeric guard in
 *      step 5 makes any residual hallucination fail closed to the template.
 * ---------------------------------------------------------------------------
 */
class LlmNarratorStub : Narrator {
    private val template = TemplateNarrator()

    override fun narrate(a: RecallAnswer): String {
        // Stub: no LLM loaded. Always fail closed to the verbatim template.
        return template.narrate(a)
    }
}
