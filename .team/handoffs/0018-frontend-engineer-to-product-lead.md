# 0018 — Frontend Engineer → Product Lead

Follow-up to 0017. The `uBandFill` contract is settled with the Backend
Engineer. **No code changed** — the finding is that no code needed to change,
and why that was not obvious.

## The headline: I misdescribed my own code, and it nearly caused a fix

I asked the Backend Engineer four questions about `uBandFill`. Question 3 was
*"when does it latch during the clear? The ignition flash and the dissolve both
key off it."*

**That was false about my own shader.** They measured their side properly,
confirmed `bandFill` never latches and *does* halve on the removal tick, and
correctly concluded my flash would re-trigger. They were one step from writing
a latch into `feat/mechanic` to fix it.

I then read my own shader instead of recalling it. `Shaders.kt:443` — the flash
reads `uBandClearProgress`. `:488` — so does the dissolve. `uBandFill` appears
in exactly one place, `bandFillAt()` at `:297`, the anticipation glow. It has
never driven ignition or dissolve. It is also tested, including the exact
failure predicted: `ShadersTest:211` asserts a band at `-1` must not flash, plus
zero at progress 0, 0.30 and 0.75.

So the contract we agreed — **`uBandFill` drives anticipation glow only,
`uBandClearProgress` drives ignition/hold/dissolve** — is what already ships.
It goes into ADR 0004 as ratifying existing behaviour, not as work owed.

**This is calibration finding six, and it is a new kind.** The first five were
defects in code I had read and believed correct. This one is a defect in my
*report about* code — and that is worse, because reports are what other people
build on. A wrong line of code is caught by a test; a wrong sentence to a
teammate is caught by nothing. The rule I take from it: **when a teammate is
going to act on a statement about my code, read the code before making the
statement.** Their instinct to go measure rather than answer from memory is the
only reason this cost a message instead of a branch.

## The `uBandFill` contract, settled

- **Per band**, one value per band, `set / (bandColumns * bandRows)` = /160.
  My whole-band feather was right; the per-row alternative had nothing to read.
- **Cannot exceed 1 — no clamp, and my unclamped feather stays unclamped.**
  Structurally bounded: the bitmap measures *span*, not area, so overlapping
  particles cannot double-count, and the damping is convex interpolation
  between two values already in 0..1. The *reason* matters more than the bound;
  it is going in the ADR next to it.
- **Per-band uniform array sampled per fragment.** Matches what ships; the one
  draw call is safe. `GameRenderer:525` already asserts
  `config.bandCount == BAND_COUNT` against the shader's baked-in array length,
  because out-of-range GLSL indexing is UB. When `SimState.bandCount` lands on a
  shared branch I will point that assert at the state rather than a
  default-constructed `SimConfig`.

## Two traps recorded for whoever tests this

1. **`SimState.bandFill` is *damped*; the clear rule fires on an undamped
   `fillRaw` that is not published.** So the glow lags the decision by a few
   ticks — deliberately, because raw fill spikes on a heavy landing and
   undamped it would flash the well amber on every hard drop, teaching a rule
   that is not the rule. **Never assert "glow crossed threshold ⇒ clear fired"
   against `uBandFill`.** It will be off by a frame or two and will look like a
   race. To detect a clear, read `bandClearProgress` leaving `-1`.
   There is no QA Engineer running; this would otherwise evaporate into a flaky
   test nobody can explain later.
2. **`uBandInvHeight` is a reciprocal and its name has to say so.** Settled the
   `docs/contracts.md` naming discrepancy in favour of the shader's names —
   `uBandClearProgress` over `uBandClear` (it is a `-1` sentinel plus a `0..1`
   envelope, not a flag), and `uBandInvHeight` over `uBandHeight`. The second is
   a correctness trap, not a preference: the shader *multiplies* by it, so
   anyone "correcting" the name and uploading `bandHeight` scales every band
   lookup by height² — silently, no compile error, no assertion. Default
   `wellHeight/bandCount` is `20/20 = 1.0`, so **it stays invisible until
   someone changes well geometry**, which ADR 0010 does at runtime from insets.
   Same shape as my four PR #2 findings: a name that does not carry the
   number's meaning.

## PR #3 status — red, not mine, watched

`gh pr checks 3`: `build-and-test` **fail**. I read the log rather than assuming:
it fails at *configuration* time on `Dependency verification failed for
configuration 'classpath'` — 13 artifacts, all Gradle plugin classpath
(guava, slf4j, errorprone). No `:app` or `:core-sim` code is compiled before it
dies. That is the base-branch cold-cache issue the DevOps Engineer is fixing
against CI, not a defect in this PR.

**I am not treating it as done.** When their fix lands, my checks should go
green on their own; if they do not, that is a finding and it becomes mine. No
review comments on PR #3 yet — nothing unanswered.

PR #3 still contains the known launch crash and must not merge in that state.

## What I deliberately did not do

- **Did not change the shader.** It was already correct. Changing working,
  tested code to match a conclusion drawn from my own faulty description would
  have been the actual defect.
- Did not pull `feat/core-sim` into `feat/gel-shading` to consume the new
  `bandCount` — that branch is held by a running agent and is mid-review.
- Did not rename `initialPieceMass`; agreed with Backend it is a cross-branch
  conflict for zero behaviour change. **Backlog item requested.**

## Open questions

- The gel shading frame cost is still **estimated at 2–4 ms and never
  measured** — no GPU in this container. Every downstream budget number rests
  on it.
- Haptics: noted your decision to keep the amplitude path. The readout still
  says `no amp control` on the fallback. Stated plainly for the record: **our
  only test device cannot demonstrate the feature we argued hardest for**, so
  that capability is unverified on real hardware, not verified-and-working.

---
*— **Frontend Engineer***
