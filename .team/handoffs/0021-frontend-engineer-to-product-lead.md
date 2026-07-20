# 0021 — Frontend Engineer → Product Lead

Stage 3B gel shading was finished on `feat/gel-shading` but had **no open PR**,
so it was invisible in review and could not land. It now has one, described
honestly, review requested, and — after a base-branch CI fix — green. The
integration with Stage 3A is planned, not executed, on purpose.

## What I did

- **Opened PR #6** for `feat/gel-shading`:
  https://github.com/bramstoeller/gravitris/pull/6
  Base is **`feat/squish-toy`**, not `main` — verified: `feat/gel-shading` is a
  direct linear descendant of `feat/squish-toy`'s tip (that branch **plus
  exactly five commits**). The earlier handoff 0016 named
  `fix/render-footprint` as the branch point; that predates today's history
  rewrite, and `fix/render-footprint` has since folded into `feat/squish-toy`
  (its diff against squish-toy is empty), so squish-toy is now the correct,
  more-advanced base. squish-toy carries the softening, the contact-gap fix and
  ADR 0011's boundary extrusion that this shading depends on.
- **Described the PR honestly**: procedural gel/subsurface shading; the
  anticipation glow on `uBandFill`; ignition/hold/dissolve on
  `uBandClearProgress`; the whole-band feather; the OLED near-black dither.
  Stated the measured static cost (~178 ALU ops/fragment at the top tier) and
  stated plainly that the 2–4 ms wall-time is a projection and **nothing in the
  PR has rendered on a real GPU** — the client's phone is still the only
  appearance-and-performance instrument.
- **Verified the effect→uniform map against the shader before writing it**, not
  from memory. `uBandFill` → glow only (`Shaders.kt:297`); `uBandClearProgress`
  → flash (`:443`) + dissolve (`:488`). This is the thing that bit me this
  morning (handoff 0018, I misdescribed it to the Backend Engineer); I read the
  code this time.
- **Ran it through the correctness emulator** (`make screenshot`, DevOps'
  tooling from PR #4; software rendering via ANGLE/swangle — hardware GL is
  unavailable in this container). It **launches, the shader compiles, and the
  scene renders**: a translucent gel body with the identity grain and the debug
  band glowing through it, `shade:FULL band:DEBUG`. This is the first time a
  shader-compile black screen could be caught here instead of by the client.
  The on-screen FPS/ms under swangle are software-render artifacts and are not a
  performance claim (the readout itself says "not a verdict").
- **Requested review from the Backend Engineer** on the PR, with reasoning: the
  band-value contract is the one live shared surface, they own the producer and
  have measured it, and it is the seam that already produced one near-miss. Also
  stated on the PR that the frame-budget risk cannot be code-reviewed — it needs
  the phone — and that the Code Reviewer remains the merge gate for trunk.
- **Fixed the red CI** (commit `df8be00`, `ci: pass GITHUB_TOKEN to the gitleaks
  secret scan`). See below — it is base plumbing, not my shader.

Commits added on top of `feat/gel-shading` (`80ea334`): the CI fix, then this
handoff. Pushed to `origin/feat/gel-shading`.

## The CI failure was base plumbing, and I fixed it rather than wait

PR #6's first run went **red**, and I did not report done while it was red. I
read the log: the failure was the **Secret scan (CHK-7)** step, which gates the
whole job and runs before build/test — so my code was never even compiled by
CI. `gitleaks-action@v3` had a breaking change: it now requires `GITHUB_TOKEN`
to enumerate a PR's commits, and the workflow did not pass it.

The Backend Engineer had **already authored the exact fix** on `feat/mechanic`
(`d786e6a`). I applied the identical change to `feat/gel-shading` — same env
line, same comment — committed under my role, so my PR's CI can actually run.
No PAT, no secret added; it is the auto-provisioned job token.

**Flagging it because it touches CI (DevOps' territory):** this fix now lives on
three branches independently (mechanic, gel-shading, and wherever DevOps lands
it). They are identical, so it will collapse to one on merge, but the durable
home for it is `main` — once it is there, these copies become no-ops. Not a
divergence risk, just noted so nobody is surprised to see the same four lines
three times.

## Integration with the mechanic — planned, not executed

The Backend Engineer's Stage 3A on `feat/mechanic` produces the band values my
shader consumes. I confirmed against both branches that their `SimState`
publishes `bandFill`, `bandClearProgress`, `bandBottomY`, `bandHeight` — a 1:1
match for the shader's uniforms.

**Today the real values do NOT flow to the shader.** `GameRenderer.kt:450/452`
still uploads a debug sweep (`debugBandFill` / `debugClearProgress`) because
`SimState.bandFill` existed but nothing wrote it on my base. Integrating is a
merge of `feat/mechanic` into `feat/gel-shading` plus swapping those two lines
to `state.bandFill` / `state.bandClearProgress`. Two lines.

**I have deliberately not integrated**, per my brief: their mechanic (PR #5) is
still in flight and I will not chase a moving target. I sent the plan and two
questions — is Stage 3A frozen enough to integrate against, and who merges into
whom (my proposal: once they are stable, I merge their branch into mine, do the
swap on my side, screenshot, and land gel-shading on top of their line). **I
could not reach the Backend Engineer directly** — no agent by that name was
reachable from my session — so I routed the plan through you and asked for a
reachable handle. Please relay or connect us.

## What I deliberately did not do

- **Did not integrate the real band values.** The mechanic is still moving.
- **Did not merge gel-shading to `main` or to squish-toy.** It is stacked on
  squish-toy and needs Code Reviewer approval; and squish-toy itself is not yet
  on main.
- **Did not commit the emulator screenshot to the repo.** It is a build
  artifact (`build/emulator/screenshot.png` in a throwaway worktree). I viewed
  it and described it on the PR; it does not belong in history.
- **Did not retune anything.** The shading is unchanged from what was built; the
  only code change is the CI env line.

## Open questions / uneasy about

- **The frame cost is still projected, never measured.** ~178 ALU ops/frag and a
  2–4 ms guess. Every downstream budget rests on it, and only the client's phone
  can replace the guess with a reading. The emulator cannot — it is software.
- **The base chain is deep.** gel-shading → squish-toy (no PR, not on main). For
  this to reach the client, squish-toy needs its own path to trunk, or
  gel-shading lands via the mechanic integration. Worth a deliberate decision on
  the merge order rather than letting it happen by accident.
- **The CI fix on three branches** (above) — harmless but worth landing on
  `main` once so it stops being copied.

---

## Addendum — the integration was executed (mechanic froze, Product Lead approved)

After the plan above was written, the Backend Engineer froze `feat/mechanic`
and the Product Lead approved executing the integration now. Done:

- **Merged `feat/mechanic` into `feat/gel-shading`** (clean; the only overlap
  was the identical gitleaks CI fix, auto-resolved).
- **Swapped the debug driver for real coverage** (`feat(app): drive band glow
  from real coverage, drop the debug sweep`): `GameRenderer` now uploads
  `state.bandFill` / `state.bandClearProgress`; the `debugBandFill` /
  `debugClearProgress` stand-ins, their backing arrays, the `TWO_PI` /
  `NOT_CLEARING` / `CLEAR_ENVELOPE_SECONDS` / `IGNITION_DEBUG_PERIOD_SECONDS`
  constants and `Tunables.BAND_DEBUG_SWEEP_RATE` are removed (no dead code).
- **The readout honesty label now reads `band:LIVE`, not `band:DEBUG`.** That
  label exists so a screenshot cannot pass off a debug sweep as real coverage;
  with real values driving the glow it must — and now does — say LIVE.

**Verified on the correctness emulator (screenshot, swangle software render):**
the integrated build compiles, launches and renders — a stack of five soft
bodies each carrying the gel subsurface shading and identity grain, readout
`shade:FULL band:LIVE`. The anticipation glow is quiet because real coverage in
the sandbox is below the 40% dead zone — that is correct honest behaviour, not
a fault; the debug sweep used to force it bright precisely because real fill is
low. `make test` passes.

**Product Lead's three checks:**
1. **Geometry lines up.** The renderer extrudes the silhouette from
   `state.particleRadius` (`VertexFill.kt:96`), so it tracks whatever the core
   publishes (0.225 at lattice 5 → extent 2.25) by construction, not a hardcoded
   value. The screenshot's clean contact gaps between stacked pieces — no
   overlap, no float — corroborate it. Verified structurally and visually; not
   asserted as a hard number in a test.
2. **Stale trap dropped.** I did not carry handoff 0018's undamped-`fillRaw`
   assumption; the clear rule reads the damped published `bandFill`, and my
   guidance (fill crossing threshold is not a clear — read `bandClearProgress`
   leaving -1) still holds.
3. **Real range vs the unclamped feather.** Real `bandFill` is structurally
   bounded to 0..1 and the feather only convex-mixes two in-range values, so it
   cannot exceed 1; `bandClearProgress` is -1 or 0..1 and the shader gates the
   sentinel with `step(0.0, clearing)`. The clean render (no blown-out
   fragments) confirms no range issue surfaced.

**One tooling flake to flag to DevOps:** the *first* `make screenshot` captured
the launch-transition wallpaper (app mid-launch, first frame not yet drawn), not
the app. A manual capture a few seconds later showed the app rendering correctly,
and logcat confirmed no crash — the window was focused and the process alive.
The screenshot script likely needs a longer settle / first-frame wait before it
grabs the frame; otherwise it can emit a misleading "black/other" shot for a
build that is actually fine.

**PR #6 base retargeted to `feat/mechanic`** so the review shows the gel +
integration delta on top of the mechanic, not the whole mechanic diff.


---
*— **Frontend Engineer***
