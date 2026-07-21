# Glossy jelly candy render layer — money shots for the client

Frontend Engineer, 2026-07-21. Branch `feat/candy-material-render` (from
`feat/candy-material-datapath`, PR #31). Refreshed after the Product Lead's
review of the first pass. For the Product Lead to view and show the client.

**Correctness only — NOT an appearance or performance claim about the real
device.** Captured on the software emulator (`-gpu swangle`). Colour, gloss
contrast, MSAA/rounding quality and shadow/glow brightness all read differently
on the client's Fairphone 6 OLED — several values are first-pass, owed a tune.

## What changed since the first review (the PL's three asks)

1. **Rounded corners — now visible.** Replaced the fade-to-tray (which read as a
   dark corner smudge against the sky) with MSAA alpha-to-coverage: true
   silhouette corners are softly rounded against ANY background — sky, tray, or
   another piece. Verify on the O (all four corners round) and the L (rounds the
   outline, stays sharp at the inner elbow). No blend, no discard.
2. **Soft shadow — now reads.** Darkened the tray-toned shadow and enlarged the
   offset so it's clearly visible under a piece against the light tray and sky.
3. **Gloss on all pieces — widened.** The specular streak is broader so large
   pieces (I, L) get a visible glint, not just the compact/blue ones.

## The frames

| File | What it shows |
|---|---|
| `01-light-candy-world-empty.png` | The light candy world (§19). |
| `02-L-rounded-corners-sharp-elbow-shadow.png` | Green L: outer corners rounded, **inner elbow stays sharp**, soft shadow beneath. |
| `03-O-rounded-corners-shadow-and-remaining-seam.png` | Cyan O falling: four rounded corners + shadow. **Also shows the one thing still broken** — the internal "+" seam (see below). |
| `04-settled-rounded-with-shadows.png` | Settled pieces: rounded, translucent, grounded by shadows. |

## The one remaining defect — and why it's not mine to fix

The internal cell seam (the dark "+" splitting the O into four) is **still
there**. I traced it to source: it is NOT edge or contact darkening (both are
correctly 0 on internal seams — verified at `SoftBodyWorld.kt:252` and
`XpbdSolver.kt:449`). It is a **mesh-topology defect**: the render mesh is
per-cell with no geometry bridging two cells, so abutting cells carry a
one-grid-step UV discontinuity across every internal seam, and the grain/specular
step across it.

This needs the Backend Engineer's mesh (seam-bridging geometry, or a unified
tetromino lattice) — I can't fix it in the shader, and doing the topology change
solo the day of a client demo is the wrong risk. Full diagnosis with line
numbers is in **handoff 0038** (frontend → backend). The rounded outer corners
do soften the overall "four squares" gestalt, but the internal cross is honest
in frame 03 so you can judge whether to show the client now or hold for the mesh
fix.

## Still first-pass / owed on-device (handoff 0037)

- Sky glow-patch brightness (§19) — not measured on the panel.
- Specular is still somewhat shape/position-dependent (single fixed streak).
- MSAA + shadow + full-screen background frame cost — only the phone can price it.
- Palette hues unchanged; the client steers colours next.
