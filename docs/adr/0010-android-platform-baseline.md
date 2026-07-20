# 0010. Android platform baseline for a fullscreen GL game

Status: proposed
Date: 2026-07-20

## Context

Minimum SDK is API 29 (Android 10) per the brief. The client's device runs
**Android 16 (API 36)**, so that is the runtime that actually matters in practice,
and we should target it. Several modern Android behaviours are cheap to design in
and genuinely annoying to retrofit into a fullscreen OpenGL game.

The Play Store has also been deferred: we deliver a **signed universal APK** the
client sideloads. Packaging is simpler, but we build to store-ready standard
anyway since they may publish later.

## Decision

**`minSdk 29`, `targetSdk 36`, single universal APK, no native libraries** (ADR
0002 — so there is no ABI matrix to split).

Platform behaviours designed in from the start:

**1. Edge-to-edge is enforced and cannot be opted out of** at `targetSdk 35+`.
The GL surface fills the display including under the status bar, navigation bar
and camera cutout. The *playfield* must not. The well is laid out inside the safe
insets obtained from `WindowInsets`, with the surface extending behind them so the
procedural material bleeds to the screen edge decoratively. **The UX Designer needs
this now**: there is a distinction between the drawing surface and the play area,
and the play area is inset by a device-dependent amount. Retrofitting this after
the well's coordinate system is fixed is painful.

**2. Predictive back.** Register an `OnBackInvokedCallback`. Back pauses the game
rather than destroying it, and must not exit mid-run without confirmation. Cheap
now, invasive later.

**3. Explicit refresh rate.** `Surface.setFrameRate(60f, ...)` per ADR 0006,
guarded for API 30+. On a 10–120Hz LTPO panel, not asking is itself a decision.

**4. `android:allowBackup="false"`** is being set by the Security Engineer, so
`SharedPreferences` are never copied off-device and there is no cloud copy to fall
back on. **Persisted state must therefore be parsed defensively**: a corrupt,
truncated or absent preferences file must fall back to defaults and never crash
launch. Persisted state is only the personal best, settings and the cached quality
tier — all trivially defaultable. This is a contract on whoever writes persistence,
and it is small: wrap reads, catch, default, continue.

**5. No `INTERNET` permission.** The Security Engineer's check inspects the
**merged** manifest and fails closed, so a dependency that requires network access
will break the build loudly. That is the intent. It is also a selection
constraint: **do not add a dependency without checking what it contributes to the
merged manifest.** The design needs no third-party libraries at all, which makes
this easy to honour.

**6. Lifecycle.** `preserveEGLContextOnPause` where available; otherwise recreate
GL resources on resume. All GL objects must be recreatable from scratch — shaders
are compiled from source strings in the APK, buffers are rebuilt from simulation
state, and there are no texture assets to reload (ADR 0007), so context loss is
comparatively painless here.

**7. Not doing.** No `GameActivity`/`GameTextInput` from the Android Game
Development Kit — it is a native-code path and ADR 0002 keeps us off the NDK. No
Play Asset Delivery, no App Bundle, no Play Games Services, no Play Integrity: all
Play-specific, all deferred with the store. Plain `Activity` + `GLSurfaceView`.

## Alternatives considered

**`targetSdk 29` to avoid edge-to-edge and predictive back** — rejected. It would
sidestep both behaviours today and leave the app permanently unshippable to the
Play Store, which the client may still want. It also produces visible
compatibility artefacts on Android 16. The two behaviours cost a day between them.

**App Bundle (AAB) output** — rejected for now since sideloading needs an APK. ADR
0002 means there are no ABI or density splits worth having anyway, so an AAB would
produce essentially the same artefact. Trivially reversible if the client
publishes.

**Immersive / sticky fullscreen hiding the system bars** — rejected as the default.
It fights predictive back and gesture navigation, and the brief's control scheme
already uses full-screen drag, which conflicts with edge gestures. Better to
respect insets and design the play area around them. Revisit only if edge-swipe
conflicts prove intolerable in playtest.

**Jetpack Compose or Android Views for the UI layer** — deliberately deferred, not
decided here. The game itself is a single GL surface; settings and the dev panel
need *some* UI. Whichever is chosen must not leak into `:core-sim` (ADR 0008). The
UX Designer's spec should drive it, and it is a cheap, reversible choice.

## Consequences

**Easy.** No ABI splits, no page-size concerns, no Play machinery, one artefact.
No third-party SDKs means the merged manifest stays trivially auditable and the
no-network guarantee is easy to keep. GL context loss is cheap to recover from
because there are no assets.

**Hard.** Edge-to-edge means the playfield's usable area is device-dependent, so
the well's aspect ratio and the coverage-band geometry must be computed from
insets at runtime rather than hardcoded. That touches ADR 0004's band layout and
the UX Designer's composition, and it is the item most likely to cause rework if
it is not designed in from the first screen.

**Live with.** We target API 36 while claiming support down to API 29, and we will
only ever test on API 36. The API 29 claim is as unverified as the 2020 hardware
floor in ADR 0009, and should be described with the same honesty — supported by
construction, not by testing.
