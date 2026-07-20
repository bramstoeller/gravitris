# Screen: Playing

## Purpose

The game. Minimal chrome — the brief's whole art-direction argument is that
feedback lives on the material, not in HUD elements, so this screen has as
little UI as a falling-block game can have.

## Layout

```
┌─────────────────────────────────┐
│ 4,210                      ⏸    │  ← current score (type-title,
│ best 12,480                     │     top-left) + pause icon
│                                  │     (48×48dp, top-right)
│                                  │     best score small/muted
│                                  │     beneath current, always
│                                  │     visible (flows.md journey 2)
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
│                                  │     (band-glow.md)
│                                  │
│ 16.7ms  60fps                   │  ← PROTOTYPE MILESTONE ONLY:
└─────────────────────────────────┘     frame-time readout, bottom-
                                         left, type-debug-mono, low-
                                         opacity so it doesn't compete
                                         visually with gameplay
```

That's the entire screen. No d-pad, no buttons — controls are the drag/tap/
swipe gestures over the whole canvas, specified in `gestures.md`.

## Elements

- **Current score** — top-left, `type-title`, updates live (ticks up during
  the clear-sequence dissolve, per `feel-feedback.md`).
- **Best score** — directly beneath, `type-caption`, `color-text-muted`.
  Omitted if no best exists yet (first-ever run — matches Title's empty
  state logic; the very first run has nothing to compare against until it
  ends).
- **Pause icon** — top-right, 48×48dp target, opens Paused. Same action as
  system back (see `ia.md`).
- **Frame-time readout** — bottom-left, monospace, low-opacity (recommend
  ~55%) so it reads as instrumentation, not part of the game's own visual
  language. **This exists because the brief requires it from the first
  build** — the performance loop can only close on the client's real
  device, and this is how the client reports real numbers. It should be a
  togglable/buildable-out element (Settings toggle or build flag) by the
  "Game" stage of the sequencing plan, once the performance question is
  answered — not necessarily present in the store-ready release. Flagging
  this as a decision for the Architect/Product Lead at that later stage,
  not deciding it here.
- **Landing silhouette** — see `landing-silhouette.md`. Visible only while a
  piece is actively falling and being steered; disappears the instant a
  hard-drop commits.
- **Well walls/floor** — `color-surface`, otherwise undecorated. No grid
  lines (the whole point of this game is that there is no grid).

## States

**Empty (run start)**
First piece spawns immediately on entering Playing — no countdown, no
"ready?" card. The well is visually empty except the walls/floor. This is
also, not coincidentally, the very first thing a first-time player ever sees
after Title, so it should feel instant, not like a loading transition.

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
long. Pause icon and frame-time readout stay fixed in their corners
regardless of stack height (never obscured by gameplay, never move to
"make room").

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
gap to close later.
