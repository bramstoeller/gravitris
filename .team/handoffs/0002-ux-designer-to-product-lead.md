# 0002 — UX Designer → Product Lead

Revision pass on top of handoff 0001, folding in the confirmed client
device (Fairphone 6) and the reaction-time accommodation discussion —
including its proposal, then its deferral. One pass, as requested, covering
both threads together. Still on branch `chore/ux-spec`, not merged, no code.

## What changed because of the confirmed device (Fairphone 6, OLED, adaptive 10–120Hz, 1400 nits, 1 billion colours, 6.31")

1. **`tokens.md`** — `color-bg` changed from a near-black `#12141C` to true
   black `#000000`. On OLED, only true black is (near) zero-power and gives
   the glow maximum contrast headroom — matches both the aesthetic and the
   device's sustainability framing. `color-surface` stays a lifted dark
   blue-grey so the well's walls/floor still read as a distinct surface
   against a true-black void, rather than disappearing into it.
2. **`band-glow.md`** — added an explicit OLED banding-risk section. A true
   black background makes the glow's 0→0.15 climb through 40–70% fill (and
   any AO/subsurface darkening in the gel shader) more likely to visibly
   band on an 8-bit output surface, not less. Mitigation specified: dither
   the low-intensity glow term using the same procedural noise field
   already driving gel-grain and ember shimmer, rather than building a
   second dithering system — close to free, ties two problems to one fix.
   Flagged to the Architect: confirm whether the compositor already
   dithers, and design against an 8-bit output surface as the baseline even
   though the panel supports 10-bit.
3. **`piece-identity.md`** — added a small-screen density section. 6.31" is
   comfortable for one-handed drag but caps how much colour information is
   legible at once; small-area colour matching degrades hue/saturation
   discrimination exactly as patches shrink under a dense, squashed stack,
   and the CVD-risk pairs already flagged in handoff 0001 get worse at small
   size, not better. Specified a boundary seam/crease cue at every
   piece-to-piece edge (independent of hue) as the primary mitigation, with
   the lightness ladder as the second line and grain-scale explicitly
   downgraded to "least reliable at small size, don't lean on it." Flagged
   as a prototype-milestone action item to verify actual on-device patch
   sizes at realistic (near top-out) density, not mockup scale.
4. **Frame-rate independence, stated explicitly** in `tokens.md`,
   `band-glow.md`, `feel-feedback.md`, and `gestures.md` — every timing and
   threshold in this spec set was already ms/dp/dp-per-second based, not
   frame-based, but I made this an explicit, findable statement in each
   file given the panel's adaptive 10–120Hz range and the Architect's
   likely fixed-60Hz-physics/interpolated-render approach. Nothing in this
   spec set assumes or requires 120Hz; it's a smoothness bonus wherever it
   applies, never a dependency.
5. **`flows.md`** — added a short note that the deferred Play Store means
   this ships as a directly-shared APK with no store listing to set
   expectations, which raises the stakes on the first-session flow (journey
   1) without changing its design — the client's "keep it simple" standing
   instruction still rules out onboarding, and I didn't add any.
6. **`accessibility.md`** — strengthened the reduced-motion rationale:
   sustained shake/jiggle at up to 120Hz on OLED is a stronger nausea
   trigger than the same motion at 60Hz on LCD, not milder, so the setting
   stays firmly in scope, unsoftened. Added a flag to the Architect to
   confirm the reduced-motion zeroing/damping applies at whatever layer
   does render interpolation, not only at the physics-tick level.

## The reaction-time accommodation: proposed, then deferred

You asked me to evaluate decoupling the two difficulty dials (fall speed
vs. block mass) as a reaction-time accommodation, after the client
initially chose to make it a first-class requirement. I designed it in
full — mechanism, framing/copy, live mid-game discoverability via Paused
(not just Settings), always-eased transitions in either direction, a single
unscored/untagged personal best, and interaction with Reduced Motion and
the Settings screen layout.

**My assessment before the reversal, for the record:** decoupling was the
right shape for this, not a bolted-on easy mode — the game's real
challenge (unpredictable squash/settle, imprecise direct-manipulation
controls) isn't purely a function of time pressure, so removing the speed
ramp's reflex demand while leaving the mass ramp untouched isolates the
right axis. The one real risk I flagged, not resolved: capping speed
indefinitely while mass keeps rising might let a sufficiently patient,
skilled player avoid ever topping out, since the thing that would otherwise
force a mistake (limited time) is exactly what's removed — this interacts
with the brief's already-open losing-condition question and needs real
playtesting to answer, not a design decision.

**Then the client reconsidered and asked for it out of iteration one,
deferred rather than rejected.** Per your instruction, I did not delete
this work: it now lives in `docs/ux/pace.md`, marked with an unmissable
"DEFERRED, not built in iteration one" banner at the top, stating plainly
that nothing in it is implemented or should be built against without
checking with you first. `accessibility.md` and `README.md` both
cross-reference it the same way — as a resolved decision (deferred, not an
open gap), not as unfinished business.

**Unaffected by this reversal, exactly as before:** Reduced Motion
(`accessibility.md`) and the colourblind-safe six-hue palette
(`piece-identity.md`) remain first-class, in scope, unchanged. The
Settings screen (`screens/settings.md`) was never edited to add Pace to it
— I wrote `pace.md` as a standalone document before touching any screen
spec, so there was nothing to revert there. It still reflects the original
three-toggle set (Reduced Motion, Haptics, Sound) from handoff 0001.

## What I did not touch, per your instruction

- `landing-silhouette.md`'s uneasy note (the single-line estimate risk /
  height-range-band fallback) — untouched, verified verbatim against the
  version from handoff 0001.
- `piece-identity.md`'s "verified on paper only, no simulator available"
  CVD caveat and its QA action item — untouched, verified verbatim. The new
  small-screen-density section is additive, placed separately, and doesn't
  soften or restate that caveat.

## Open questions carried forward (updated)

From handoff 0001, still open: band definition/fill-feed rate, quiescence
check before a clear fires, heightfield-query feasibility for the
silhouette, impact-propagation legibility, no-bloom assumption, and piece
archetype count (all Architect-facing); CVD simulator verification before
release (QA-facing).

New in this pass: OLED compositor dithering behaviour (Architect), reduced
motion's interaction with render-layer interpolation (Architect),
on-device palette legibility at real stack density on the 6.31" panel
(Architect/QA, prototype milestone). The reaction-time accommodation is
resolved (deferred) rather than open, but its one real balance question —
does mass alone reliably force a top-out without time pressure — is worth
someone remembering when it comes back off the backlog, and is written into
`pace.md`'s own open-question section rather than repeated here.

## Status

Not merged to `main`. No code. Ready for Frontend Engineer to build against
once the Architect answers the standing questions from 0001 and this pass.
