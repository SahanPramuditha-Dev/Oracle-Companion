# Oracle Companion App UI & Engineering Rules

## 1. UI Craft & Design Quality Standards
- **No Developer Slop:** Never use default unstyled Material components, flat gray boxes, or generic wireframes.
- **Aesthetic Direction:** Oracle Companion follows a **Sleek Dark HUD / Cyberpunk Telemetry** aesthetic:
  - Base background: Deep black/onyx (`#0A0B10` or `#0D0E15`).
  - Elevated card surfaces: Layered slate (`#151822`) with subtle 1dp border stroke (`#1FFFFFFF` / 12% white).
  - Status Indicators: Pulsing emerald (`#10B981`) for Connected/Active, Amber (`#F59E0B`) for Connecting/Warning, Rose (`#F43F5E`) for Disconnected.
  - Accent Color: Electric Indigo/Cyan for primary CTAs and active switches.
- **Typography:**
  - Clear contrast between bold metric values (monospace/extra bold) and secondary labels (muted, 60% opacity).
  - Uppercase tracked badges for categories and system states (e.g., `DEVICE HEALTH`, `PC PERFORMANCE`).

## 2. Mobile Ergonomics & Compose Best Practices
- **Touch Targets:** Minimum 48dp on all interactive elements.
- **Edge-to-Edge:** Respect `statusBarsPadding()` and `navigationBarsPadding()`.
- **Primary Actions:** Anchor main action buttons in the bottom thumb zone (52dp - 56dp height).
- **Motion:** Use spring animations for press feedback and smooth transitions (`fadeIn + expandVertically`).
