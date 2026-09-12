---
name: query-wikipedia
description: Look up a short Wikipedia summary for a topic when the user asks a general knowledge question that home memory cannot answer.
source: google-ai-edge/gallery
tool: wikipedia
---

# Query Wikipedia

## Instructions

When home memory is empty or the question is general knowledge:

1. Extract a short topic phrase (person, place, event).
2. Use the Wikipedia tool result as grounding.
3. Answer in 1–3 complete sentences in the user's language.
4. If the extract does not answer the exact question, say so and share the closest useful fact.
5. Do not invent facts beyond the tool result and memory facts.
