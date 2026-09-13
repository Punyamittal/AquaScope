---
name: app-control
description: Open a screen inside AquaScope itself — scan, history, report, memory timeline, settings, ScreenMind, home. Trigger for "open X", "start scan", "go to Y", "show me Z".
source: bundled
tool: app_action
---

# App Control

## Instructions

When the user asks to open a screen or start an action in this app (scan, history, report, memory timeline, settings, ScreenMind, home):

1. Use the app_action tool, which launches the matching screen directly.
2. Confirm briefly which screen was opened.
3. If no screen matched, say so plainly instead of guessing.
