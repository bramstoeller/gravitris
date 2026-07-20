# Screen: Paused

## Purpose

Freeze the game safely — including for OS lifecycle interruptions the
player didn't choose (see `flows.md` journey 3) — and offer the only two
things worth doing from here: keep playing, or leave with the run scored.

## Layout

An overlay on top of the (frozen, dimmed) Playing canvas — not a full
screen replacement. The stack stays visible underneath, dimmed, so the
player retains spatial context of where they left off.

```
┌─────────────────────────────────┐
│▓▓▓▓▓▓▓▓ (dimmed frozen canvas, ▓│  ← color-overlay-scrim over the
│▓▓ color-overlay-scrim @ 82%) ▓▓▓│     frozen Playing screen
│                                  │
│           P A U S E D            │  ← type-title
│                                  │
│        ╭──────────────╮         │
│        │    RESUME    │         │  ← primary action, radius-pill,
│        ╰──────────────╯         │     color-glow-tinted (same
│                                  │     "go" affordance as Title's
│                                  │     Play button)
│        ╭──────────────╮         │
│        │   SETTINGS   │         │  ← secondary, radius-md
│        ╰──────────────╯         │
│                                  │
│          End Run                │  ← tertiary, text-only, quieter
│                                  │     than the two buttons above —
│                                  │     deliberately the least visually
│                                  │     prominent option on this screen
└─────────────────────────────────┘
```

## Elements

- **Resume** — primary, largest, top of the group, matches Title's Play
  button treatment (same warm accent) so "this is the way back into the
  game" reads identically across screens.
- **Settings** — secondary button, opens Settings with a back-target of
  Paused (not Title) — see `ia.md`.
- **End Run** — text-only tertiary action, no button chrome, placed last
  and visually quietest. This ends the current run and scores it exactly
  as topping out would (goes to Game Over) — see `ia.md`'s "quit is scored,
  not discarded." No confirmation dialog needed: nothing destructive
  happens that a confirmation would meaningfully protect against (the score
  is preserved either way), and adding one would be friction the client's
  "keep it simple" instruction argues against.

## States

**Empty / loading / error**
None of these apply — Paused has no data dependency (no network, no list,
nothing that can be "empty" or fail to load). It's a fixed three-option
overlay every time.

**Populated**
The only state — as drawn above, regardless of how far into a run the
player is. The dimmed canvas behind it naturally varies (sparse early-run
stack vs. dense late-run stack) but Paused's own UI doesn't change.

**Reached via OS lifecycle pause (not a deliberate tap)**
Same screen, same options — a player returning after a phone call sees
exactly this, with Resume front and center, which is deliberately the same
recovery path as a manual pause. No special "welcome back" messaging; that
would be chrome for a moment that doesn't need it.

**Offline / permission-denied**
Not applicable.

## Focus order

1. Resume (content-description: "Resume")
2. Settings (content-description: "Settings")
3. End Run (content-description: "End run, ends the current game and shows your score")

Resume is first in focus order and is also what system back / the pause
icon's re-tap would trigger — the fastest path back into the game should
never require more than one action however it's reached.
