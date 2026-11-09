# Launcher support and verification

The launcher targets Android TV, Google TV and Android-based Fire OS at API 25+,
with ARMv7, ARM64 and x86_64 builds. Vega OS and Fire OS 5/API 22 are excluded.
These are targets, not a list of qualified devices. Local APKs use debug signing
and are not published releases. Plugin installation and live server behavior
remain unqualified.

## Evidence levels

Bounded physical checks on a TiVo Stream 4K running Android 12 established
installation, cold launch, selected media handoffs, focus restoration and a
physical Home-helper path. They do not qualify every ordinary-app or Home flow.

| Area | Remaining limits |
| --- | --- |
| Installation | Firmware-specific restrictions remain possible; TiVo testing required removal of CoreService. This is not a general recommendation to remove system software. |
| Ordinary app | Full login/IME, sustained playback, physical-remote timing, process death, large-font layouts and lifecycle combinations remain unqualified. |
| Replacement Home | Saved Home selection has disagreed with effective system routing; full replacement-Home behavior and reboot persistence remain unqualified. |
| Optional Home helper | Held/repeated Home, all originating apps, fallback and reboot persistence remain unqualified. |

## Verification checklist

Record device model, firmware, API/ABI, remote, APK hash and results for each
check. Host builds and source inspection do not establish device behavior.

1. Verify install, update, restart and ordinary launch.
2. Check Apps inventory, Move persistence, login/cancel/keyboard, account cleanup,
   offline/empty media, settings and playback handoff/return.
3. Check rapid tab traversal, focus restoration, row changes and large-font layouts.
4. Compare ordinary Back with default-Home Back; verify cold/repeated physical Home,
   selector return, process recreation and reboot. Saved selection alone is insufficient.
5. Verify plugin save/reorder/hide, restricted-user visibility, invalid configuration
   and plugin absence/offline fallback on a matching development server.
6. For the optional helper, test physical Home from another app and the stock launcher,
   held/repeated presses, normal Back and behavior after disabling the helper.

Device installation, settings changes and runtime checks require authorization
under the [development workflow](workflow.md#manual-and-platform-validation).
Record detailed results with the task evidence rather than extending this guide
with dated execution logs.
