# Glossy jelly candy render layer — money shots for the client

Frontend Engineer, 2026-07-21. Branch `feat/candy-material-render` (from
`feat/candy-material-datapath`, PR #30). These are for the Product Lead to view
and show the client — the round-3 glossy-candy look the client is waiting to
judge (`visual-direction.md` §13–§19).

**Correctness only — NOT an appearance or performance claim about the real
device.** Captured on the software emulator (`-gpu swangle`, ANGLE-over-
SwiftShader). Colour, gloss contrast, MSAA edge quality and the glow-patch and
shadow brightness will all read differently on the client's Fairphone 6 OLED —
several values below are explicitly first-pass and owed an on-device tune. What
these frames DO prove: it launches, it renders, and the four asks read.

## The frames

| File | What it shows |
|---|---|
| `01-light-candy-world-empty.png` | The light candy world (§19): soft sky-blue → warm cream gradient, calm slate tray. Replaces the old dark-indigo canvas. |
| `02-falling-piece-glossy-glint.png` | A falling rose J — one hard near-white specular glint sweeping the whole piece, translucent body, smooth surface (§14). Reads as one connected candy, not four tiles. |
| `03-stack-cyan-on-green.png` | A cyan O resting on a green I — gloss glint on the O, both read as single connected candies, contact between them. |
| `04-spawn-with-soft-shadow.png` | An azure piece with a soft tray-coloured shadow band beneath it (§18). |
| `05-connected-L-and-I.png` | A green L: one continuous grain/highlight across all cells (the "four separate squares" complaint is fixed, §15), softened outer corners (§16). |
| `06-settled-candy-on-tray.png` | A settled cyan candy sitting on the tray — glossy, translucent, grounded. |

## Does it hit the definition of done?

- **Glossy** — yes: a hard, high-contrast near-white specular streak (§14).
- **Connected candy, not four tiles** — yes: body-wide UV means one continuous
  grain/highlight/subsurface across the whole piece (§15, the client's literal
  complaint).
- **Rounded outer corners** — yes but subtle and the most tuning-dependent item;
  see open questions.
- **Translucent body** — yes: the raised subsurface term reads as jelly depth.
- **Soft shadow** — yes, reads in mid-air/stacked configs; subtler for a piece
  flush on the floor (physically correct). A softer *spread* is a flagged
  follow-up.
- **Light candy background** — yes.

## Open questions for the client / on-device tune (see handoff 0037)

1. **Corner rounding vs. the sky.** Corners fade toward the tray colour
   (`corner-fade-mode`, §16's chosen compromise). Against the tray/other pieces
   this softens the corner; on an *airborne* piece against the bright sky the
   same fade reads as a faint dark corner fringe. Gain dialed back to keep it
   gentle — but "how rounded" is the top client steer.
2. **Glow-patch brightness (§19).** The two sky glow patches are sane first-pass
   values, NOT measured on the panel — a bright additive patch on a bright field
   under-reads the opposite way the old dark-on-dark discs did.
3. **Shadow spread (§18).** Offset + edge-feather only; no per-body-centroid
   outward scale (the mesh carries no centroid). A soft blurred spread is the
   follow-up if the client wants more.
4. **MSAA cost (§17).** 4× requested with graceful fallback. Its real bandwidth
   cost, stacked with the shadow pass and the full-screen background, is only
   measurable on the phone via the frame-time readout.
5. **Palette is a first pass** — hues unchanged from `piece-identity.md`; the
   client steers colours next.
