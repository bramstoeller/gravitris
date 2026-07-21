# 0014. Pin the lattice at 4; the piece's material extent is the gameplay constant

Status: accepted
Date: 2026-07-21
Supersedes the startup quality-tier selection in ADR 0009 (§3, and the
particle-count half of §4) and its per-tier `clearThreshold` mitigation. ADR
0009's reference device (§1), dropped 2020 floor (§2), render-side scaling and
pinned substeps (§4) and frame budget (§5) all stand.

## Context

ADR 0009 chose one of three quality tiers at startup by measurement, defaulting
to medium (lattice 5), and scaled quality by particle count per tier. Two things
have changed since, and together they retire the tier mechanism.

**1. Tetrominoes are ~4× the particle density (ADR 0015), and only lattice 4
fits the reference device.** The device benchmark was measuring a single-block
pile and under-counting tetromino cost roughly 4×; it has been rewritten to
measure a real tetromino pile. Against that corrected number, on the Fairphone 6
model:

| lattice | ~particles (full 20×44 well) | host ms/step | device-est (12× derating) | 16.67 ms budget |
| ------- | ---------------------------- | ------------ | ------------------------- | --------------- |
| **4**   | ~1 664                       | 0.78         | **~9.4 ms**               | **under**       |
| 5       | ~3 100                       | 1.56         | ~18.8 ms                  | over            |

Lattice 5 is over budget with tetrominoes; lattice 4 is the only viable tier on
the device we can actually test. A runtime tier could therefore only ever serve
*faster-than-reference* devices we cannot verify.

**2. The tier mechanism leaks gameplay, and that leak was only ever half-fixed.**
A piece's material extent is `PIECE_WIDTH · lattice/(lattice-1)`, which varies
with the lattice: **2.40 at lattice 4, 2.25 at lattice 5, 2.16 at lattice 6**
(`SimConfig.spacing`). So the lattice tier changes piece size, pieces-per-row and
packing — the game itself — not just the render mesh. ADR 0009 papered over the
coverage-fill half of this with per-tier `clearThreshold` calibration, but the
piece-size/packing half remained, and a replay is only bit-identical within one
lattice (lattice is part of `SimConfig`; ADR 0006). Two players on
differently-benchmarked phones would play measurably different games.

**3. ADR 0013 already assumes this decision.** ADR 0013 (on main) reasons from
"particle count pinned" and rejects "shipping three subtly different games". It
was written against a lattice pin that was drafted on the `chore/architecture`
branch but never landed on `main` — so on main its supporting references dangle
and it openly contradicts ADR 0009's live tier system. This ADR is the pin ADR
0013 assumes, and it repairs those references (see "Reconciliation").

## Decision

**1. The lattice is pinned at 4. There is no runtime quality tier.** One lattice,
one set of tuned constants, no startup calibration branch, no per-tier index
buffer, compliance or threshold. Startup tier selection (ADR 0009 §3) is deleted
rather than corrected.

**2. The gameplay constant is the piece's material extent (`pieceExtent`, 2.40
world units).** With the lattice fixed, `pieceExtent` is automatically a single
value, so the "extent varies per lattice" leak is closed by construction — no
formula change is required today. **If the lattice is ever re-pinned at build
time (item 5), redefine `spacing = pieceExtent / lattice` so piece size stays
invariant across the re-pin.** That is the decoupling drafted (but not landed) on
`chore/architecture`; pinning makes it moot now and a one-line discipline later.

**3. `clearThreshold` and the overflow threshold are single constants**, not
per-tier calibrated values. ADR 0009's per-tier calibration and the QA
cross-tier-equivalence test it called for are both retired — there are no tiers
to equate.

**4. Runtime quality scaling stays render-side only** — resolution scale, shader
quality, effect density (ADR 0009 §4, unchanged, and ADR 0007). This is now the
*only* runtime quality lever, exactly as ADR 0013 requires.

**5. Weak hardware degrades via frame rate, never via the simulation (ADR
0013).** A device that cannot hold 60 ticks/s at lattice 4 drops frames and
lowers fps; the FrameDriver catches up without dilating wall-clock. The
documented fallback for a genuinely slower *reference* device is a **build-time
re-pin to lattice 3** (with tuning re-calibrated), not a runtime tier — recorded
so the option is known, not so it is built.

**6. Reference device, dropped 2020 floor, pinned substeps (8), and the frame
budget (ADR 0009 §1/§2/§4/§5) are unchanged.**

## Alternatives considered

**Keep ADR 0009's three tiers, now with the corrected tetromino benchmark** —
rejected. On the reference device only lattice 4 fits (lattice 5 is ~18.8 ms,
over budget), so tiers would exist solely to give *unverifiable* faster devices a
higher lattice — and they would pay for it with a per-device gameplay divergence
(piece size and packing) and replays that do not reproduce across devices. It is
a permanent per-shape calibration tax to support hardware we cannot test, on an
axis where only one value fits the hardware we can. ADR 0009 already rated this
the weakest part of the tier design; tetrominoes make it decisive.

**Keep tiers but land the `pieceExtent`-decoupling so the lattice stops affecting
gameplay** — rejected as more machinery than the problem is worth. Decoupling
removes the size leak but keeps the per-tier `clearThreshold`/compliance
calibration and per-tier determinism, to support a higher lattice that is over
budget on the reference device anyway. Decoupling is the right idea; with a
single viable tier there is nothing to decouple between.

**Ship a single fixed quality level tuned to the reference device** — this is now
chosen, and it is ADR 0009's own "honest simple option". ADR 0009 rejected it for
fear that genuinely weak hardware would be *unplayable*. ADR 0013 answers that
fear differently and on the client's explicit instruction — a weak device drops
frames and runs at a lower fps, it does not slow down or fail. The objection that
sank this option in ADR 0009 is resolved by ADR 0013, not ignored.

**Pin lattice 5 or 6 for higher fidelity** — rejected: both are over the frame
budget with tetrominoes on the reference device.

**Pin lattice 3 for maximum headroom** — rejected as the default: 9 particles per
cell (a 2×2 quad grid) is too coarse to read as deformation, and the whole
product is the deformation. Kept only as the build-time fallback in item 5.

## Consequences

**Easy.** No startup calibration branch, one index buffer, one `clearThreshold`,
one compliance value, no cross-tier QA equivalence test. The performance dial can
no longer change the game, because there is no dial. `pieceExtent` is a single
constant and determinism is one configuration. ADR 0013's "particle count
pinned" reference becomes true.

**Hard.** There is no simulation-side fallback for weak hardware: a device slower
than the reference device drops frames (ADR 0013), it does not drop fidelity.
That is the deliberate price of not shipping subtly different games, and it is
the client's stated preference ("frames skippen is prima… niet vertragen", ADR
0013). Reach below the reference device is delivered as lower fps, not lower
quality.

**Live with.** `pieceExtent = 2.40` and `lattice = 4` are now load-bearing
gameplay constants that several tuned values depend on — `clearThreshold`, well
width measured in pieces-per-row, the landing silhouette. Changing either
invalidates that tuning and is a build-time re-pin with re-calibration, the same
discipline substeps already carry (ADR 0003). Both should be commented as such
where they are defined.

**On the look at lattice 4.** Whether lattice 4 reads as "a commercial modern
game" is a *render* question, answered render-side (ADR 0009 §4, ADR 0007) — the
silhouette extrusion (ADR 0011) and the gel fragment shader carry most of the
perceived material quality, not raw mesh density. If it reads as too faceted, the
response is more draw-time subdivision or shader work, **not** more simulation
particles. The reference-device feel the client approved was already produced at
lattice 4 (the only viable tier), so this pins the approved squash feel; it does
not change it. Emulator validation of the look is owned by the Product Lead.

**Trigger to revisit.** A second, faster reference device, or a measured GPU cost
that leaves room for lattice 5 — both are build-time re-pins (item 2/5), not
runtime tiers.

## Reconciliation with existing ADRs

- **ADR 0009** — §3 (startup tier selection) and the particle-count half of §4,
  plus the per-tier `clearThreshold` mitigation in its Consequences, are
  superseded here. The rest stands; its status note is updated to say so.
- **ADR 0013** — its references to "particle count pinned (ADR 0011)" and "ADR
  0011's reasoning… three subtly different games was the wrong trade" were
  dangling on main (main's ADR 0011 is the silhouette ADR; the pinned-lattice ADR
  from `chore/architecture` never landed). This ADR is the pin they meant; those
  references are repaired to point here.
- **ADR 0011 (silhouette)** — untouched. It is unrelated to the lattice pin; the
  earlier confusion was only that an unmerged branch reused the number 0011 for a
  different decision.
