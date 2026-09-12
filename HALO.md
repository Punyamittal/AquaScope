# Monster Halo · SMRITI Light (iQOO 15)

The screen is SMRITI’s mind. The rear **Monster Halo** is SMRITI’s physical expression.

## Semantic states (never arbitrary RGB)

| State | Screen meaning | Halo |
|-------|----------------|------|
| NORMAL | Home memory calm | Soft cyan breathe |
| SCANNING | AquaScope sensing | Blue→cyan→purple sweep |
| PROCESSING | Analyzing response | Purple pulse |
| NEW_MEMORY | Episode written | Warm white flash → settle |
| ANOMALY | Something changed | Amber slow pulse |
| PERSISTENT_ANOMALY | Change persists | Deeper amber, slower |
| CONFIRMED | User-confirmed issue | Restrained warm red |
| MEMORY_RECALL | Ask / retrieve | Teal sweep |
| UNKNOWN | Insufficient evidence | Neutral white, low (not red) |
| OFF | Night / privacy | Dark |

## Architecture

```
UI / SmritiCore outcome
        ↓
SmritiLightMapper          ← semantic mapping
        ↓
MonsterHaloController      ← prefs, listeners, brief transitions
        ├─ VivoMonsterHaloDriver  → vivo_light_service binder (when present)
        └─ SimulatedHaloDriver    → no-op (always safe)
        ↓
HaloIndicatorView          ← on-screen camera-border mirror
MemoryFieldView.syncExpression ← field motion matches light
```

App **never depends** on hardware. If `vivo_light_service` is missing or Dynamic Light is off in OriginOS settings, Ask/Scan/Home continue; the on-screen indicator still mirrors state.

## Settings

**System** tab → **SMRITI LIGHT · MONSTER HALO**

- Monster Halo on/off
- Night / privacy (disable physical light)
- Brightness Low / Med / High
- Show on-screen indicator
- Preview state cycle

Also enable OriginOS **Settings → Shortcuts and Accessibility → Dynamic Light** for hardware output.

## Troubleshooting (light not changing)

On iQOO 15 (`I2501`) we verified:

- `vivo_light_service` **is registered** on the device
- Apps must unlock Hidden API access to `ServiceManager` (SMRITI does this via HiddenApiBypass)
- Binder connect can succeed (`start ok id=…`) while `hasLight=false` until OriginOS **Rear light effects** is enabled

### Fix steps

1. Install / reopen SMRITI AQUA (force-stop first)
2. **System** → status should show `Hardware: CONNECTED to vivo_light_service`
3. Tap **Open OriginOS rear light settings** → enable rear / atmospheric light
4. Tap **Test physical light (solid cyan)** — look at the **back** of the phone

Logcat success line:

`VivoLight: start ok id=… name=vivo_light_service`
