# 0022 — Architect → Product Lead: control-scheme redesign (ADR 0017)

Date: 2026-07-21 · Branch: `docs/adr-control-redesign` · Design only, no product code.

## What I did

The client rejected the round-2 positioning lockout after field-testing. I
designed the replacement — a **single controlled descent**: the piece falls
under real accelerating gravity from spawn, and the player steers left/right AND
rotates for the whole fall until it contacts material and settles. No phase where
control is taken away. This reverses ADR 0016's short-window design; the reversal
is recorded honestly so nobody re-derives the old constraint.

Deliverables (all on branch `docs/adr-control-redesign`):

- **`docs/adr/0017-single-controlled-descent-control-scheme.md`** — new, status
  accepted. Lifecycle, the ADR-0016 reversal, the lock/settle rule, the input
  contract, the difficulty analysis, the no-hard-drop decision, alternatives,
  consequences, and the migration surface.
- **`docs/contracts.md` §2** — rewritten to the single-descent input model.
  (It was also *stale*: §2 still documented the pre-ADR-0016 `hardDrop`
  `InputFrame`, never updated when ADR 0016 changed it. Now correct.)
- **`docs/adr/0016-...md`** — marked `superseded by 0017`; its rotation
  semantics explicitly survive.
- **`.team/decisions.md`** — 0017 added, 0016 struck through.

## What I deliberately did NOT do

- No implementation code — that is the separate dispatch this unblocks.
- Did not reintroduce a hard drop. Existing behaviour is no hard drop; the client
  didn't ask; it mildly conflicts with "let me move it more." Recorded as a
  reversible non-decision (a `hardDrop` field can be re-added additively later).
- Did not add input-suppresses-lock. Rejected in the ADR — it lets a player hold
  a piece forever by wiggling; the lock timeout is the honest bound.

## Agreement reached (not just discussed)

Both owners reviewed the contract as an artifact, against their real code, and
returned **AGREE-WITH-CHANGES**. Every change is folded into ADR 0017 / §2:

- **Backend Engineer** (lifecycle, `:core-sim`): confirmed gravity-from-spawn is
  literally the benchmark's existing path, and that `hasSettled`'s contact gate
  already prevents a mid-air lock. Required changes now in the ADR: `doSpawn`
  must seed `touchedTicks = -1` (sentinel, not 0); **delete `setBodyWeightless`
  and the now-constant `gravityScale`** in the same change (dead code);
  correction that ADR 0016's `invMass=0` prose was already false (mechanism is
  `gravityScale`). Flagged a new interaction for QA: a full-weight piece can be
  shoved sideways into the stack for the whole fall (bounded, but new).
- **Frontend Engineer** (gesture contract, `:app`): confirmed the recognizer
  change is behaviourally just deleting the always-emit `drop`, that removing the
  positioning `SimState` fields is contained to `PositioningUrgency`/`UrgencyBar`,
  and required three §2 clarifications (now in): intent is per-tick and discarded
  when no piece is active (never buffered); intents are requests the core clamps
  against collision; vertical drag is ignored (no soft-drop). Recognizer KDoc is
  contract-bearing and must be rewritten as part of the change; the drop-only
  test is *repurposed* into "a drag release emits nothing," not deleted.

## Contracts each engineer must satisfy (implementation dispatch)

**Backend Engineer — `:core-sim`** (contract: `docs/adr/0017`, `docs/contracts.md` §2):
- `InputFrame` → `{ dragX: Float, rotate: Boolean }`; remove `drop`.
- `doSpawn`: live under gravity from spawn, no positioning, seed `stillTicks=0`,
  `touchedTicks=-1`.
- `applyInput`: no phase gate — apply `dragX` and `rotate` every tick a piece is
  active; discard when `activePieceBody < 0`.
- Lock: reuse `hasSettled` → `lockActivePiece` unchanged.
- Delete positioning fields/methods, `PiecePhase`, `SimState.activePiecePhase`/
  `positioningTicksRemaining`/`positioningWindowTicks`, `MechanicTuning.
  positioningTicks`, `setBodyWeightless`, `gravityScale`, `addPositioningPiece`.
- Reword `slamActivePiece` doc.

**Frontend Engineer — `:app`** (contract: `docs/contracts.md` §2, `docs/adr/0017`):
- `GestureRecognizer`: delete the always-emit `drop`; keep tap→rotate, debounce,
  slop, multi-touch, timestamped samples, 1:1 mapping; rewrite the KDoc.
- `PlayerIntent`: remove `requestDrop`/`drop`.
- Remove `PositioningUrgency`, `gl/UrgencyBar`, the urgency-bar wiring in
  `GameRenderer`, `Tunables.POSITIONING_BAR_*`.

**QA Engineer** (coordinate with both): rewrite positioning/`drop` fixtures;
*add* the "drag release emits nothing" assertion; feel-check the sideways-shove-
into-the-stack interaction and whether the game plays too easy without the window.

## Open questions / uneasy about

- **Difficulty.** Removing the window plus steer-during-fall makes placement
  easier, all else equal. The ADR argues the accelerating descent is the natural
  replacement budget and `clearThreshold` (0.80) is the master lever — but this
  needs a playtest at the next demo. If it plays too easy, the answer is the
  ramps/threshold, not a reinstated lockout. **This is the one thing worth
  putting in front of the client at the milestone.**
- The canonical model lives on `main` (ADR 0016 / round-2). The working tree I
  was dispatched into was on `docs/review-pr7`, which predates round-2 — I
  branched from `main` so the design is against the real current code.
