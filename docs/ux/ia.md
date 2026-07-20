# Information architecture

Five screens, portrait only. The client's standing instruction is to keep
iteration one simple, so this set is deliberately minimal and closed — no
screen exists for something that doesn't exist yet (no accounts, no levels,
no leaderboard, no store).

## Screens

| Screen | Purpose | Reachable from |
|---|---|---|
| **Title** | Entry point, best score, single way in | App launch, Game Over → Title, Settings → back |
| **Playing** | The game | Title → Play, Game Over → Play Again, Paused → Resume |
| **Paused** | Freeze, options, exit run | Pause icon or system back from Playing, OS lifecycle pause |
| **Game Over** | Final score, best, replay loop | Stack tops out, or "End Run" from Paused |
| **Settings** | Reduced motion, haptics, sound | Title or Paused (not from Game Over — see below) |

Settings is deliberately not reachable from Game Over: it's one extra branch
off a screen whose entire job is "look at your score, then choose replay or
title," and both of those existing entry points (Title, Paused) already
cover every moment a player plausibly wants to change a setting.

## Navigation graph

```
                 ┌──────────┐
      ┌─────────▶│  Title   │◀────────────────┐
      │          └────┬─────┘                 │
      │               │ Play                  │ Title
      │               ▼                       │
      │          ┌──────────┐   pause icon /   │
      │  Settings│          │   system back    │
      │ ◀────────┤ Playing  ├───────┐          │
      │  (back)  │          │       ▼          │
      │          └────┬─────┘  ┌──────────┐    │
      │               │        │  Paused  │    │
      │          tops │        └─┬──┬───┬─┘    │
      │           out │  Resume ─┘  │   │       │
      │               │             │   └─Settings (back returns here)
      │               ▼             │ End Run
      │          ┌──────────┐◀──────┘
      │ Play     │Game Over │
      └──Again───┤          ├──Title───────────┘
                 └──────────┘
```

## Back-button semantics (Android system back)

One rule per screen, no exceptions, so it's never ambiguous which behaviour
applies:

| Screen | System back does |
|---|---|
| Title | Exits the app (it's root — standard Android behaviour, no confirmation) |
| Playing | **Pauses** — identical to tapping the pause icon. Playing never exits directly. |
| Paused | **Resumes** — identical to tapping Resume. Closing the overlay resumes; it does not exit. |
| Settings | Returns to whichever screen opened it (Title or Paused) |
| Game Over | Identical to tapping "Title" |

## "Quit" is scored, not discarded

Paused's exit option is **End Run**, not "Quit to Title." Ending a run early
takes the current score to Game Over exactly as topping out would — it does
not silently discard progress back to Title. This keeps one consistent rule
("a run always ends at Game Over") instead of two different exit behaviours
depending on how the run ended, and it means a player who ends a run early
still sees where they stand against their best.

## What's deliberately not here

- No level/stage list (one endless mode).
- No account/profile screen (no accounts).
- No in-app store, no ad screen, no consent/permission flow — the app makes
  no network requests and needs no runtime permissions (see
  `accessibility.md` and the brief's platform constraints); there is nothing
  for a permission-request screen to do.
- No tutorial screen — the mechanic teaches itself through `band-glow.md`;
  see `flows.md` for why the first-session flow is designed to make that
  true rather than assumed.
