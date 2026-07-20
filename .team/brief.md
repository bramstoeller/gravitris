# Product brief — **Gravitris**

Status: **Gate 1 approved** (2026-07-20) — client instruction: keep iteration one simple
Date: 2026-07-20
Author: Product Lead, from client interview (see `conversations/`)

## Problem

Falling-block puzzle games are a solved, saturated genre. Every entrant competes
on polish over a mechanic that has not meaningfully changed in forty years,
because the grid is load-bearing: pieces snap, rows fill, rows clear.

This product removes the grid. Blocks are heavy, deformable soft bodies that
squash, bulge, sag and settle under real physics. Nothing snaps. The stack is
alive — it compresses under its own weight as it grows, and every session
settles differently.

That single change turns a memorized-pattern game into a physical, tactile one,
and creates a mechanic that cannot be cloned by reskinning a Tetris tutorial.

## Users

Casual mobile players who already know falling-block games and want something
that feels new rather than nostalgic. The hook is sensory — weight, squish,
haptics — not depth of strategy. Sessions are short, one-handed, portrait.

No accounts, no social features, no multiplayer. A player installs it, plays it
on a commute, and beats their own score.

## The mechanic

**Soft-body squash and settle.** Each piece is a deformable body, not a rigid
shape. On landing it compresses, spreads and jiggles; the stack beneath it
compresses in turn. Pieces get heavier as the game progresses, so the tower
increasingly sags and shifts under its own load.

**Coverage-band clearing.** Because nothing is grid-aligned, a horizontal band
clears when it is *sufficiently filled by material* (target threshold ~90%,
to be tuned), not when a perfect row forms. This rewards packing and squeezing
material into gaps rather than tidy stacking, and follows naturally from
deformable bodies.

**Endless with rising difficulty.** One mode. Two difficulty dials — fall speed
and block mass — so difficulty emerges from the physics rather than being
bolted on. Play until the stack tops out. Score and personal best, stored
locally.

Losing condition, tuning of the threshold, and scoring formula are open design
questions for the architecture gate.

## Look and feel

**Tactile and organic**, produced **procedurally in shaders** — gel, rubber and
dough rendered via subsurface approximation, noise grain and rim lighting rather
than authored texture assets. Keeps the APK small and makes the art direction a
set of tunable parameters instead of a folder of PNGs.

Shading is not decoration here: it is what keeps individual blocks legible once
they have deformed together. Piece identity is carried by **hue**, since shape
deforms away.

**Band feedback:** material in a nearly-full band glows warmly from within. The
feedback lives on the blocks themselves — no HUD chrome, no percentage readout —
and teaches the clearing rule without a tutorial.

**Feel:** haptic pulse on impact scaled to mass and fall speed; impact
propagates visibly down the stack; screen shake scaled to mass; the clear reads
as a release of pressure, with the stack above dropping and re-settling. That
re-settle is the payoff moment and is given time to be watched.

## Controls

Direct manipulation, no on-screen buttons:

- **Drag anywhere** on screen to move the piece left/right (drag target is the
  whole screen, not the piece, so the thumb rests low and out of the way)
- **Tap** to rotate
- **Swipe down** to hard-drop
- A projected **landing silhouette** shows where the piece will settle

Rationale: buttons cost ~20% of a portrait screen, and this game needs vertical
space because a sagging soft-body stack spreads action across the whole well.
Direct manipulation also matches the fantasy of handling something heavy.

Accepted tradeoff: less precise than buttons. Mitigated by the landing
silhouette and by rotate-on-tap rather than a gesture.

## Platform and constraints

| Constraint | Decision |
| ---------- | -------- |
| Platform | Android only. No iOS, no desktop, no port path planned. |
| Minimum OS | Android 10 (API 29) |
| Reference device | **Fairphone (Gen. 6), Android 16** — Snapdragon 7s Gen 3, 8GB RAM, 6.31" LTPO OLED, adaptive 10–120Hz, 1400 nits. The client's own phone. |
| Performance target | 60fps on the reference device. See *Performance target* below — the original "2020-era mid-range" floor is retained only as an aspiration. |
| Stack | Native Kotlin, custom XPBD soft-body solver, OpenGL ES 3.0 |
| Orientation | Portrait only |
| Monetization | None. Free, no ads, no IAP, no tracking, no third-party SDKs |
| Network | None. The app makes no network requests. |
| Data | Local only — score and settings. No accounts, no PII, no analytics. |
| Distribution | **Signed APK, sideloaded.** Built to store-ready standard; not submitted to Google Play in this phase. Decided 2026-07-20 — see *Publishing constraint*. |
| Test device | Client has an Android phone and can sideload **with written help**. Plain-language install instructions are a deliverable, not an afterthought. |

Stack rationale: no mainstream 2D engine ships adequate soft-body dynamics, so
the solver is custom regardless. Once that is true, an engine only supplies
rendering and packaging while costing 25–35MB of runtime and a CI-hostile build.
Native gives control of solver memory layout (soft-body physics is cache-bound),
a ~5MB APK, and a reproducible headless Gradle build. See forthcoming ADR.

## Performance verification

This container cannot measure real-device performance and must not pretend to.
It has no `/dev/kvm` and no `/dev/dri`, so an emulator runs under full software
CPU emulation with a software rasterizer. Such an emulator is useful for
**correctness** — launch, input, visual regression, automated tests — and its
frame timings are worthless as a performance signal. No performance claim may
ever cite emulator numbers.

What can be measured honestly here: the solver core is pure CPU math and can be
benchmarked natively on the container's x86_64 hardware, yielding real
throughput figures from which mobile ARM performance can be *estimated*. This
sizes the design. It says nothing about the rendering half.

The loop is closed on the client's own phone. The prototype therefore ships with
an **on-screen frame-time readout** from the first build, so the client can
report real numbers from real silicon while also judging whether the squish
feels good. This is a requirement of milestone one, not a debug extra.

DevOps to advise on whether a correctness-only emulator is worth its setup cost.

## Performance target — revised 2026-07-20

The brief originally targeted "60fps on a 2020-era mid-range device". The client's
actual device is a Fairphone (Gen. 6) on a Snapdragon 7s Gen 3 — comfortably
above that floor.

This matters for honesty, not for ambition. A pass on the client's phone proves
the game runs well and feels good. It does **not** prove we meet the 2020
mid-range floor, and no hardware this team or the client can reach will prove
it. Retaining an unverifiable criterion invites us to claim it later on no
evidence.

So: the Fairphone 6 is the **reference device** and the verified target.
Broad low-end compatibility is pursued through **dynamic quality scaling** —
measuring frame time at runtime and degrading solver iterations, particle count
or substep rate to hold frame rate — rather than by hand-tuning for a device
nobody has. Android 10 (API 29) remains the minimum API as a cheap compatibility
floor, not as a verified performance claim. Architect to confirm or challenge.

**Open: frame-rate policy.** The panel is adaptive 10–120Hz. Rendering at 120
halves the frame budget to ~8.3ms, which is likely fatal for a soft-body solver;
locking to 60 is safe. The expected answer is a fixed 60Hz physics timestep with
interpolated rendering, which also keeps the simulation deterministic and
testable. Owned by the Architect, pending ADR.

## Publishing constraint — verified 2026-07-20, then sidestepped

A **new personal** Play developer account (any created after 13 Nov 2023) cannot
publish to production until it has run a closed test with **at least 12 testers
opted in for 14 consecutive days**, then applied for production access, which is
reviewed in up to ~7 days. That is roughly three weeks and twelve real humans
between "build is ready" and "on the store".

**Organization accounts are exempt** and may publish straight to production, but
require a D-U-N-S number for the legal entity, which itself takes days to obtain
if not already held.

**Client decision (2026-07-20): skip the store for this phase.** We build to
store-ready standard and deliver a signed APK the client can sideload and share
directly. This removes the constraint entirely and shortens the path to a
finished thing in the client's hands.

Consequences, which are all simplifications:

- No Play Console account, no D-U-N-S, no 12-tester closed test, no production
  access review, no store listing copy, no screenshots or feature graphic, no
  Data Safety declaration in this phase.
- Signing is a self-managed upload key rather than Play App Signing. The
  client holds it; it never enters this container. The key must be backed up —
  without Play App Signing there is no reset path, so losing it means the app
  can never be updated under the same identity. This is now the project's one
  unrecoverable failure mode and the signing guidance must say so plainly.
- Sideloading means users see "install from unknown sources" warnings. Install
  instructions must cover this without sounding alarming.
- **A packaging consequence:** sideloading ships one universal APK rather than
  per-device App Bundle splits. If the solver goes NDK-native this carries every
  ABI at once and the ~5MB size target does not survive; if it stays pure
  Kotlin/JVM there are no ABI splits and the target likely holds. Architect to
  decide explicitly and re-baseline the figure if needed.
- **One upside:** Google no longer re-signs the artifact, so the bytes the user
  installs are the bytes we built. Reproducibility now extends all the way to
  the device instead of breaking at upload.
- **One thing that gets worse:** no store means no crash dashboard. The team is
  blind to field problems by construction. That is the accepted cost of the
  privacy posture, and it raises the pressure behind the crash-reporter
  tripwire — expect that conversation after release rather than being surprised
  by it. It belongs with the client, not settled in a pull request.
- Publishing later remains open. Nothing in this decision forecloses it; the
  account-type question simply returns at that point.

## The single unrecoverable failure

With Play App Signing unavailable, the client holds the only copy of the only
signing key. **Losing the keystore or its password cannot be recovered from.**
Key rotation is not a recovery path — it requires the old key to prove
continuity, so it helps with compromise and never with loss.

The consequence reaches the players, not only the client. Recovering from a lost
key means users uninstall and reinstall, which deletes app data — and because
`allowBackup` is disabled for privacy, there is no backup to restore. Every high
score is gone. Those two decisions interact, and the trade is accepted
deliberately: the privacy posture is worth more than the recoverability of a
local arcade score.

The guidance in `docs/security/signing-and-key-custody.md` deliberately biases
toward **redundancy over secrecy** — three copies, password stored alongside the
file in offline copies — because the realistic risk for a solo developer with no
store presence is losing the key to a dead laptop, not an attacker seeking it.
That inverts standard secret-handling advice on purpose.

**This is the project's only risk with no automated backstop.** Every other
control fails closed in CI. This one lives on the client's machine after
handover, guarded by a procedure a human must follow, twice, years apart. The
team cannot hold that habit — this container will not exist in a year — so it
must transfer to the client explicitly at handover rather than being left in a
document to be rediscovered.

## Accessibility

In scope from the start, not retrofitted:

- **Reduced motion** setting — disables screen shake and damps jiggle. This game
  is nothing but motion; this is a genuine accessibility need, not a nicety.
- **Colourblind-safe piece hues** — required, because hue is the primary piece
  identity cue once shape deforms. Verified on paper only so far; simulator
  verification is a pre-release QA action, not a completed claim.

**Deferred to a later iteration (client decision, 2026-07-20):**

- **Reaction-time accommodation.** Reduced motion does not affect fall speed, so
  a player who cannot keep up with the difficulty ramp currently has no recourse.
  The client first chose to make this first-class, then reconsidered the same day
  and deferred it, consistent with their standing "keep it simple" instruction.
  Recorded as backlog item D1 so the reasoning is not re-derived later.

  The direction worth revisiting: the game's two difficulty dials — fall speed
  and block mass — are coupled by the ramp, and need not be. Mass is what makes
  the stack sag and compress, which is the interesting physics; speed is what
  makes it a reaction test. Decoupling them would let a player keep the heavy,
  dramatic physics while reducing twitch demand, which is better than a blanket
  easy mode that flattens the experience. When it is built it should not be
  framed as a difficulty setting, should be reachable mid-game rather than only
  before starting, and should not penalise score — there is no leaderboard and
  no second party, so an asterisk would serve nobody.

## Success criteria

1. Installs and runs on the **reference device** (Fairphone Gen. 6, Android 16),
   holding 60fps with a full stack of soft bodies in play, measured on-device.
   The original "2020-era mid-range" floor is **not** a success criterion — it is
   unverifiable with any hardware this team or the client can reach, and a
   criterion nobody can check is one that gets quietly claimed on no evidence.
   Broad low-end compatibility is pursued through startup quality calibration
   (ADR 0009), not by hand-tuning for a device nobody has.
2. The squish reads as *heavy* — players describe the blocks as weighty and
   physical without being prompted.
3. A player understands coverage-band clearing within one session, without a
   tutorial screen.
4. Builds reproducibly from a clean container with one documented command, and
   produces a **signed APK the client can install directly**, to a standard that
   would pass store review if submitted later.
5. Ships with no network permission and no data collection. Note the precise
   claim: absence of `INTERNET` is kernel-enforced and guarantees the app
   *cannot open a socket*. It does **not** by itself guarantee no data leaves
   the device — `android:allowBackup` defaults to true, and the platform will
   copy `SharedPreferences` to the user's Drive from outside the app process.
   `allowBackup=false` and `dataExtractionRules` are therefore required before
   the store listing may make the stronger claim. Until then the safe wording is
   "no network permission; the app cannot make network requests."
   See `docs/security/threat-model.md`.

## Non-goals

Explicitly out of scope. Anything here requires a new client decision:

- iOS, desktop, web, or any cross-platform port
- Multiplayer, leaderboards, accounts, cloud save, social features
- Ads, in-app purchases, paid tiers, analytics or any third-party SDK
- Hand-designed levels, puzzle stages, or any authored content pipeline
- Additional game modes (sprint/timed) before the milestone demo
- Landscape orientation and tablet-optimized layouts
- Localization beyond English
- Google Play submission, store listing assets, and Data Safety declaration
  (deferred by client decision 2026-07-20, not cancelled)
- Reaction-time / difficulty accommodation (deferred 2026-07-20, backlog D1 —
  reduced motion and colourblind-safe hues remain in scope and are unaffected)
- Authored texture assets (revisitable at the release gate if procedural
  shading proves insufficient)

## Sequencing

"Store-ready" is the release target; it is not the first thing built.

1. **Prototype** — solver + one mode, placeholder look, installable APK. Proves
   the physics feels good. This is the milestone demo.
2. **Game** — art direction, feedback, accessibility, scoring, settings.
3. **Release** — signing, store assets, metadata, and written instructions for
   the client to register a Play Console account and publish.

Rationale: deformable-body physics at 60fps on mid-range hardware is the
project's single largest risk. If the core feel fails, no amount of store polish
recovers it, so it is proven first.

## Open questions

Carried into the architecture gate:

1. Coverage threshold — what percentage actually feels fair? Requires tuning
   against a playable build.
2. Losing condition — with a sagging, shifting stack, what counts as "topped
   out"? A fixed line is unfair if the stack settles back down.
3. Solver budget — particle and constraint counts affordable at 60fps on the
   target device. Determines how deformable blocks can be.
4. Scoring formula — should packing efficiency be rewarded beyond the clear
   itself?
5. Play Console — client must register an account before release; signing key
   custody is the client's, and no credentials enter this container.
