# Screen: Settings

## Purpose

The minimum set of things a player might reasonably want to change, and
nothing else. Per the client's standing "keep it simple" instruction, this
is a short, flat list — no sections, no search, no nested menus.

## Layout

```
┌─────────────────────────────────┐
│ ←  SETTINGS                     │  ← back arrow (48×48dp) + type-title
│                                  │
│  Reduced motion            ◯──  │  ← toggle, off by default
│  Disables screen shake and                    
│  softens landing wobble         │  ← one-line explanatory caption
│                                  │     under each toggle — this is
│  Haptics                    ──● │     the ONE screen in the app
│                                  │     where a line of explanatory
│  Sound                      ──● │     text is appropriate, because
│                                  │     these are consequential,
│                                  │     infrequently-visited settings
│                                  │
│                                  │
│                                  │
└─────────────────────────────────┘
```

## Elements and defaults

| Setting | Default | Caption copy |
|---|---|---|
| Reduced motion | **Off** | "Disables screen shake and softens landing wobble" |
| Haptics | **On** | (no caption needed — self-explanatory) |
| Sound | **On** | Reserved/stub — see note below |

No other settings exist. Specifically not present: a coverage-threshold
tuner, a difficulty selector, a palette/theme choice, an account/login
section, or a "reset best score" action — none were asked for, and each
would be a decision the player has to make that the game should simply make
well by default (per the constitution: "reduce the number of decisions the
user has to make").

**Sound toggle is a stub.** Sound design is explicitly out of scope for
this pass (per the UX brief) — this toggle exists now only because the
Settings screen's shape shouldn't need revisiting the moment audio lands.
If no audio ships by the time this screen is built, either omit the row
entirely or ship it disabled/greyed with a "coming soon" affordance —
engineer's call, flagged here as a real open item rather than a firm
decision.

## States

**Empty / loading**
Not applicable — three fixed toggles, no data fetch, always immediately
interactive.

**Populated**
As drawn above — the only real state. Toggle positions reflect whatever
was last saved locally.

**Error — settings failed to load from local storage**
Fall back to the documented defaults (Reduced motion off, Haptics on, Sound
on) rather than showing an error or blank screen. A settings screen that
fails to load past its own defaults is invisible to the player — that's the
correct failure mode for something this low-stakes.

**Error — a toggle fails to save**
Revert the toggle visually to its last-known-good state and leave it
interactive (don't lock the UI). No modal, no toast — for a single boolean
preference, silent retry-on-next-change is enough; this is not
worth interrupting the player over.

**Offline / permission-denied**
Not applicable.

## Focus order

1. Back arrow (content-description: "Back")
2. Reduced motion toggle (content-description: "Reduced motion, toggle, currently off/on")
3. Haptics toggle (content-description: "Haptics, toggle, currently on/off")
4. Sound toggle (content-description: "Sound, toggle, currently on/off")
