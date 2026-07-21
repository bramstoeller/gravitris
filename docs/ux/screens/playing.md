# Screen: Playing

## Purpose

The game. Minimal chrome — the brief's whole art-direction argument is that
*gameplay* feedback lives on the material, not in HUD elements — but "minimal
chrome" was never meant to mean "no score, no level, no way to see what piece
is next." **Updated 2026-07-21 (`visual-direction.md` §6):** the debug
frame-time readout that was standing in for a HUD is replaced by a real one.
It lives in the Android View layer, same as the readout it replaces — it
costs nothing on the GPU, so it isn't in tension with "feedback lives on the
material" at all; that principle was always about gameplay feedback
(squash, glow, shake), not status chrome.

## Layout

```
┌─────────────────────────────────┐
│ 4,210              LV 3     ⏸  │  ← current score (type-title, top-
│ best 12,480         ╭──╮        │     left), level chip (top-centre),
│                     │▓▓│ NEXT   │     pause icon (top-right, 48×48dp)
│                     ╰──╯        │     next-piece preview (top-right,
│   · · · (soft cool glows,       │     beneath pause icon)
│   visual-direction.md §3,       │
│   drifting very slowly in       │
│   the well's empty space) · ·  │
│         ░░░░░░░░░░░░░            │
│        ░░░░░░░░░░░░░░░           │  ← falling piece (piece hue,
│         ░░░░░░░░░░░░             │     see piece-identity.md)
│                                  │
│     ┊╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌┊          │  ← landing silhouette
│     ┊▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓┊          │     (landing-silhouette.md)
│     ╰────────────────╯          │
│▓▓▓▓▓▓▓▓▓▓  ▓▓▓▓▓▓▓  ▓▓▓▓▓▓▓▓▓▓▓ │  ← settled stack, multiple
│▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │     hues, band-glow visible
│░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ │     where a band is filling
│                                  │     (band-glow.md); spawn band's
│                                  │     top edge pulses color-warn if
│                                  │     Phase.Overflow is active
│                                  │     (feel-feedback.md D6)
└─────────────────────────────────┘
```

The frame-time readout is **not shown by default any more** — see its own
entry below. That's the entire default screen otherwise. No d-pad, no
buttons beyond pause — controls are the drag/tap/swipe gestures over the
whole canvas, specified in `gestures.md`.

## Elements

- **Current score** — top-left, `type-title`, updates live (ticks up during
  the clear-sequence dissolve, per `feel-feedback.md`; the "+N" pop label at
  the clear location is a separate, additional element — see
  `visual-direction.md` §7.3 — that appears at the clear, not here).
- **Best score** — directly beneath, `type-caption`, `color-text-muted`.
  Omitted if no best exists yet (first-ever run — matches Title's empty
  state logic; the very first run has nothing to compare against until it
  ends).
- **Level chip** — top-centre, `type-caption` label ("LV") + `type-body`
  value, `color-surface` chip background (`radius-sm`, `elevation-1` — same
  chip language as the score/best pairing implies, kept visually
  lightweight since it updates rarely, not every frame).
- **Next-piece preview** — top-right, beneath the pause icon. A small
  (~40×40dp) flat silhouette of the next piece's shape in its own hue,
  drawn with a simple two-stop vertical gradient (not the gel shader — see
  `visual-direction.md` §6 for why this is deliberately an Android View,
  not GL geometry). Swaps in with `motion-pop` (`tokens.md`) when a new
  piece spawns. Content-description: "Next piece: {shape name}, {hue name}"
  for TalkBack, even though the canvas itself stays a single opaque element
  (see Focus order, below).

  **Correction, 2026-07-21 — this needs a contract addition it doesn't have
  yet.** I originally described this alongside the background/overflow work
  as buildable independent of D8 (scoring). That's wrong for a different
  reason than score is: the Frontend Engineer checked `SimState` and found
  it exposes only the *current* active piece's archetype
  (`activePieceBody` → `bodyArchetype`), never the upcoming one —
  `PieceSequence.peek()` exists but the class is `internal` to `:core-sim`
  (confirmed: `PieceSequence.kt:31`), so nothing crosses the module boundary
  today. **Until `SimState` exposes the next piece (archetype at minimum;
  shape too if the silhouette shows more than a coloured swatch), omit this
  slot entirely rather than show an empty placeholder or fake data** — an
  empty box is exactly the "tech demo" read this whole document exists to
  fix. This is a `docs/contracts.md` change (Backend implements, Architect
  signs off per that document's ownership table), not something the shell
  can approximate. Flagged to Backend directly; see the handoff.
- **Pause icon** — top-right, 48×48dp target, opens Paused. Same action as
  system back (see `ia.md`).
- **Frame-time readout** — **demoted from default-on to a debug-only
  toggle**, per `visual-direction.md` §6. Still bottom-left, monospace,
  low-opacity (~55%) when shown. Reachable via the same debug affordances
  already wired for the shading-tier dial (`MainActivity.onKeyDown`) —
  **debug builds only**, gated the same way `runSolverBenchmark` already is
  (`FLAG_DEBUGGABLE`), rather than a Settings toggle a release build would
  ship. The performance-verification loop this exists for is a build-time,
  client-device concern, not a player-facing setting.
- **Landing silhouette** — see `landing-silhouette.md`. Visible only while a
  piece is actively falling and being steered; disappears the instant a
  hard-drop commits.
- **Well walls/floor** — `color-surface`, otherwise undecorated. No grid
  lines (the whole point of this game is that there is no grid). The wall's
  inner edge now carries a thin emissive line (`visual-direction.md` §3) —
  still no new attribute, still the same three quads.
- **Environment/background** — the well's own empty space (above the stack,
  between pieces) now shows the procedural gradient + two soft cool glows
  specified in `visual-direction.md` §3, replacing flat black. This is
  background, not gameplay — it never uses `color-glow` (amber) or
  `color-warn` (red), so it can never be confused with band-glow or the
  overflow warning.

## States

**Empty (run start)**
First piece spawns immediately on entering Playing — no countdown, no
"ready?" card. The well is visually empty except the walls/floor and the new
background environment (`visual-direction.md` §3) — this is the first-ever
thing a first-time player sees, and it's also the first moment the
environment pass has to justify itself: an empty well is the state with the
*most* background visible, so a flat colour there would still read as "no
background" even with everything else in this document built. Score/best/
level/next-piece all appear immediately (score and level start at their
initial values, not a loading placeholder). This is also, not coincidentally,
the very first thing a first-time player ever sees after Title, so it should
feel instant, not like a loading transition.

**Loading**
None expected during normal play. If a frame hitches badly enough to be
visible (thermal throttling, GC pause), no UI should appear over it — that's
an engineering robustness concern, not a UX-designed state; there is no
"buffering" spinner in this screen's vocabulary.

**Populated, sparse (early run)**
As drawn above minus most of the stack — mostly empty well, one piece
falling. Bands are dark (below 40% fill, see `band-glow.md`) — this is
expected and correct, not a bug to make more visually interesting.

**Populated, dense / overflowing (late run, near top-out)**
Stack fills most of the well height; multiple bands may be glowing at once
(band-glow.md's "wall of embers" case — intentional, not suppressed). Score
number may be large — see Title's note on auto-shrink rather than
truncation for the current-score readout too, since endless mode can run
long. Score, level, next-piece and pause icon stay fixed in their corners
regardless of stack height (never obscured by gameplay, never move to
"make room"). If `Phase.Overflow` is active, the spawn band's `color-warn`
pulse (`feel-feedback.md` D6) is the dominant new signal in this state —
it should be legible even with several bands simultaneously glowing amber
underneath it, which is exactly why it's a different hue rather than a
brighter version of the same one.

**Error**
No user-facing error state is designed for this screen. A solver/render
hiccup is an engineering robustness matter (see brief's Performance
Verification section), not something this screen surfaces as a message —
there is nothing correct to tell the player ("the physics glitched" is not
actionable information for them).

**Offline / permission-denied**
Not applicable — no network, no runtime permissions requested.

## Focus order

Not applicable in the conventional sense — this screen has no D-pad-
navigable menu, only the pause icon as a single actionable element, which
should still carry a content-description ("Pause") for Switch Access users.
The canvas itself is exposed to TalkBack as one static-labelled element
("Game board") per `accessibility.md` — this is a stated limitation, not a
gap to close later. The score, level and next-piece elements are **not**
focus stops (none are actionable) but should be in the TalkBack reading
order as static text/labels, same treatment Title already gives its best-score
row.

## A note on score/level/next-piece source data

Score and level display are specified here so the HUD is ready the moment
D8 (score/persistence, backlog) lands — **this screen does not implement
scoring**, it only specifies how to show it once `SimState.score`/`level`
exist (both already in `docs/contracts.md` §3's `SimState` interface, just
hardwired to placeholder values pending D8). The frame itself (chip layout,
labels) can and should be built now against those placeholder values, per
the Frontend Engineer's plan — a static "LV 1" and "0" are honest, not fake,
because they're the real current values of real fields, just not yet driven
by gameplay.

**Next-piece is a different, harder gap than score.** It isn't waiting on a
value to change — the field doesn't exist on `SimState` at all yet (see the
correction on the next-piece bullet, above). That one genuinely cannot be
built until the contract changes; the background/environment and
overflow-warning parts of this document are independent of both gaps and
can land first, exactly as originally noted.
