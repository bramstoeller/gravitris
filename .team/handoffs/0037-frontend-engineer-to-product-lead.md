# Frontend Engineer → Product Lead: glossy jelly candy render layer (§14–§19)

**Branch:** `feat/candy-material-render` (from `origin/feat/candy-material-datapath`,
PR #30 — NOT from main). **PR:** #<opened, see below>.
**Money shots:** `.team/reviews/0037-candy-material-render/` (six frames + README,
for you to view and show the client).

The round-3 glossy-candy render the client is waiting to see. Built on the
Backend Engineer's data path (handoff 0036) — body-wide UV, grain compensation
and the `particleCorner` flag. `make test` and the full `./gradlew check` are
green; the emulator play-through renders end to end (7 clears, 8 spawns).

## What I built, against the spec

- **§14 glossy jelly material** (`Shaders.kt`, `Tunables.kt`). One hard,
  near-white specular streak (`bodyUv · lightDir` → squared smoothstep band),
  biased upper-left, suppressed where `vContact` is high, gated to pieces so the
  walls never glint. Raised the subsurface gain (0.55→0.80) as the primary
  translucency cue and warmed/brightened the rim toward the specular.
- **§15 one continuous piece.** Consumed the backend's body-wide UV and folded
  `grainScaleCompensation` into `uGrainScale` (`GameRenderer.foldedGrainScales`),
  so grain/subsurface/specular sweep the whole tetromino — the "four separate
  squares" complaint is fixed. Verified in the frames.
- **§16 rounded corners** (`vCorner` varying, new `aCorner` mesh attribute).
  Rounds ONLY true outer-silhouette corners (L stays sharp at its elbow), fading
  toward the tray colour, cubed for a tight "slightly rounded, not a cube" fade.
- **§17 MSAA** (`MsaaConfigChooser.kt`, `GameView.kt`). 4× hardware MSAA on the
  EGL config, with graceful fallback to 2×/off if the driver offers none — no
  shader change, "no blend / no discard" rules intact.
- **§18 soft shadow** (`PieceShadow.kt`). A second cheap blended draw of the body
  mesh, offset down+right, flat darkened-tray colour, edge-feathered. The one
  pass that turns `GL_BLEND` on, and only for itself.
- **§19 light candy background** (`Background.kt`, `Palette.kt`). Recoloured to
  the sky-blue→cream gradient + warm sun-haze patches, and the tray to
  `color-tray`.

Both MSAA and the shadow fold into the shade-tier cut ladder (shadow via
`SHADOW_MIN_SHADE_LEVEL`; MSAA as a build-time constant because the EGL sample
count is fixed at surface creation and cannot ride the per-frame dial — noted in
`Tunables.MSAA_SAMPLES`).

## Commit range

`feat/candy-material-datapath`..`feat/candy-material-render`:
1. `feat(app): glossy candy material, rounded corners, soft shadow, msaa`
2. `feat(app): recolour to the light candy world background and tray`
3. `style(app): sharpen gloss, quiet grain, ground shadow, soften corners` (one
   on-emulator tuning pass after looking at the frames)
4. `docs(team): add glossy candy render money shots for the client`

## What I deliberately did NOT do

- **No input/lifecycle/control changes** — did not touch `GestureRecognizer` or
  `Simulation` phase logic (owned by the control-redesign track).
- **No HUD recolour.** `tokens.md` flips HUD text to dark-on-light with a light
  chip (`color-hud-chip`); §20/§21 flag that as cosmetic follow-up, not blocking.
  `GameHud` still uses the old dark chip. On a light background the current dark
  chip + light text is still legible, but it does not match the new tokens — a
  small follow-up (see §21).
- **No band-glow revisit** (§20): the additive amber will under-read on the light
  sky. Flagged there, not designed here.
- **No exact palette tuning** — hues unchanged per the dispatch ("first pass, the
  client steers colours").

## Considered and rejected

- **Fading corners toward the sky gradient instead of the tray.** Rejected per
  §16: correct only at the very top of an empty well, visibly wrong mid-stack.
  Tray is the documented compromise; the airborne-fringe cost is real (below).
- **Per-body-centroid outward scale for the shadow** (§18's soft-spread idea).
  The mesh carries no centroid attribute, so the shadow uses offset + edge-
  feather instead. Reads as a soft contact band; a true blurred spread needs a
  centroid attribute — flagged, not hacked.
- **A separate shadow geometry/program owning its own buffers.** Unnecessary —
  `PieceShadow` reuses `BodyMesh`'s VAO/IBO by binding its own program and
  calling `mesh.draw()`, since the draw is program-agnostic.
- **Reducing per-archetype grain frequencies to look smoother.** Rejected — that
  is the CVD identity cue. I lowered only the global grain *amplitude*
  (`GRAIN_GAIN`), leaving the frequency ordering intact.

## Open questions / owed on-device tune (all first-pass, client steers)

1. **Corner rounding is the top steer.** Fade-to-tray softens stacked corners but
   makes an airborne piece against the bright sky show a faint dark corner
   fringe. I dialed `CORNER_GAIN` back to 0.7 to keep it gentle. "How rounded,
   and is the airborne fringe acceptable" is a client + on-device call. If the
   fringe is unwanted, the honest alternatives all cost more (a per-pixel
   background-aware fade, or a blended edge) — worth raising before polishing.
2. **Sky glow-patch brightness (§19)** — sane first-pass values, NOT measured on
   the panel. A bright additive patch on a bright field under-reads the opposite
   way the old dark-on-dark discs did (the same failure mode the team hit once).
   Owed a real on-device measurement.
3. **Shadow spread** — offset + feather only; softer spread is a follow-up.
4. **MSAA + shadow + full-screen background frame cost** — this round stacks
   three real new costs on a device already at 15.0ms/16.67ms. Only the phone's
   frame-time readout can price them; the emulator cannot. The cut order is in
   the code (MSAA→2×→off; shadow cut at low tiers).
5. **Body-UV v-axis orientation** — the specular is biased upper-left assuming a
   given v-axis direction. In the emulator frames it reads upper-region; if on
   the device the glint sits lower, negate `SPEC_DIR.y`/mirror `SPEC_CENTER.y`
   (noted in `Shaders.kt`). One-line fix, flagged so it is checked deliberately.

## Uneasy about

- The **specular v-axis assumption** above — I could not fully confirm the body-UV
  vertical orientation from the emulator alone.
- **Grain vs. gloss balance.** I lowered grain amplitude for the glossy read; if
  the client or `piece-identity.md`'s owner wants the identity grain stronger,
  that trades against the smooth-candy look. A conscious dial, flagged.

---
*Handoff from the **Frontend Engineer**.*
