# Flows

Three journeys that matter: the first-ever session (where the whole game has
to teach itself with no tutorial), the habitual replay loop (the actual
target persona — short, one-handed, on a commute), and an interruption
mid-play (a lifecycle case that's easy to get wrong and ruins trust if
mishandled). Drop-out risk is marked inline as **⚠ RISK**.

**Distribution context that raises the stakes on journey 1 without changing
its design:** the client has deferred the Play Store — this ships as a
signed APK a person receives directly from someone else, not something they
find and read a store listing for first. There's no description, no
screenshots, no reviews setting expectations before install. That makes the
first-run experience (journey 1, below) carry more weight than it would for
a store-discovered app: it's the *only* introduction this game gets. That
does not change the design — the client's standing "keep it simple"
instruction still rules out an onboarding flow, and the mechanic still has
to teach itself with zero tutorial screens, per the brief. It's a reason to
take journey 1's risk markers seriously, not a reason to add anything to
mitigate them beyond what's already specified.

## 1. First-ever session — the mechanic has to teach itself here

```
Title (empty state: no best score yet)
  │  single CTA, tap anywhere / Play
  ▼
Playing — first piece spawns immediately. No loading screen,
  no overlay, no "how to play" card.
  │
  ├─ Player free-drags and taps on the first 1-2 pieces, learning
  │  controls by feel.
  │  ⚠ RISK #1 — first 10-20 seconds. If drag sensitivity feels
  │  wrong, or a deliberate tap misfires as a drag (or vice versa),
  │  this is the likely bounce-off point. Mitigated by gestures.md's
  │  slop-based discrimination, but only playtesting confirms it
  │  feels right — flagged, not assumed solved.
  │
  ├─ Stack builds. Bands stay dark while under ~40% fill (band-glow.md).
  │  ⚠ RISK #2 — HIGHEST RISK MOMENT. If no band reaches the
  │  "obviously glowing" 70%+ zone within roughly the first 60-90
  │  seconds, the player never sees the game's entire hook and may
  │  quit thinking it's "Tetris with wobble." This is a tuning
  │  problem (piece size distribution, band height, early fall
  │  speed) — flagged to the Architect and QA as a prototype
  │  playtesting acceptance check, not something this document can
  │  guarantee through visual design alone.
  │
  ├─ A band creeps warm → breathes faster → flashes → clears.
  │  This IS the tutorial. The player has now witnessed
  │  cause → warning → payoff once, unprompted.
  │
  ├─ Difficulty rises (fall speed, mass) per the brief's two dials.
  │
  ▼
Stack tops out → Game Over
  │  Score is large/small text, best score line shown underneath —
  │  but NOT with "NEW BEST" celebratory treatment. A single data
  │  point becoming the best by definition isn't a record worth
  │  celebrating the same way beating a real prior score is; save
  │  that treatment for run 2+ (see journey 2).
  ▼
Play Again  or  Title
```

## 2. Returning player — the habitual commute loop

This is the persona the brief describes: short, one-handed sessions, the
hook is "beat your own score."

```
Title (best score shown prominently, large, next to the CTA —
       this is the number the player is here to beat)
  │  Play (one tap)
  ▼
Playing — current score always visible (large, top), best score
  always visible (small, corner) for the whole run, so the gap to
  beat is legible at a glance without any extra input.
  │
  ▼
Stack tops out (or End Run from Paused) → Game Over
  │
  ├─ Beat best → "NEW BEST" celebratory state. Visual treatment
  │  reuses the amber glow-accent colour (color-glow) to tie the
  │  celebration to the same warm-light language the whole game
  │  already uses for "something good just happened."
  │
  └─ Did not beat best → plain score/best comparison, no
     celebratory treatment, no negative framing either (no "you
     lost" language — this is an endless high-score game, not a
     failure state).
  │
  ▼
Play Again — ONE TAP, no confirmation dialog.
  ⚠ RISK — if Play Again requires any extra confirmation or
  friction, it directly damages the "one more try" impulse that
  this entire loop depends on. Do not add a confirmation step here
  under any circumstance (there's nothing destructive to confirm —
  the previous run is already scored and saved).
```

## 3. Interruption mid-play (call, notification, app switch)

```
Playing (mid-run, possibly mid-drag or mid-clear-sequence)
  │  OS lifecycle onPause
  ▼
Game auto-pauses — the simulation actually halts (not just a
  visual overlay). Nothing may fall, settle, or clear while
  backgrounded.
  │  ⚠ If this happens during the band-clear watch window
  │  (feel-feedback.md's 800-1400ms sequence), the sequence must
  │  freeze exactly where it is and resume from that point, not
  │  restart or skip ahead, when the player returns. Flagged to the
  │  Architect: implement the clear sequence as pausable timers/
  │  state, not wall-clock timestamps, so this falls out naturally.
  │
  │  player returns to the app
  ▼
App resumes into Paused — never directly back into a live falling
  piece. Landing the player straight back into live gameplay after
  an unknown absence risks an instant unfair top-out or misdrop from
  a piece they weren't watching. Paused lets the player choose when
  to re-engage.
  │  Resume
  ▼
Playing continues exactly where it left off.
```

## Cross-cutting notes

- Every "back to Title" exit anywhere in these three flows goes through
  Game Over first if a run is in progress (see `ia.md` — quitting is
  scored, not discarded).
- None of these three flows ever show a loading spinner mid-session. The
  only place a brief loading moment is plausible is app cold-start
  (shader compilation) — see `screens/title.md`'s loading state.
