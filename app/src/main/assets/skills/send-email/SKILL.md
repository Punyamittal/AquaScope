---
name: send-email
description: Draft and open an email using the phone's mail app. Trigger for "email", "send mail", or "compose email".
source: google-ai-edge/gallery
tool: email
---

# Send Email

## Instructions

When the user wants to email someone:

1. Extract recipient, subject, and body from the request when present.
2. Use the email tool, which opens the native mail composer.
3. Confirm briefly that the mail app was opened (or that no mail app is available).
4. Do not claim the email was already sent.
