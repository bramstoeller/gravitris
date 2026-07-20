# Screen: Title

## Purpose

The only entry point. One decision: play. Best score is shown here because
it's the number returning players are chasing, and this is where they see it
before committing to a session.

## Layout

```
┌─────────────────────────────────┐
│                                  │
│                                  │
│                                  │
│            S Q U I S H          │  ← type-display, color-text
│                                  │     (logo treatment, static —
│                                  │      no shader/physics running
│                                  │      behind this screen)
│                                  │
│                                  │
│         BEST   12,480           │  ← type-caption "BEST" (muted) +
│                                  │     type-title value (color-text)
│                                  │     omitted entirely on first run
│                                  │
│                                  │
│        ╭──────────────╮         │
│        │     PLAY     │         │  ← type-body, radius-pill,
│        ╰──────────────╯         │     color-glow-tinted fill —
│                                  │     the one warm object on an
│                                  │     otherwise cool/dark screen,
│                                  │     drawing the eye deliberately
│                                  │
│                                  │
│                            ⚙     │  ← settings icon, 48×48dp target,
│                                  │     bottom-right corner
└─────────────────────────────────┘
```

Tapping anywhere on the screen (not just the Play button) starts a run —
consistent with the game's whole "direct manipulation, minimal chrome"
philosophy — but the Play button is still drawn so the affordance is
unambiguous for a first-time player who doesn't yet know that.

## States

**Empty (first run — no best score exists yet)**
The "BEST" row is omitted entirely, not shown as "BEST 0" or "BEST --".
Zero is not a score anyone earned; showing it invites the wrong reading
("I have to beat zero," trivial) instead of the right one ("no record yet").
Layout simply has more breathing room above the Play button on this first
visit.

**Loading (cold start only)**
The brief requires an on-screen frame-time readout from the first build and
notes shader compilation as a real first-run cost. If the app takes more
than ~500ms to reach an interactive Title screen (most likely: first-run
shader compilation), show the logo static immediately with no spinner, and
fade in the BEST row / Play button / settings icon once ready, rather than
blocking on a spinner. A static logo reads as "arriving," a spinner reads as
"something might be wrong" — prefer the former for a sub-second wait.

**Populated (returning player)**
As drawn above. Score formatting: thousands-grouped (`12,480`), no decimal.
For very large scores (long-running endless play, or repeated sessions),
cap displayed width by font auto-shrink within the type-title box rather
than truncating or abbreviating digits — this is a high-score number, exact
digits matter to the player, unlike the game-over big-score treatment where
abbreviation is more acceptable (see `screens/game-over.md`).

**Error — local score storage unreadable**
If reading the saved best score fails (corrupt file, storage issue), fail
silently to the empty-state treatment (omit the BEST row) rather than
surfacing an error dialog on the very first screen a player sees. Do not
block Play. Log it (for QA/debugging), don't surface it — a lost personal
best is disappointing but not worth a modal on the title screen of a casual
game.

**Offline / permission-denied**
Not applicable — the app makes no network requests and requests no runtime
permissions. There is no such state for this screen.

## Focus order (D-pad / Switch Access)

1. Play button (content-description: "Play")
2. Settings icon (content-description: "Settings")

Best-score text is not a focus stop (it's not actionable), but should be
included in the screen's TalkBack reading order as static text preceding
the Play button.
