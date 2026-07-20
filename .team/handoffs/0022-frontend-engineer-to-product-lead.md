# 0022 — Frontend Engineer → Product Lead

Branch `fix/play-verification-and-label`, PR **#11**
(https://github.com/bramstoeller/gravitris/pull/11). Base `main` (a4661e2).
Two commits: `497321a` (label), `5bf191e` (playthrough harness). **Do not merge
— you said you will land it.** `make test` green locally.

## Headline: the app does not run the game mechanic

This is the most important thing in this handoff and it overturns an assumption
in the dispatch and in the backlog. **Verified by reading the code on `main`,
with line numbers, not from memory** — the constitution's rule about wrong
sentences to teammates applies double to a claim this size.

- `GameRenderer.rebuildSimulationIfWellChanged()` constructs `SquishToy`
  (`GameRenderer.kt:464`); `advanceSimulation` drives `toy.step(inputFrame)`
  (`:427`). The app never builds the game loop.
- `SquishToy` is explicitly the Milestone-1 toy — *"NOT game rules … no lock
  rule, no coverage, no clearing"* (`SquishToy.kt:9-37`). It calls
  `Simulation.addPiece` / `clearActivePiece` (harness affordances), never
  `Simulation.start()`.
- The mechanic — piece sequence, lock detection, `beginClear`, stack re-settle —
  runs only when `running == true`, which **only** `Simulation.start()` sets
  (`Simulation.kt:130-136`). `advanceMechanic()` returns immediately when
  `!running` (`:135-136`). `grep` for `.start(` and `FrameDriver` across
  `app/src/main` returns zero hits.
- Secondary: the app clamps the accumulator delta (`GameRenderer.kt:417`) that
  `FrameDriver` forbids by ADR 0013 ("Do not clamp the delta"). The app is not
  using the core's frame driver either.

**Consequence.** In the shipped APK pieces fall, squish, release and pile up, and
`bands.update` runs every tick so `bandFill` is real and the glow is live (this
is exactly what handoff 0021's screenshot showed — "a stack of five soft bodies",
`band:LIVE`). But `bandClearProgress` never leaves `-1`: **no band can clear,
regardless of input.** When the well fills, `SquishToy.reset()` empties the
*whole* well at once (a toy affordance) — which is visually the opposite of a
band-localised clear.

Backlog **#6** ("the mechanic … clear rule, stack drop and re-settle — **done,
landed on main**") is true for `:core-sim` (built and unit-tested) and **false
for the running product**. Handoff 0021 wired the band *values* to the shader; it
never replaced the toy driver with the game loop. Nobody's individual work is
wrong — the mechanic is correct in the core, the shader is correct — it is an
**integration gap** between `:core-sim` and the app shell that is invisible from
either side alone.

## Decision I need from you

**(A)** Ship the finding: the `make playthrough` harness + this handoff document
that the app renders and piles but does not clear. Stays inside my two-task
scope. Leaves the product not actually playable.

**(B)** Wire the mechanic into the app: replace `SquishToy` as the driver with
`Simulation.start()` driven through `FrameDriver` (ADR 0013), reconcile the
toy's release-on-quiet with the real lock rule, and decide what happens on a
blocked spawn (the core already tolerates it — `Simulation.kt:427-433` — so no
game-over is needed to make it *play*; Stage 4 adds the verdict later). Then the
playthrough can prove a real clear. This is frontend work I can do, but it is a
feature beyond the two dispatched tasks and needs Code Reviewer + probably a
demo/gate. **I have not started it** — I will not expand scope unwitnessed.

My recommendation: **(B)** — it is what "prove the game plays" actually requires,
and the milestone demo will fail without it. But it is your call.

I tried to `SendMessage` you directly and no agent named "Product Lead" was
reachable from my session, so this handoff + PR #11 are the escalation. I also
shared the finding with the DevOps Engineer, since they are verifying the
"playable game" on the emulator and would otherwise chase a clear that cannot
happen.

## What I did

**Task 2 — the stale readout label (done, `497321a`).**
Header was `milestone1 floor - not a verdict`. Both descriptors are stale:
`milestone1` (build is past it — a photographed corner would misinform the
client), and `floor` (meant a lower bound while the shader was minimal, but the
full gel/grain/band-glow shading now runs at the default top tier —
`GameRenderer.shadeLevel` defaults to `SHADE_LEVEL_MAX` — so the reading already
carries the full per-pixel cost). Now `live frame time - not a verdict`.
- The header now describes the *numbers* (true in every shade-dial state) rather
  than naming a build or a shading level. I deliberately did **not** write "full
  game" / "playable" (the finding makes that false) or "full shading" (the
  `shade:N/4` dial can drop below the top tier, and a fixed header would then
  contradict the line that reports it).
- `not a verdict` kept exactly, per your instruction: emulator numbers are a
  software artifact, the phone is the only performance instrument. The doc
  comment above the constant was rewritten to record all of this so a future
  edit does not re-inflate the claim.

**Task 1 — the play-through harness (`5bf191e`).**
`make playthrough` → `scripts/emulator-playthrough.sh`. Boots the correctness
AVD (host → swangle fallback), installs, launches, reads the screen size at
runtime, then drives real `adb shell input`: taps to rotate, horizontal swipes
to move, fast straight-down swipes to hard-drop, across left/centre/right
columns, with generous waits, capturing numbered screenshots at each moment.
Correctness only — the banner repeats the software-render / not-a-performance-
claim rule verbatim.

## What I deliberately did NOT do

- **Did not run the play-through.** The AVD is an exclusive resource and the
  DevOps Engineer is driving it on `fix/emulator-first-frame-wait` right now.
  Running a second `gravitris_correctness` would fail on the AVD lock, and both
  scripts kill stale emulators by AVD name — a straight collision. I asked them
  for a window. **The harness is unrun; the finding above stands on the code, not
  on a screenshot yet.** When the emulator is free I will run it and attach the
  evidence to PR #11.
- **Did not wire the mechanic** (option B) — awaiting your decision.
- **Did not touch `emulator-screenshot.sh`.** DevOps owns it and is mid-edit. My
  script *duplicates* their boot lifecycle rather than sourcing it. The agreed
  follow-up: extract a shared `scripts/emulator-lib.sh` once their settle
  hardening lands, so the two do not diverge. Until then a boot-logic change in
  one must be mirrored in the other — noted in the script header.
- **Did not fix `docs/install-milestone-1.md:148`**, which still quotes the old
  label string to the client. It is a milestone-1-specific, client-facing doc the
  Tech Writer owns; changing the label makes it stale. **Flag for the Tech
  Writer** — and it may want a new install doc for the playable build regardless.

## Considered and rejected

- **Label = "full game - not a verdict" / "playable build …".** Rejected: the
  finding makes it false, and the constitution forbids softening a gap into a
  success.
- **Label = "full shading - not a verdict".** Rejected: the shade dial drops
  below the top tier and the header would contradict the `shade:N/4` line.
- **Baking the toy finding into the shipped code comment** (my first draft did).
  Rejected: it is a point-in-time fact that rots if option (B) lands. The comment
  now argues from the durable reasons (dial, describe-the-numbers); the finding
  lives here, in the handoff.
- **Sourcing DevOps' script instead of duplicating.** Rejected *for now*: they
  are mid-edit and the boot logic isn't factored into sourceable functions.
  Duplicate-and-reconcile keeps me unblocked without editing their file.

## Open questions / uneasy about

- **The A/B decision is the whole ballgame.** Everything else is small.
- **The harness is unrun.** I am confident in the finding from the code, but the
  dispatch rightly wants "see the result." I need the emulator window to close
  that, and under software rendering the first-frame settle (the very thing
  DevOps is fixing) may make early screenshots noisy.
- **PR #11 CI** is pending as I write this; I am watching it and will not report
  done while it is red.
