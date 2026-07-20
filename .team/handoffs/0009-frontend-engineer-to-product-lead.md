# 0009 — Frontend Engineer → Product Lead

Date: 2026-07-20
Branch: `feat/squish-toy`, from `feat/app-shell` with `feat/core-sim` merged in.
Commits `e6a3d52..9a9d3ec` (9). **Pushed to `origin` after each. Not merged.**

**Stage 2, Milestone 1 — the squish toy.**

`make build` produces `app/build/outputs/apk/debug/app-debug.apk`.
`make test` is green: **227 tests**, 0 failures, plus lint, the ADR 0008
no-Android check and the CHK-1/3/4 merged-manifest checks. Merged manifest
carries `VIBRATE` and nothing else.

---

## The merge

Exactly the one conflict you predicted, in `MainActivity.kt`, resolved to my
version per the backend engineer's handoff. Nothing else conflicted.

---

## What the client will see

A block falls into an empty well. Drag anywhere to move it, tap to rotate, flick
down to drop it hard. It squashes on impact, compressed material darkens, the
phone thumps in proportion to how hard it landed. When it stops moving they get
another one; when the well fills it empties and starts over.

No score, no clearing, no losing, no menus. Client-facing install instructions
are at **`docs/install-milestone-1.md`** — that is the file to send them, and it
covers what to press and what to send back.

| Key | Effect |
| --- | ------ |
| Volume **up** | Toggles compression darkening. Readout shows `shade:on` / `shade:off`. |
| Volume **down** | Runs the solver benchmark. Debug builds only. |

---

## The benchmark — and one decision you should know about

It is on volume-down, hidden, debug-only (gated on `FLAG_DEBUGGABLE`). It runs
`Simulation.buildBenchmarkScene()` — shared with the JVM benchmark exactly as
the backend engineer asked, so the two cannot drift and compare different
scenes. 900 warm-up frames matching the host protocol, 600 measured. Solver CPU
only: no rendering, no GPU. It freezes the screen for a few seconds and says so
on screen first.

**I changed the divisor, and this is the one judgement call here I would most
like reviewed.**

ADR 0009 and `blockers.md` both say to divide the device figure by the
`0.497 ms` in `spike/solver-budget/results-host.txt`. That instruction predates
`:core-sim`. That 0.497 was measured on the *spike's* solver — different
friction, different contact ordering, different code. I ran the shipped
benchmark on the build host three times against the production solver and got
**0.4443 ms, repeatable to within 0.1%**: about 11% faster than the spike.

Dividing a device number by 0.497 would fold that 11% implementation difference
into what is meant to be a pure hardware ratio, and would understate derating by
that much. So the divisor is the production solver's own host figure, with the
spike's kept alongside it for reconciliation. If you would rather the reported
number stay comparable to ADR 0009's text as literally written, it is one
constant — but I think that would be the wrong number.

The 0.4443 figure is also worth having on its own: it says the production solver
reproduces the spike's reference workload, which is independent evidence that
the port is right.

### Labelling

The readout said `stage1 baseline - not a verdict`. "stage1" is no longer
accurate, so it now says **`milestone1 floor - not a verdict`**. **"not a
verdict" stays, and the reasoning is unchanged**: the fragment shader is still a
palette lookup plus one term, so the live figures are still a floor, not
headroom. The benchmark block carries its own caveat, `solver bench - cpu only,
no gpu`, because it *is* a complete measurement of what it claims and must not
be read as a frame-rate verdict. Both strings have a comment above them saying
not to let a future edit start implying more.

---

## The defect that mattered most

**`COMPRESSION_GAIN` was wrong by more than 3x, in the direction that ships the
effect invisible.** Stage 1 flagged this as the thing I was most uneasy about
and said to measure before judging the look. I measured first.

| | harness (Stage 1) | real solver |
| - | ----------------- | ----------- |
| compression range | 0.57 .. 1.16 | 0.888 .. 1.00 |
| deepest impact darkening at gain 1.2 | 51% | **13%** |
| settled material | — | 5% |

The solver's area constraints are compliant but stiff, so it deforms far less in
*area* than the harness pretended. At the shipped gain the hardest impact in the
game would have darkened by 13% and the client would have concluded the shading
term does not work. Gain is now **4.0**, which puts the deepest impact at ~45%
and the 25th percentile at ~24%.

Two things worth knowing beyond the number:

- The signal is **spatially coherent** — ten of twenty-five particles, i.e. the
  lower rows of the piece, not one isolated vertex. It reads as a dark band
  across the contact face, not speckle.
- Settled material sits near 2%, which is **correct and deliberate**. The
  darkening is an *event*: it blooms on impact and fades as the piece relaxes. A
  resting pile reads as its own flat colour. That is the weight cue.

The measurement is committed as `CompressionRangeTest` rather than written down
here, because the constant is only meaningful relative to a distribution and
that distribution needs re-taking whenever the solver changes.

---

## Your three open judgement calls

**1. Slop-crossing.** Unchanged — crossing the 8dp touch slop does not spend the
slop distance. Still my judgement, still unconfirmed by UX. Nothing in the
integration touched it and I had no new information to reconsider it with. It
remains a real question for a device.

**2. The 55% darkening cap.** **This question has largely dissolved, and that is
new information.** On the measured range the deepest impact reaches ~45%, so the
cap never binds — it is a safety rail against a future material change rather
than a number shaping the look. The live question is now the **gain**, which is
where the visual decision actually sits. UX should be asked about 4.0, not about
55%.

**3. `bodyLattice` at 5 for Milestone 1.** Kept at 5, now as
`Tunables.TOY_LATTICE` with the reasoning attached. Nothing selects it, because
ADR 0009's startup calibration does not exist yet — and the benchmark is
precisely what will tell us whether anything needs to. Supporting 4/6 is small:
the topology is built per lattice size and is tested at 4, 5 and 6.

---

## Integration defects found and closed

The integration path was unexecuted code and I tested it as if I expected it to
be wrong. Four things were.

**1. The render triangles were not the solver's triangles.** The shell split
each lattice cell along one diagonal; the solver's two area constraints per cell
use the other. Both cover the cell, so nothing would have looked obviously
broken — but `particleCompression` is the ratio of *those specific triangles'*
current to rest areas, so the one shading term this build carries would have
been describing halves that were not the halves being drawn. Fixed, and
`TopologyMatchesSolverTest` now asserts the shell reproduces
`SimState.triangleIndices` index for index at lattice 4, 5 and 6.

**2. Archetype count mismatch.** The core declares seven archetypes; the palette
specifies six hues. Cycling on the core's count would have drawn a piece in the
well-surface grey, and anything past the palette size indexes a GLSL array out
of bounds — undefined behaviour, per-driver, not a wrong colour. The toy cycles
on the palette and the upload path clamps.

**3. Kinematic drag reads as perfectly still.** The core applies drag and
rotation by moving position and both previous-position buffers together, so no
velocity is injected — correct physics, and it means a piece being dragged along
the floor is indistinguishable from a settled one by any velocity measure. A
naive quietness test would have taken the piece out of the player's hand
mid-gesture. Input now suppresses the settle test outright.

**4. Stale piece colours after a reset.** The mesh caches archetypes against the
body count. In practice a reset always passes through zero and every count on
the way back differs from the last, so it happened to stay correct — but that is
an argument, not a guarantee. Made explicit.

### The hazard the architect named, and where it actually stands

The `vEdge`/`vContact` inversion is the nastiest hazard in ADR 0007 because
swapped they still render and merely read wrong. **It is not live in this
build**: the shader consumes neither. I want that stated plainly rather than
claimed as checked. `particleCompression`'s polarity *is* live and I did verify
it against the solver — below 1 is compressed, the shader darkens below 1.

### On the judder warning

Taken seriously. The ADR 0006 lerp is extracted into `VertexFill` and tested
against the real solver, including a test that catches a *reversed* lerp — which
passes both endpoint tests and differs only in between, so it is the one that
actually bites. If the client reports jitter, `VertexFillTest` is the evidence
that the render path can be ruled out before anyone reopens the solver.

---

## Deviations and decisions worth your attention

**A. I added a field to `SimState`.** `particleCapacity`, so the renderer can
size its buffers against the core's real body capacity instead of reproducing
the derivation. `contracts.md` §5 states additive `SimState` fields do not cross
the module boundary, so this needed no Architect — but I changed the backend
engineer's file and they should see it.

**B. `SquishToy` — staging, deliberately not game rules.** Milestone 1 needs
something to drop and a way to start over. The backend built `addPiece` /
`clearActivePiece` as "a Milestone 1 harness affordance, not the spawner",
anticipating this call site.

**The honest disclosure: deciding a piece is finished is, in embryo, Stage 3's
lock rule.** I made it as crude as possible, put it on the shell side where it
cannot affect simulation results, and wrote at the top of the file that it must
not be promoted without the lock rule being designed properly. Releasing a piece
has no consequence beyond handing the player another one — it does not score,
lock, clear or end anything. If you would rather Milestone 1 ship with literally
one piece and no successor, that is a smaller change than what is there now, but
I think it would make the toy much harder to form an opinion from: pieces
squashing against *each other* is where "heavy" reads hardest.

**C. The well empties when material reaches the top.** A toy affordance, not a
losing condition. `addPiece` throws on an overlapping placement and the backend
is explicit it must not be caught, so the shell proves the well is full before
it asks rather than after — tested, because the failure mode is the app crashing
on the client's phone after a couple of minutes.

**D. Rotation into a rotation of a square.** All seven archetypes are the same
square lattice in Stage 1, so a quarter turn maps the piece onto its own
footprint. **Tapping to rotate will look like it does nothing.** That is the
core's documented Stage 1 behaviour, not a bug, but the client will notice, and
the install document does not oversell it.

**E. The stack is lost on rotation / window resize.** `SimConfig` is immutable
by design, so a changed safe area means a new `Simulation`. Rebuilt only when
the derived config actually differs, so a cosmetic inset change does not empty
the well. Carrying positions across would drop pieces outside the new walls.

**F. Pieces spawn just above the visible well** and may show a sliver over the
status-bar region for a frame or two before falling in. Cosmetic; noted rather
than solved.

---

## Numbers the client's feel questions will land on

- **Cadence: ~210 ticks (3.5 s) per piece** in a 10x20 well — roughly 135
  falling, 55 ringing down, 20 settled before hand-over. The fall dominates.
- **Effective terminal velocity under gravity is ~12.2 units/s**, set by
  `linearDamping`, well below the solver's own 30-unit clamp — which only a hard
  drop reaches. So the solver's `MAX_SPEED` shapes hard drops and nothing else.
  Worth the backend engineer or architect knowing; the ADR reads as though 30 is
  the operative terminal velocity.
- I cut the settle delay from 30 ticks to 20 and left the fall alone. **The fall
  speed is the physics, and Milestone 1 exists to have the physics judged rather
  than tuned around.** If the client says it is slow, that is a real answer and
  the lever is `linearDamping`, not a shell constant. The install document asks
  them about it directly.

---

## What I deliberately did not do

- **No shading beyond the compression term.** No gel, subsurface, rim light,
  grain, dithering or band glow. `Shaders.kt` still states the boundary.
- **No landing silhouette, screen shake, band glow or impact propagation.**
- **No game rules**: no piece sequence, lock detection, coverage, clearing,
  losing, scoring or difficulty ramp. Tested — `no game rules have appeared`
  asserts score, level, phase, landing validity and every band value stay inert.
- **No quality-tier selection**, no `SurfaceView`/`Choreographer` escape hatch,
  no new dependency.
- **I did not merge to `main`.**
- **I did not run the app.** Still no GPU and no emulator in this container.
  Everything visual remains reasoned, not seen. See below.

---

## What I considered and rejected

**Uploading the core's `triangleIndices` directly** instead of deriving the
pattern in the shell. Rejected on sequencing: the index buffer is built in
`onSurfaceCreated`, before a `Simulation` exists to ask. Deriving it and
asserting exact equality gets the same guarantee without reordering GL setup
around a dependency it should not have.

**Detecting settle inside `:core-sim`.** That is where Stage 3 will want it, for
determinism and replay. Rejected for now precisely because building the lock
rule before the physics is felt means tuning it twice — which is the build
order's own stated reason for deferring it.

**Using `SimState.kineticEnergy` for the settle test.** It is the whole stack's
energy, so a settled piece would read as noisy whenever anything else in the
well was still moving. Derived per-body from `position - prevPosition` instead,
which the contract already publishes.

**Indexing body particles as `body * particlesPerBody`.** True today. The
contract does not promise it, and assuming it would be a silent misread the
first time the core compacts its arrays. Scanning `particleBody` costs a few
hundred comparisons once per tick.

**Raising `COMPRESSION_MAX_DARKEN` now that the gain moved.** Rejected: on the
measured range it does not bind, so changing it would alter nothing visible
while removing a rail. It is UX's call if the gain goes much higher.

**Running the benchmark on a background thread.** Rejected — the GL thread is
where the real solver runs, at the same priority, and nothing else is drawing
while it holds the thread. A background thread would measure a different
scheduling situation than the one the number is meant to describe.

---

## Things I am uneasy about

**1. Nothing here has rendered a frame, and this is now the second build in a
row where that is true.** Shader compilation, attribute binding, VAO state and
the integer attribute path remain unverified. My mitigation is unchanged in kind
— the topology, the vertex fill, the interpolation and the whole simulation seam
are pure functions with tests — but a black screen on first install is still the
single most expensive failure mode and `GlProgram` throwing with the driver log
attached is still the only breadcrumb. The install document names a black screen
as the one failure we most need reported.

**2. The gain is measured but not *seen*.** 4.0 is derived from a real
distribution rather than a fake one, which is a large improvement on Stage 1, but
"45% darkening at peak impact" is arithmetic, not a judgement that it looks
right. It may still be too strong or too weak on an OLED panel in a lit room. It
is one constant and the comment on it says so.

**3. The cadence number is mine, not UX's.** ~3.5 s per piece with 2.2 s of it
falling is a real feel decision that I made by measuring and then leaving alone.
I am reasonably confident leaving the physics untouched is right for this
milestone specifically, and less confident that 3.5 s is a good place to find
out.

**4. `docs/ux/` and `docs/security/` do not exist on this branch.**
`feat/app-shell` branched from `chore/build-foundation` before those merged to
`main`, so the specs my code cites in comments are not present here. I read them
from `origin/main` rather than merging `main` in, because you gave a precise
branch setup and merging trunk was outside it. Nothing is broken — no build step
reads them — but the branch is missing documents it references, and someone
should merge `main` before or during review rather than discovering it then.

**5. Four defects again, in code I had read.** Same pattern as Stage 1: three of
the four above were caught by writing a test that expected the code to be wrong,
not by reading it. The topology diagonal in particular I had read twice and
assessed as "same cells, fine" before writing the equality test that showed it
mattered. I do not have a better method than distrusting my own reading and
writing the assertion anyway.

---

## For the next agent

- `SquishToy` is written to be deleted. Do not extend it into a spawner or a
  lock rule; that work belongs in `:core-sim`.
- Before judging the compression look, run `CompressionRangeTest` — it prints
  the failure with the measured distribution in the message.
- The vertex format is still the thing Stage 3 grows. `VertexFill`, `BodyMesh`
  and `Shaders` are the three files involved, and `vEdge`/`vContact` are the
  hazard: swapped, they render and read wrong. One brightens, one darkens.
- The benchmark divisor is `SolverBenchmark.HOST_P50_MS`. If the solver changes,
  that number needs re-taking on the host or the ratio quietly stops meaning
  hardware.
