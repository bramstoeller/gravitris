# 0003 — Architect → Product Lead

Date: 2026-07-20
Branch: `chore/architecture` (commit `f734203`) — **not merged to main**
Gate: this is the material Gate 2 should be approved against

## Headline

**The dominant risk is retired. "Spongy" is affordable.**

I built and measured a spike before designing anything. Solver cost is
independent of stiffness across a 1000x range — the thing that constrains
softness is the substep count, and the substep count we need (8) is cheap. The
fear that blocks would have to be stiffened, and that "spongy" would quietly
become "slightly springy", **does not survive measurement**.

The risk has moved, though. On the numbers, **the largest unmeasured cost in this
product is now the procedural fragment shader, not the solver** — a reversal of
the project's founding assumption. The UX Designer should know that.

## What I did

- **Spike** at `/work/spike/solver-budget/` — an XPBD solver, benchmark harness
  and coverage-band implementation in Kotlin. Clearly marked non-production, with
  a README saying so. Installed JDK 21 + Kotlin 2.1.21 ad-hoc; the container had
  no JVM toolchain at all. Results: `results-host.txt`.
- **ADRs 0001–0010** in `/work/docs/adr/`, indexed in `.team/decisions.md`.
- **`/work/docs/architecture.md`** — the system on one page.
- **`/work/docs/contracts.md`** — the `:core-sim` ↔ `:app` seam, precise enough
  for backend and frontend to work in parallel from day one.
- **`/work/docs/build-order.md`** — sequencing, with the earliest feel-test moment.
- Staffing recommendation appended to `.team/staffing.md`.
- Three blockers in `.team/blockers.md`.

## The measured numbers

Host: x86-64, HotSpot 21, single-threaded, pinned. **Not on-device.**

| Finding | Result |
| ------- | ------ |
| Solver cost | `0.49 ms × (particles/960) × (substeps/8)` — linear, no surprises |
| Substep floor | **8.** Below it a settled pile jitters or creeps |
| Is "spongy" affordable? | **Yes** — cost is independent of compliance across 1e-8…1e-5 |
| Stability vs piece mass | stable to 8x mass — the difficulty ramp is safe |
| SoA vs AoS | **1–3%** at our scale, 1.15x only at 138k particles |
| Coverage bands | 0.006–0.018 ms — **0.05% of a frame** |
| Allocation | **0 bytes/frame** |
| Determinism | bit-identical replay |

Estimated on the Fairphone 6 at the default tier: **2.5–5.9 ms** solver against a
16.67 ms frame. Comfortable.

## Answers to your three messages

**1. The 2020 floor.** Agreed, drop it — and the brief has already been updated,
but **success criterion 1 still contradicts the new Performance target section**.
That needs fixing (blocker).

**I do push back on one detail of your quality-scaling instinct.** You proposed
degrading "solver iterations, particle count, or substep rate". Measurement says
two of those three are unsafe:

- **Substeps below 8 make a settled stack jitter.** Scaling them to hold framerate
  trades a framerate problem for something players read as a *bug*. Substeps are a
  correctness floor, not a quality dial.
- **Particle count cannot change mid-run** — bodies already exist in the well;
  re-meshing them would visibly pop.

So ADR 0009 splits it: **simulation quality is chosen once at startup** by a short
calibration (using the spike's reference config), and **runtime scaling is
render-side only** — resolution scale, shader quality, effect density. Same
graceful-degradation goal, applied to the parameters that can actually take it.

**2. The 120Hz display.** Fixed 60Hz simulation, render interpolated, and we
**explicitly request 60Hz** — on an adaptive panel, not asking is itself a
decision. I rejected 120Hz rendering, and the reason is not the solver (which fits
either budget) but the **fragment shader**, which is the expensive per-pixel work
and would double. The decision is left cheaply reversible: because rendering is
already interpolated, going to 120Hz later is a change to one `setFrameRate` call.
You were right that this and determinism are one decision — ADR 0006.

**3. NDK vs pure Kotlin.** **Pure Kotlin. No native libraries at all.** Your
reading of "native Kotlin" was correct and the Security Engineer's assumption does
not apply. Three reasons, in order of weight: the measured budget does not need
native speed; the SoA/cache benefit that would justify C++ is only 1–3% at our
scale; and pure Kotlin gives **bit-identical determinism across devices** (strict
IEEE-754 since Java 17, correctly-rounded `sqrt`), which is what QA's entire
replay-test strategy rests on and which `-ffast-math` would destroy.

**Revised APK figure: ~2–4 MB.** No ABI splits exist, universal packaging is free,
and the ~5MB target holds with room. It also sidesteps the Android 15+ 16KB
page-size requirement entirely. ADR 0002 records the trigger for revisiting
(sim >8 ms on device at Milestone 1 → drop a tier, then `arm64-v8a`-only NDK at a
re-baselined ~3–4 MB).

Noted and folded into ADR 0010: no dependency may contribute `INTERNET` to the
merged manifest (we need no third-party libraries at all), and persisted state is
parsed defensively with `allowBackup=false` and no cloud fallback.

## The two genuinely open questions

**Losing condition** (ADR 0005) — my answer: **the game ends when there is no room
to spawn the next piece, and the stack has been given time to prove it.** If the
spawn region is blocked, the game does *not* end; it enters an overflow state
where the intruding material glows and pulses, and a ~1.5s grace window lets the
stack settle. If it settles back, play resumes with no penalty. A transient bulge
can never kill you, and the physics settling back down is rewarded rather than
punished. It reuses the band machinery and the glow language already specified, so
it costs nothing new.

**Coverage threshold** — runtime-tunable at negligible cost (ADR 0004), so ~90% is
a dial to turn at the demo, not a rebuild. Note the coupling: **changing band
resolution invalidates the tuned threshold.**

## I checked my contracts against the UX specs, and found six real defects

I read the UX Designer's specs against my rendering contract before landing this.
It found six things wrong with my first draft. All are now fixed in the ADRs and
`contracts.md`; I record them because they are the difference between the
engineers fitting on the first try and not.

1. **Reduced motion was specified wrongly, and it was the serious one.** I had it
   raise solver damping. That would damp the *primary squash on impact* — which
   UX explicitly requires to stay unchanged, because it is the core weight cue —
   and, worse, it would make an **accessibility setting change physics, game
   outcomes and replay tests**. Accessibility must never change what happens, only
   how it is drawn. It is now a render-layer temporal filter; `SimConfig` carries
   no accessibility fields.
2. **My overflow warning contradicted the band-glow rule.** I had the intruding
   *material* glow. UX makes it the strongest legibility rule in the game that
   glow belongs to a **zone, never a body** — that is what teaches the clearing
   rule without a tutorial. The warning is now a property of the spawn zone, in
   the reserved warn colour, below the 3Hz flash ceiling.
3. **A single `vHue` was not enough.** The palette varies saturation, lightness
   and grain per piece independently, and the alternating lightness is the
   colour-vision-deficiency backup cue. Now a piece-archetype index into a palette
   uniform block.
4. **One `vEdge` cannot serve both edges.** Free surface *brightens* (rim light);
   contact with a neighbour *darkens* (seam/AO) — and that seam outranks the
   lightness ladder as the small-screen legibility cue. Split into `vEdge` and
   `vContact`, the latter free from the contact solve.
5. **Band fill alone cannot drive the clear animation** — a band at 1.0 is
   indistinguishable from one mid-dissolve. Added a per-band clear-envelope array.
6. **The landing silhouette needed more than one float** — it draws across the
   piece's projected horizontal extent, needs a validity flag, and has a
   documented range-band fallback. Now `{yLow, yHigh, xMin, xMax, valid}`, which
   costs nothing now and avoids an interface change mid-prototype.

**And one defect the UX check surfaced in my own quality-tier design:** coarser
lattices stamp larger particles into the coverage bitmap, so **the same pile reads
as a different fill percentage per tier** — meaning the startup *performance* tier
would silently have become a *gameplay* difference. The clear and overflow
thresholds must be calibrated per tier. Fixed in ADR 0004 and 0009, with a QA test
to assert equivalent clear decisions across tiers.

Two things confirmed back to UX: **no bloom / no HDR post-process** (the ignition
flash reads from the emissive blend alone), and band height is `wellHeight / 20`.
One question returned to them: whether the solver's own impact propagation reads
legibly down the stack, or whether a shader-side impact wave is needed — that
needs no new simulation output either way.

## Staffing, blunt

**Two builders, a designer, a tester, a build engineer.** 1 backend (`:core-sim`),
1 frontend (`:app` — the biggest job and now the riskiest), 1 UX (engaged), 1 QA,
1 DevOps (small scope — no store machinery), 1 code reviewer standing.

**Not staffed: data-engineer (0)** — there is no data, just three values in
`SharedPreferences`; staffing for that would be padding. **Tech-writer (0 for
now)** — with the store deferred the audience is the client, so a README and
sideload instructions suffice. **Security-engineer (0 further)** — re-engage once
at release. **No second backend/frontend** — one narrow contract between them; a
second head would cost more in coordination than it returns.

## Build order — the bit you asked for

**Milestone 1 needs no clearing rule, no losing condition, no scoring, and no art
direction.** It needs a solver, a renderer that draws triangles, touch input, and
a build. That is why it can be early:

- **Stage 0** DevOps build skeleton — the only true bottleneck
- **Stage 1** three parallel tracks: backend physics ‖ renderer+input ‖ UX spec
- **Stage 2** 🎯 **MILESTONE 1 "Squish Toy"** — one piece falls into an empty
  well, you drag it, drop it, feel it squash, with impact haptics. Flat colours.
  **This is where the client holds the phone and says whether it feels heavy** —
  and where we run the reference benchmark and close the derating blocker.
- Stages 3–5: mechanic, then game, then completion.

Full detail in `/work/docs/build-order.md`.

## What I deliberately did **not** do

- **No implementation.** Spike only, and it is marked as such.
- **No UI, layout or visual language** — UX Designer's, in parallel.
- **No CI, packaging or Gradle setup** — DevOps'. I know you are holding them on
  this handoff; ADR 0008 and 0010 specify what they need.
- **No scoring formula.** The brief's fourth open question is a design decision,
  not an architectural one. Flagged in ADR 0005 so it is not lost.
- **No abstraction for a second game mode, level format, or renderer.** No plugin
  system for tuning parameters. No Kotlin Multiplatform "just in case" — the port
  path is explicitly closed.
- **No caching layer, queue, database, background service or third-party SDK.**
  Written into `architecture.md` as deliberate absences so nobody adds them by drift.
- **No solver multi-threading.** Recorded as rejected in ADR 0009 for the same reason.

## Considered and rejected (the short list — full reasoning in each ADR)

- **Shape matching** — cheaper and stable, but pulls bodies back to rest shape,
  which is exactly what a settled stack must not do. It would fight the mechanic.
- **Pressure/gas model** — lovely bulge, but a shell creases under stack load and
  stacks of pressurised shells are notoriously jittery.
- **FEM** — correct, and too much for iteration one.
- **Summed particle areas for coverage** — the obvious first idea; double-counts
  overlaps and reads a ring around a void as full, which is backwards for a
  mechanic about squeezing into gaps.
- **GPU coverage with readback** — a pipeline stall costs more than the whole CPU
  algorithm.
- **Fixed top-out line** (with or without grace) — the unfairness you identified;
  also arbitrary chrome in a game that removed chrome.
- **Block-out with no warning** — most physically honest, but gives the player no
  warning and "overlap" is fuzzy for soft bodies.
- **Simulating at 120Hz** — halves the budget and doubles solver cost at once.
- **Five modules instead of two** — conventional, and would prevent nothing here.
- **Defensive copies of sim state** — 90 KB/s of garbage, against a design that
  measures 0 bytes/frame.

## What I am uneasy about

1. **The derating factor is an estimate, not a measurement, and the band is
   wide.** 3–7x is a factor of two between "comfortable" and "tight" at the high
   tier. Everything downstream inherits that uncertainty. It closes at Milestone 1
   and not before.
2. **GPU cost is completely unmeasured** and is now the top risk. I have no way to
   touch it from here.
3. **My spike had three bugs that produced convincing but wrong physics
   conclusions** before I caught them — bodies seeded overlapping (twice, the
   second time via a "fix"), friction measuring penetration after resolving it, and
   a per-frame allocation. The first made *more* substeps look *less* stable, which
   read exactly like a solver problem and was a setup problem. I fixed them and
   re-ran, and the numbers above are from the corrected run. I flag it because the
   real implementation will hit the same class of bug, and because it is the reason
   I would not want anyone treating these numbers as more precise than they are.
   Run-to-run variance is ~±7%.
4. **The stability results come from a pile ~4 units wide and ~46 tall, not a
   filled 10×20 well.** That is a *harder* compression case, so the substep floor
   is conservative — but a wide well produces more simultaneous contacts per body,
   and that configuration is untested. Worth checking at Milestone 1.
5. **The friction model makes a settled pile slightly stickier than reality.**
   Good for stability, possibly bad for the feel of material flowing into gaps —
   which is the mechanic. A tuning risk to watch, not a known defect.
6. **`SimState` exposes raw arrays, read-only by convention only.** Deliberate, to
   hold allocation at zero — but the compiler will not stop the renderer writing to
   simulation state. That needs code review, and I have said so in ADR 0008 rather
   than pretend it is safe.
7. **The determinism rules are invisible constraints.** No transcendental functions
   in the core, no concurrency in the constraint solve. A new engineer will violate
   these innocently. They need to live in the module's own docs, not only in an ADR.

## Open questions for you

1. **Success criterion 1 contradicts the revised Performance target section** —
   needs rewording to name the reference device. Yours to make.
2. **Does the brief's quality-scaling wording get updated** to match ADR 0009, or
   do you overrule my pushback? Either is fine; I want it decided.
3. **The UX Designer should confirm the *revised* varying set in ADR 0007** (see
   the corrections above) and answer the impact-wave question. Adding a varying
   later means touching the vertex format, the buffer fill and both shaders.
4. **Scoring formula** is still unanswered and is a design decision.
5. **Grace-window stalling**: a player could hover near overflow and repeatedly earn
   grace windows. Skilful tension or exploitable? A playtest question.

## ⚠ A git hazard you need to know about before merging

**Parallel agents are sharing one working tree, and it bit me.** I created
`chore/architecture` and started work; partway through, another agent checked out
`chore/ux-spec` in the same tree, so **three of my commits landed on
`chore/ux-spec` instead of my own branch.** I discovered it at the end.

What I did about it: created a proper worktree at `/work/.worktrees/architecture`
and cherry-picked my three commits onto `chore/architecture`, where they now sit
cleanly. I resolved the conflicts so that this branch carries **only my own**
journal and blocker entries, not other agents'.

What I did **not** do: rewrite `chore/ux-spec`. It is not my branch, another agent
may be building on it, and rewriting published history is forbidden. **So my three
architecture commits currently exist on both branches** (`f734203`/`c863a6b`/
`5dae9f0` on `chore/ux-spec`, and `0771610`/`25c6c7d`/`4390f7d` here).

**What this means for you:** merging both branches to main will present the same
content twice. Git will usually resolve it silently since the changes are
identical, but the `.team/` files may conflict. Merge `chore/architecture` **first**,
then `chore/ux-spec` — and expect to resolve `.team/blockers.md` and
`.team/journal.md` by keeping both sides' rows.

**The underlying process problem is worth fixing**: CLAUDE.md already says parallel
agents should use worktrees, and this is what happens when they do not. I would
make that mandatory rather than advisory before the next parallel dispatch.

## Next dispatch

DevOps is unblocked. They need ADR 0008 (two modules, the `:core-sim` no-Android
build check) and ADR 0010 (`minSdk 29` / `targetSdk 36`, universal signed APK, no
native libs). The build check should be written in Stage 0 — if it is not, the
boundary that makes the whole test strategy work will erode within a week.
