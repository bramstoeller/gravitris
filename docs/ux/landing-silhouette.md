# Landing silhouette

The brief is explicit that this needs to be honest: the piece will deform on
impact, so its final resting shape is genuinely uncertain at the moment the
player is aiming it. **A confident, precise-looking prediction that turns out
wrong is worse than a vaguer one shown honestly.** This document specifies a
silhouette that promises only what it can deliver.

## What it is not

Not a rigid ghost of the piece's current (undeformed) shape, dropped straight
down. That would visually promise an exact final outline, which is false —
the piece will squash, spread, and possibly tip or settle asymmetrically
depending on what it lands on. Showing a crisp double of the piece invites
the player to trust a shape that won't exist a moment later.

## What it is

A soft, translucent horizontal marker at the estimated resting position,
built from three elements, all deliberately vaguer than a shape outline:

```
        ╭╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╮   ← thin bright line: estimated top
        ┊  ▓▓▓▓▓▓▓▓▓▓▓  ┊      surface height (the one number
        ┊ ▓▓▓▓▓▓▓▓▓▓▓▓▓ ┊      worth being precise about)
        ┊▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓┊   ← feathered translucent footprint,
        ╰╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╯      neutral pale grey-white, ~30-35%
                                opacity, soft/blurred edges
```

- **Footprint fill:** neutral desaturated pale grey-white (not the piece's
  own hue, and never the reserved amber glow colour — either would make it
  look like a second solid object or a band-glow effect rather than a UI
  projection). Opacity ~30–35%. Edges feathered/blurred, not a crisp
  outline.
- **Top-surface line:** a single thin, brighter line marking only the
  estimated top height across the piece's projected horizontal extent — the
  one number that actually matters for the player's decision ("will this
  stick up too far / fit under that overhang"). Everything else stays soft;
  this stays legible.
- **Slow pulse:** opacity breathes gently, ~1.2s period, small amplitude —
  reads as "this is a live, provisional estimate," not committed geometry.
  This pulse is independent of and should not be confused with band-glow
  pulsing (different colour, different rhythm, different meaning).

## How it's computed (a cheap heuristic, not a prediction)

Recommend: sample the current settled stack's top-surface height across the
piece's live horizontal footprint (a heightfield/raycast query against
existing bodies, not a physics simulation), then estimate resting top height
as:

```
estimated_top = max(surface_height_in_xrange) + piece_uncompressed_height × squash_fraction
```

`squash_fraction` (start at ~0.7, tune during the prototype milestone) is a
single constant standing in for "how much shorter does a piece typically end
up after landing." This is explicitly an approximation. It will not account
for tipping, lateral flow into gaps, or asymmetric compression against an
uneven surface — which is exactly why the rendering above stays soft rather
than crisp.

**Flag to the Architect:** confirm a cheap top-surface heightfield query is
available/affordable during drag, and at what rate it can reasonably update
(recommend 15–20Hz with interpolation between samples — this is a steering
aid, not a precision instrument, so it does not need to run at full render
frame rate). Do **not** run a full soft-body pre-simulation per frame to
generate this; that is almost certainly outside the frame budget alongside
the live solver, and even if affordable, it still wouldn't predict correctly
without also predicting neighbours' response.

## After commit

The instant hard-drop is committed, the silhouette disappears — it is a
pre-commit aid only. The real solver takes over and plays the actual
squash/settle. There is no snap or morph animation from silhouette to final
shape; attempting to reconcile them would itself imply a false precision the
silhouette was designed to avoid. The honesty is upheld by never claiming
they match.

## Failure states

- **Heightfield query unavailable for a frame** (e.g. mid-collapse chaos
  elsewhere in the well): hold the last valid estimate rather than flickering
  or vanishing.
- **Unavailable for longer** (> 400ms with no valid update): fade the
  silhouette out gracefully rather than displaying stale data indefinitely.
- **Near walls/overhangs:** the predicted footprint clamps horizontally
  exactly like the real piece's drag clamp, so the silhouette never claims a
  position the piece could not actually reach.

## What I'm uneasy about

This is the spec item most likely to fight the physics. If squash is highly
non-uniform in practice — a piece tips, rolls, or unloads its weight
unevenly onto an irregular surface — a single "estimated top height" line
could be wrong often enough to feel untrustworthy rather than helpful, which
would undermine the "landing silhouette mitigates the imprecision of direct
manipulation" argument the controls design leans on.

**Recommendation:** treat "does the silhouette feel honest or does it feel
wrong" as an explicit playtesting question at the prototype milestone, not
an assumption. If it reads as overconfident in practice, the fallback is to
make it *vaguer still* — a height **range** band (a soft gradient from
"probably at least this high" to "possibly this high") rather than a single
line — before ever making it more precise. Precision is not free to add
later if the underlying estimate is not trustworthy; vagueness is free to add
at any time.
