# 0001 — UX Designer → Product Lead

## What I did

Wrote the full UX spec set for iteration one under `/work/docs/ux/` on
branch `chore/ux-spec` (not merged — no commits on `main` touched).

Files:

- `README.md` — reading-order index.
- `flows.md` — three journeys (first-ever session, habitual replay loop,
  mid-play interruption), with drop-out risk points marked inline.
- `ia.md` — five screens, navigation graph, back-button semantics per
  screen, and the "quitting is scored, not discarded" rule.
- `band-glow.md` — the core deliverable. Fill%→visual-response curve
  (deliberately front-loaded: nothing below 40%, acceleration in the last
  10%), additive-emissive rendering approach that never overwrites piece
  hue, the cross-piece-continuity mechanism that makes the glow read as a
  property of a zone rather than a piece, and the teaching-without-a-
  tutorial argument.
- `piece-identity.md` — six-hue CVD-conscious palette, a reserved 15°–65°
  amber band that no piece may use (kept exclusive to band-glow), plus
  lightness-ladder and grain-scale backup cues.
- `gestures.md` — concrete drag/tap/swipe-down thresholds (touch slop 8dp,
  1:1 drag mapping, hard-drop at 1000dp/s within a ±25° cone, tap gated on
  displacement only, no duration gate).
- `landing-silhouette.md` — a deliberately soft/uncertain projection (not a
  rigid ghost), with a cheap heightfield-heuristic computation approach and
  explicit failure/fallback states.
- `feel-feedback.md` — haptic energy formula and Android amplitude mapping,
  screen-shake amplitude/duration with a hard 10dp ceiling, and the full
  band-clear timeline (ignition → hold → dissolve → physics drop, ~800ms
  minimum/1400ms max watch window before control resumes, does not shrink
  with difficulty).
- `tokens.md` — colour, type, spacing (8 steps), radii, two shadow steps,
  three UI-chrome motion durations. Deliberately does not duplicate the
  gameplay timing numbers that live in `band-glow.md`/`feel-feedback.md`.
- `accessibility.md` — reduced motion specified as a table of exactly what
  changes and what doesn't (screen shake off, jiggle damped 70%, primary
  squash untouched, glow pulse rate floored, ignition flash untouched), a
  general 3Hz flash ceiling independent of the toggle, contrast/target-size/
  focus-order baseline, and the CVD verification status.
- `screens/title.md`, `screens/playing.md`, `screens/paused.md`,
  `screens/game-over.md`, `screens/settings.md` — layout, elements, and all
  required states (empty/loading/populated-sparse/populated-dense/error;
  offline and permission-denied are both explicitly N/A since the app makes
  no network calls and needs no runtime permissions).

## What I deliberately did not do

- **No tutorial screen or onboarding overlay** — per the brief, this is a
  design constraint on the mechanic (band-glow.md), not something to add.
- **No sound design** beyond a stub Settings toggle and a note that impact
  events are where audio would hook in — out of scope per the brief.
- **No settings beyond Reduced Motion / Haptics / Sound** — no coverage-
  threshold tuner, difficulty selector, palette choice, or account/reset
  options exposed to the player. Those are either not player-facing
  decisions (threshold tuning is a build-time/QA concern) or don't exist
  yet (accounts).
- **No losing-condition UI** — the brief flags the losing condition itself
  as unsolved (a fixed top-out line is unfair against a sagging stack). I
  reserved a `color-warn` token for a possible future "approaching top-out"
  indicator but did not design a screen element around it — that would be
  designing on top of an open architecture question.
- **No literal percentage readout anywhere** — already rejected by the
  client; I did not reopen it, including in the debug frame-time readout
  (which shows fps/frame-time only, nothing about band fill).
- **No landscape/tablet layout** — portrait only, per the brief.

## What I considered and rejected

- **Reusing Okabe-Ito/IBM palettes directly for piece hues** — rejected.
  Both were designed for flat 2D swatches at fixed lightness; this game's
  pieces are lit with rim-light/subsurface/AO gradients that vary lightness
  continuously across a single piece's surface, so a palette that leans on
  lightness *and* hue to separate two colours (as both source sets
  sometimes do) would collapse under this game's shading. I hand-spaced six
  hues for hue-angle separation alone instead, using those palettes only as
  a sanity check. Written up in full in `piece-identity.md`.
- **A crisp rigid-ghost landing silhouette** — rejected as dishonest given
  squash is genuinely uncertain; specified a soft footprint + single
  top-height line instead, with an explicit fallback to an even vaguer
  height-range band if playtesting shows the single line overpromises.
- **Duration-gated tap detection** — rejected in favour of slop-only,
  because gating tap on both slop and duration creates an ambiguous
  "hesitant tap" case (slow, small movement, no long-press action exists to
  justify penalizing it). Slop alone is simpler and matches "keep it
  simple."
- **A second rotation direction** (tap left/right half of screen) —
  rejected for now; it reintroduces exactly the gesture-discrimination risk
  the tap-to-rotate decision exists to avoid. Deferred, not designed.
- **"Quit to Title" as a separate, unscored exit from Paused** — rejected in
  favour of a single rule: every run ends at Game Over, scored, whether by
  topping out or by choosing "End Run." Two different exit behaviours
  depending on how a run ended seemed more confusing than one consistent
  rule, even though it means "quitting" always shows a score screen.
- **Settings reachable from Game Over** — rejected as an unnecessary third
  entry point; Title and Paused already cover every moment a player
  plausibly wants to change a setting, and Game Over's job is narrowly
  "look at score, then replay or leave."
- **A spinner/loading screen for cold start** — rejected in favour of
  showing the static logo immediately and fading in the rest once ready,
  given the brief's note that first-run shader compilation is a real,
  probably sub-second cost.

## Open questions

For the Architect:

1. **Band definition** — what is the actual band height, and at what rate
   is per-band fill available to the renderer? `band-glow.md` assumes a
   scalar-per-band feed at a few Hz; if that's wrong, the ramp timings need
   to move from "visual time" to "physics ticks."
2. **Quiescence/debounce before confirming a clear** — a band's fill can
   spike momentarily during an impact bounce and settle back below
   threshold. `feel-feedback.md` assumes the clear sequence only starts
   after a genuine settle; I haven't specified how that's detected because
   it depends on what the solver already tracks.
3. **Cheap heightfield query for the landing silhouette** — needs a
   top-surface sample against the existing stack, updatable at ~15–20Hz
   during drag, without running a soft-body pre-simulation. Please confirm
   feasibility; if not affordable at all, the silhouette needs a cheaper
   fallback (possibly just "column height" with no compression estimate).
4. **Impact propagation legibility** — does the solver's own constraint
   propagation read clearly at 60fps as a downward ripple, or does it need
   an explicit shader-side embellishment on top? Flagged, not assumed.
5. **No screen-space bloom/HDR post-process is assumed anywhere** in this
   spec set (band-glow uses additive emissive only, faked in the material
   shader). Please confirm this is the right assumption given the solver's
   share of the frame budget.
6. **How many piece archetypes (shapes) actually exist?** `piece-identity.md`
   ships six hues and a documented rule for extending safely, but I don't
   know if six is the right number.

For the Product Lead / client:

7. **No reaction-time accessibility accommodation is specified.** Reduced
   motion touches camera shake and jiggle, not fall speed — the difficulty
   curve has no slower-speed setting. The brief's two accessibility asks
   don't mention this; I'm flagging it as a gap rather than deciding
   whether it's in scope.
8. **CVD palette is verified on paper only.** No CVD simulator was available
   in this environment; the six hues are checked against published
   Okabe-Ito/IBM research and hand-spaced for hue-angle separation, with
   two pairs flagged as higher-risk and covered by backup cues (lightness
   ladder, grain scale). This needs a real simulator pass (Android's
   "Simulate color space," or Coblis on an actual rendered, lit, glowing
   screenshot) before the release gate — written up as a QA action item in
   both `piece-identity.md` and `accessibility.md`. This is the one hard
   accessibility requirement in the brief that I could not fully close
   myself, and I'd rather say so than imply it's done.
9. **The prototype's frame-time readout** (`screens/playing.md`) is
   specified as visible from milestone one per the brief, with a note that
   it should probably become toggleable/removable by the "Game" stage. I
   didn't decide when/how it gets hidden for the store-ready release —
   that's a product decision once the performance question is actually
   answered.

## What I'm uneasy about

The **landing silhouette is the item most likely to fight the physics**. If
squash turns out highly non-uniform in practice (tipping, uneven load
distribution across an irregular surface), a single "estimated top height"
line could be wrong often enough to feel untrustworthy rather than helpful —
which would undercut the controls' whole "direct manipulation is imprecise,
but the silhouette mitigates it" argument. I've written the fallback
(vaguer height-range band instead of a line) into the spec, but whether the
single-line version is even worth building first, versus starting from the
vaguer version, is a real judgment call that only playtesting at the
prototype milestone can settle. I'd rather this get tested early than
polished on the assumption it works.

Second, smaller unease: the **first-session teaching moment (band-glow.md's
"highest risk moment") is entirely dependent on tuning I don't control** —
piece size, band height, and early fall speed all have to conspire to put at
least one band into the obviously-glowing zone within the first 60-90
seconds, or the whole "no tutorial needed" premise fails silently, with no
error to catch it — the player just quits. I've flagged this as a
playtesting acceptance check, but it isn't a UX fix if it goes wrong; it's a
balance/tuning fix, and I want it on someone's checklist before it's
forgotten.

## Status

Not merged to `main`. No code was written — specs and a token set only, as
scoped. Ready for the Frontend Engineer to build against once the Architect
has answered the API-shape questions above (particularly #1–3, which affect
what data the renderer/UI layer needs from the solver).
