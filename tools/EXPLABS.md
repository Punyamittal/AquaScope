# Experiential Labs for AquaScope coding

OpenAI-compatible gateway used with promotional / org credits.

- Platform keys: https://platform.experientiallabs.ai/api-keys
- Auth docs: https://platform.experientiallabs.ai/docs/authentication
- API reference: https://platform.experientiallabs.ai/docs/reference
- Buy credits (required for Astra / Fable): https://platform.experientiallabs.ai/credits

## Model slugs

| Model | Slug | Notes |
|-------|------|--------|
| GPT-6 Astra | `gpt-6-astra` | Needs purchased credits |
| Claude Fable 5.1 | `claude-fable-5.1` | Needs purchased credits |
| Free coding default | `gpt-5.6-luna` | Works without purchase |
| Also free | `deepseek-v4-flash`, `deepseek-v4.1-flash`, `qwen3.8-27b` | |

## Setup (do not commit the key)

```powershell
copy .env.example .env
# edit .env — paste EXPLABS_API_KEY=xpl_...
python tools/explabs_chat.py "Review AnomalyScorer distanceToPercent"
```

After buying credits:

```powershell
$env:EXPLABS_MODEL = "gpt-6-astra"
# or: claude-fable-5.1
python tools/explabs_chat.py "Suggest a dry/wet calibration plan for iQOO 15"
```

## Security

Never commit `.env` or paste keys into git/chat. If a key was exposed, revoke it at the API keys page and mint a new one.
