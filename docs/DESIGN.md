# Design reference: Material 3

Echo Home's look follows **Material 3** (https://m3.material.io/styles). Use it as the reference for every new screen. The tokens live in `app/src/main/java/com/echohome/app/M3.kt`; change them there, not at the call sites.

## Principles applied
- **Dark scheme on true black.** The surface is pure black (the Echo's panel is dark most of the day, black pixels are off, no burn-in glow). Raised elements use **tonal surfaces** (a slightly lighter tone) instead of shadows.
- **Colour roles, not colours.** `onSurface` for main text, `onSurfaceVariant` for secondary text, `primary` for emphasis (weather condition), `tertiary` containers for timers (warm amber), `secondaryContainer` for music, `outline` for the faintest text. No hard-coded `Color.rgb(...)` in the UI.
- **Shape.** Cards and chips use the extra-large corner (28 dp) or the full pill; buttons are rounded; nothing has square corners.
- **Typography.** The M3 type scale in Roboto (the system `sans-serif`; `sans-serif-medium` for title/label roles) with M3 tracking. Because the display is 5" and read from one or two metres, call sites multiply the M3 sizes (`scale` in `text(role, scale, color)`), e.g. the clock is Display Large × 1.65. Regular weight for display/headline/body, medium for title/label.
- **Touch targets** are at least 48 dp tall (chips 64 dp), with 20 dp between neighbours.
- **State feedback without words.** The gradient bar (blue-violet listening, amber thinking, green speaking) and three soft sounds (listening, understood, not understood) replace labels and indicators. Sounds are low-pitched, round and short (marimba-like, slow attack, exponential decay).
- **Motion.** Keep it light for the weak CPU: only the thin gradient bar animates, and nothing animates while Whisper is running.

## Adding a screen
1. Build text with `text(M3.Type.<ROLE>, scale, <colour role>)`.
2. Use `surface(view, M3.surfaceContainerHigh)` / `chip(...)` for tappable cards.
3. Add the screen to `screens` and `routeScreen()`; it returns Home by itself.
4. Check it on the real Echo (`adb exec-out screencap -p > shot.png`): 960×480.

## Ideas that fit the system
- Photo backgrounds for Home: a dark scrim over the photo, text stays `onSurface`; keep the clock readable first.
- Recipe screen: `Title Large` for the name, `Body Large` for steps, tonal cards for ingredients.
