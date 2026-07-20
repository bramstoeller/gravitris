# Pace — the reaction-time accommodation (DEFERRED, not built in iteration one)

> **Status: deferred to a later iteration.** The client initially asked for
> this as a first-class requirement, then reconsidered and asked for it out
> of the first version — deferred, not rejected, per the Product Lead's
> instruction that "keep it simple for the first iteration" already
> answered this before it needed to be asked. **Nothing below is
> implemented or in scope for the current build.** The reasoning is kept
> intact, not deleted, because this is a real requirement for a future
> iteration and re-deriving it from scratch later would waste the thinking
> already done here. Do not build against this document without checking
> with the Product Lead first — it is not a live spec.
>
> **What's still firmly in scope for iteration one, unaffected by this
> deferral:** Reduced Motion (`accessibility.md`) and the colourblind-safe
> piece palette (`piece-identity.md`). Neither was ever in question; this
> reversal applies only to the reaction-time/pace idea below.

This was going to be designed in from the start, as a first-class
requirement alongside reduced motion and the colourblind-safe palette, not
a deferred nice-to-have. This document specifies it to the standard those
two are held to, so it's ready to pick back up later.

## The mechanism: decouple the two difficulty dials

The brief gives endless mode two dials — **fall speed** and **block mass** —
and currently ramps them together. They do different jobs. Mass is what
makes the stack sag, compress and settle unpredictably: the *interesting
physics*, and the thing the whole product is actually about. Speed is what
makes the game a reaction test: a *twitch* demand, layered on top of the
physics, not the source of it.

**Pace decouples them.** Turning it on does not cap or remove challenge in
general — it caps only the fall-speed ramp, while the mass ramp continues
exactly as it does today. A player keeps the heavy, sagging, dramatic
physics the whole product is built around, and loses only the reflex
pressure on top of it.

This is the right shape for the accommodation, not a bolted-on easy mode,
for a concrete reason: **the challenge that matters most in this game isn't
purely a function of time pressure in the first place.** Direct-manipulation
controls are already imprecise by design (`gestures.md`), and the landing
silhouette is deliberately honest that a heavy piece's final resting shape
is uncertain even when placed perfectly (`landing-silhouette.md`) — a
patient player with unlimited time to aim still doesn't fully control how a
piece settles once it lands. Removing time pressure removes the *twitch*
layer; it does not remove the physics unpredictability that makes packing
genuinely hard. That's why decoupling, rather than a general slowdown of
everything, is the correct accommodation: it isolates the one axis
(reflexes) that's actually a barrier for some players from the one axis
(weight and squash) that's the actual game.

## What the player sees and chooses

A single setting, **Pace**, with two states:

- **Standard** (default) — fall speed ramps exactly as already specified;
  no change from the rest of this spec set.
- **Relaxed** — the fall-speed ramp follows the same early curve as
  Standard, then flattens at a fixed ceiling (`relaxedSpeedCeiling`, a
  tunable constant — see Open balance question below) and never rises past
  it, no matter how long the run continues. **Block mass keeps rising
  identically in both states.**

### Framing: mechanical, not evaluative

The setting is named for what it does, not for a skill level, and the two
states are described the same way a caption-size or playback-speed control
would be — because that's a well-established way to make an accommodation
feel like a preference rather than a confession. Copy for the Settings row:

> **Pace** — Standard / Relaxed
> Controls how quickly pieces fall. Doesn't affect their weight or your
> score.

Note what that caption does on purpose: states plainly that mass (the
"weight" the player already feels every session) is unaffected, and that
score isn't touched either — pre-empting the exact anxieties ("is this the
easy mode," "will my score count") a player might otherwise have to ask
Settings to answer implicitly. No screen in this app uses the words "easy,"
"difficulty," or "assist" anywhere near this control.

There is no third state and no continuous slider. The ambition the client
asked for is in the *capability* (live-adjustable, mid-game, decoupled,
score-neutral — all below), not in offering more tiers. More tiers is more
decisions for every player, including the ones who don't need this control
at all, and the constitution's "reduce the number of decisions" principle
argues against it here as much as anywhere else in this spec set.

## Discoverable while playing, not just before starting

This is the part that makes it first-class rather than a settings-menu
checkbox: **a player must be able to change Pace without quitting the run
they're struggling with.** Discovering mid-session that you can't keep up
is, as the Product Lead put it, the worst possible moment to be told to
quit to a menu — so this spec makes sure quitting is never required.

- **Settings** (reachable from Title or Paused, per `ia.md`) carries the
  canonical Pace control, for pre-game setup and for players who want to
  set it once and forget it.
- **Paused also carries the same control directly**, not nested one level
  further into Settings — see the updated `screens/paused.md`. Pausing is
  already the natural "I need a moment" action mid-struggle; the fix is to
  make sure that moment doesn't require a second, deeper navigation before
  the player can act on it.
- **Changing Pace never ends or restarts the run.** It changes the
  fall-speed ramp's target for the run in progress, live. Resume, and the
  same run continues with the new pace applied going forward.

This is one control shown in two places, not two different controls — the
Settings row and the Paused row read and write the same state. That's a
deliberate exception to "one way to do a thing, used everywhere": the
*interaction pattern* doesn't change, only which moment a player can reach
it from, because this is the one control in the app where "how do I even
get here" during a bad moment is the actual design problem.

## Live transitions: always eased, never a snap

Because Pace can change mid-run, the fall-speed ramp's current value must
ease toward whatever the new target is rather than jump:

- **Standard → Relaxed, current speed above the ceiling:** ease the fall
  speed down to `relaxedSpeedCeiling` over a short transition (a few piece
  spawns), not an instant cut. A sudden deceleration reads as a glitch, not
  relief.
- **Relaxed → Standard:** resume ramping upward from whatever the current
  (possibly capped) speed is, at the standard ramp's normal rate — do
  **not** jump forward to "what Standard would be by this point in the
  run." Snapping the exact demand this setting exists to remove back onto a
  player the instant they switch it off would be the one truly hostile
  failure mode available here.

## Scoring: one personal best, no tag, no asterisk

Score and best score are identical mechanisms regardless of Pace. No
per-pace best, no badge or label on Game Over indicating which Pace a run
used, nothing in the score number or its formatting differs. This matches
the Product Lead's view and I agree with it, not just procedurally: there
is no leaderboard and no second party this game is comparing anyone
against (per the brief's non-goals) — "personal best" already means
"best run I've had," full stop, and a player choosing Relaxed knows exactly
what they chose. Tagging the score would manufacture a comparison nobody
asked for and make the accommodation read as second-class, which is the
opposite of "first-class requirement."

## Interaction with Reduced Motion

Independent settings, no coupling between them, and no shared or combined
control — a player may want either, both, or neither in any combination
(a player with a vestibular sensitivity but fast reflexes wants Reduced
Motion alone; a player with slower reflexes but no motion sensitivity wants
Pace alone). Displaying them together on Settings and Paused should read as
"here are the accommodations," not as one combined "accessibility mode"
toggle — keep them as two clearly separate rows, each independently
labelled.

## Is the Settings screen still coherent with this added?

Settings now carries four rows: **Pace** and **Reduced Motion** (the two
accommodations, grouped together, listed first) then **Haptics** and
**Sound** (preferences, listed after). That's still a short, flat list with
no sections or nesting — the ordering does the organizing work instead of
adding structure. See the updated `screens/settings.md`.

## What does NOT change

- No persistent in-game HUD indicator of which Pace is active during play.
  The player already knows what they chose; showing it on the Playing
  screen would be exactly the kind of HUD chrome this whole spec set has
  otherwise avoided. Pace is visible where it's *set* (Settings, Paused),
  not layered onto the one screen that's supposed to stay clean.
- Nothing about the band-glow curve, gesture thresholds, haptic mapping
  formulas, or clear-sequence timing changes based on Pace. Those all key
  off band fill, touch input and impact energy — none of which Pace
  touches directly. One implementation detail worth flagging: the haptic
  energy formula (`feel-feedback.md`) normalizes impact velocity against an
  expected fall-velocity range — that range should be computed against
  whichever ramp ceiling is *currently in effect* (Standard's full range or
  Relaxed's capped range), not a single fixed absolute max, so impacts still
  feel proportionately weighted at Relaxed pace instead of rarely reaching
  full haptic amplitude just because the ceiling is lower.

## Open balance question for the Architect / Product Lead

I agree with decoupling the dials — reasoning above — but want to flag the
one real risk I see rather than wave it through: **if fall speed is capped
indefinitely while mass keeps rising, does a sufficiently patient, skilled
player under Relaxed pace ever actually top out, or does effectively
unlimited time-per-piece let optimal packing continue indefinitely?** The
losing condition itself is already an open question in the brief (a fixed
top-out line is unfair against a sagging stack); Relaxed pace makes that
question sharper, because it removes the one pressure (limited time to
react) that might otherwise force a mistake. If mass alone — heavier
blocks, more sag, less predictable settling — doesn't reliably force
eventual mistakes even given unlimited placement time, Relaxed pace risks
becoming unloseable rather than differently-paced, which would be a
mechanically different game, not an accommodation.

I don't have the mass-scaling curve or the settle/loss-condition mechanics
to answer this myself — flagging it for whoever owns those numbers to check
once there's a playable build, the same way the coverage threshold and
gesture constants need real playtesting. A secondary, smaller flag: capping
speed while an endless mode keeps running could make Relaxed sessions take
noticeably longer in wall-clock time to reach the same "difficulty," which
may sit awkwardly against the brief's "short, one-handed, commute" session
length assumption — worth checking against real play, not assumed either
way here.
