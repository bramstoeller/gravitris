# Screen: Game Over

## Purpose

Show the result, show it against the player's best, and offer the one thing
that matters for the "beat your own score" habit loop: play again,
instantly.

## Layout

```
┌─────────────────────────────────┐
│                                  │
│                                  │
│           14,920                │  ← type-display, this run's score
│                                  │
│         NEW BEST                │  ← only shown if beaten; type-title,
│                                  │     color-glow (ties to the same
│                                  │     warm-light "something good"
│                                  │     language as band-glow)
│                                  │
│         best  12,480            │  ← previous best, shown crossed
│                                  │     through or de-emphasized once
│                                  │     superseded (see states below)
│                                  │
│        ╭──────────────╮         │
│        │  PLAY AGAIN  │         │  ← primary, radius-pill,
│        ╰──────────────╯         │     color-glow-tinted, ONE TAP,
│                                  │     no confirmation
│                                  │
│            Title                │  ← secondary, text-only
│                                  │
└─────────────────────────────────┘
```

## States

**First-ever run (no prior best existed)**
No "NEW BEST" badge — see `flows.md` journey 1's reasoning: a single data
point becoming the best by definition isn't a record worth the celebratory
treatment. Show the score plainly; omit the "best" comparison row entirely
(there was nothing to compare against). This keeps the emotional payoff of
"NEW BEST" meaningful when it does appear on a later run.

**Beat previous best**
"NEW BEST" badge shown as drawn above, `color-glow` accent, and the
previous-best row shown as a smaller reference point beneath (so the player
can see by how much) rather than removed — the comparison is part of the
satisfaction.

**Did not beat best**
No badge, no negative framing ("you lost," "game over" doom language) —
this is an endless high-score game, ending a run is the expected, repeated
shape of play, not a failure state. Show this run's score, and the existing
best beneath it, plainly, as in the wireframe but without the badge line.

**Loading (saving score)**
Writing the score to local storage should be fast, but if it's not
instant, show the score number immediately (it's already known client-side)
and don't gate any visible content on the save completing — only the "NEW
BEST" determination needs the previous best to have been read, which should
already be in memory from Title/Playing, not re-read here.

**Error — score failed to save locally**
Do not block Play Again or show a modal. Show the run's score as normal
(it's real, the player did earn it) with a small, non-blocking inline note
beneath it — `type-caption`, `color-text-muted` — reading something like
"Score not saved" (exact copy is the engineer/tech-writer's call, keep it
short and blame-free — don't say "error" or name a technical cause). Retry
the save silently in the background; if it succeeds on retry, the note
simply disappears on next render. Never prevent the player from continuing
to play because a local write failed.

**Very large scores (long endless sessions)**
Auto-shrink the `type-display` score text to fit rather than truncating or
abbreviating digits (same rule as Title and Playing) — this is the number
the whole session was for, exact digits matter more here than almost
anywhere else in the app.

**Offline / permission-denied**
Not applicable.

## Focus order

1. Play Again (content-description: "Play again")
2. Title (content-description: "Return to title")

Play Again is first and is what most players want immediately after
reading their score — consistent with the "one more try" loop this screen
exists to support.
