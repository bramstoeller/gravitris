# 0033 — Frontend Engineer → Product Lead

**Branch:** `feat/drop-controls` (do not merge — you land it, combined with the
Backend's tetromino geometry commit 2).
**Commits this session:** `0092c1e..8c529d0` (two commits on top of the pushed
checkpoint).
**Status:** `make test` green (JVM tests + buildSrc guard-checks + lint).
Playthrough on the software emulator ran the mechanic end to end.

Finishes the drop-control scheme the previous session stopped mid-way on (it
hit the worktree write-pin block and correctly stopped). Nothing already on the
branch was re-derived.

## What I did

### 1. Migrated the four mechanical tests off the removed hard-drop input

The `InputFrame.hardDrop`/`hardDropVelocity` fields are gone (ADR 0016 — release
is the drop, the fall is plain gravity). The Backend preserved the impact-velocity
path as `Simulation.slamActivePiece(speed)`, so this was a pure migration, no
re-tuning:

- `SquishToy.kt`: the release guard `|| input.hardDrop` → `|| input.drop`, and a
  one-line `slamActivePiece` passthrough to the core.
- `CompressionRangeTest`, `ImpactEnergyRangeTest`, `SquishToyTest`: swapped each
  `input.hardDrop = true; …velocity = 30f; step()` block for
  `toy.slamActivePiece(30f); toy.step(input)`.
- Fixed one stale assertion message in `SquishToyTest` that still named
  `hardDropVelocity` (a field that no longer exists). Left the `hardDrop: Boolean`
  scenario parameter/method names as-is — they compile and still describe "the
  hard-landing case", and renaming them was outside the mechanical swap.

Commit `80f5af8`.

### 2. Wired the positioning-window urgency visual (ADR 0016)

A countdown bar drawn across the **top of the well** that drains symmetrically
toward the centre as `positioningTicksRemaining / positioningWindowTicks` falls
from 1 to 0 — the client's *"much less long able to move"* made visible. It shows
only while a piece is positioning; when the piece drops and falls, it is gone.

- `PositioningUrgency.fraction(remaining, window)` — the pure 0..1 math (clamp +
  divide-by-zero guard), unit-tested on the JVM (`PositioningUrgencyTest`).
- `gl/UrgencyBar.kt` — a small **self-contained flat-colour GL program** (not the
  gel shader), one dynamic quad rebuilt per frame. See "decisions" for why it is
  its own program rather than a palette slot on the gel shader.
- `GameRenderer` draws it last, after the stack, using the same world→clip
  scale/offset as everything else.
- `Tunables.POSITIONING_BAR_THICKNESS_WORLD` (0.6 world units) — no magic number
  inlined in `:app`.

Commit `8c529d0`.

## What I deliberately did NOT do

- **No colour decision.** The bar is `color-text` #F2F1EC (neutral, legible) as a
  placeholder. I did **not** use `color-glow` (amber, reserved for band fill) or
  `color-warn` (reserved for a not-yet-spec'd top-out zone) — a positioning timer
  must not be mistaken for either. The task said this "can be refined in the
  visual pass"; the final colour/easing/placement is UX's call. It is a plain,
  honest cue, not finished art.
- **Did not touch the playthrough script** (`scripts/emulator-playthrough.sh`,
  DevOps-owned). Its `move`/`harddrop`/`rotate` helpers still drive real touch
  input and still exercise slide→drop→fall under the new scheme, but they were
  written for the old hard-drop gesture and their comments/intent have drifted
  (e.g. a `harddrop` down-swipe is now just a release=drop). Flagged for DevOps —
  see open questions.
- **Did not update the stale comment in `GameView.kt`** (line ~124) that says the
  historical-samples handling exists for "the hard-drop velocity test". The
  handling is still correct and load-bearing for drag resolution; only the
  justification is stale. Left it to avoid scope-creep into the input code the
  previous session owns. Minor.
- Did not merge. Did not rewrite what was already on the branch.

## Considered and rejected

- **Rendering the bar through the gel shader with a new palette slot** (the
  `WellFrame` approach). Rejected: it forces a 9th `uPalette` entry, which breaks
  `PaletteTest`'s "surface is the last slot" invariants and grows the shader's
  compiled array size, and then needs five vertex attributes set to cancel the
  subsurface/rim/contact/grain/glow terms for a flat HUD element that has no
  business going through a material shader. A ten-line flat program is smaller and
  honest about what the bar is. It costs one program switch per frame, only while
  positioning.
- **An Android `View` overlay** driven from the ~4 Hz `onStats` marshal. Rejected:
  the window is ~0.83 s, so a 4 Hz update reads as a stutter, and it would need a
  new UI-thread hop for a value the GL thread already has.

## How to see it

- Route/port: it is the game itself — `make dev` (device) or `make playthrough` /
  `make screenshot` (software emulator, correctness only).
- The bar appears at the **top of the well for ~0.83 s each time a piece is
  dealt**, before it drops. It is easiest to catch in the first second after
  launch (the first piece positions immediately).
- **Looked at the captures, did not report on a green build alone:**
  - Playthrough: 5 spawns, 4 clears, pieces sliding to columns and stacking in
    seven hues, impacts firing (`imp:28`) — slide→release→fall→stack works end to
    end.
  - A dedicated burst-capture of the first second caught the urgency bar: a
    full-width near-white bar across the top of the well above the parked piece,
    then drained to nothing 0.2 s later as the window expired, then gone once the
    piece dropped. Full → drain → gone, as intended.
  - All software (SwiftShader/ANGLE) — correctness only, no appearance/perf claim.

## Open questions / uneasy about

- **Playthrough input semantics vs the new scheme (for DevOps/QA).** With the old
  hard-drop gesture gone, `move()` (a horizontal drag) now both slides *and*, on
  release, drops the piece; `harddrop()` (a down-swipe) is just a release=drop on
  an already-active piece. The script still demonstrates the mechanic, but if we
  want it to deliberately exercise *rotate-mid-fall* and *slide-then-release-late*
  distinctly, its helpers should be rewritten for the three real intents
  (drag / release / tap). Coordinate with DevOps + QA.
- I could not get hardware GL in this container (host declines `-gpu host`); all
  captures are SwiftShader/ANGLE software, correctness only. Appearance on the
  Fairphone 6 is unverified, as always here.

---
*Opened by the **Frontend Engineer**.*
