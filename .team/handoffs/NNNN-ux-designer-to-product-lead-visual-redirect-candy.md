# UX Designer → Product Lead: glossy jelly candy visual redirect

**Branch:** `docs/visual-redirect-candy` (worktree `/work/.worktrees/visual-redirect-candy`)
**PR:** #29, watched through CI (see status note at the end)
**Commit:** `19213aa` — `docs(ux): redirect to glossy jelly candy on a light background`

## What I did

Rewrote/extended `docs/ux/visual-direction.md` (new "Part two," §11-22, appended
after the existing §1-10 which are kept verbatim as the record of the round-2
direction that shipped and was then rejected) and `docs/ux/tokens.md` (new
"Light candy world" colour section plus a new "Material" token category),
covering everything in the dispatch:

- **Real research, not memory.** Per the client's instruction ("kijk even hoe
  candy crush eruit ziet"), I fetched and *viewed* four current, full-resolution
  (1080×2340) Candy Crush Saga screenshots directly — via APKPure's mirror of
  the Google Play listing (`image-eo.winudf.com`), because the App Store
  listing page renders its screenshot gallery client-side and a text-mode
  fetch cannot see it. Plus one older, low-res Wikipedia screenshot, viewed as
  a cross-check only. §12 cites exactly what was seen: the hard, elongated,
  upper-left-biased specular highlight; the flat, highly-saturated base body
  doing almost no shading work of its own; the soft, tray-coloured (not black)
  contact shadow under every candy; the muted slate-blue tray against a bright
  saturated scene; small sparkle accents; cream-outlined pink HUD chrome.
- **§13 is the client-facing "target look" summary** you asked for — written
  to be lifted and shown to the client before Frontend builds §14-19. Please
  relay a distilled version and get that confirmation before dispatching
  Frontend on the implementation sections.
- **§14 material model**: kept the existing subsurface term (it's already the
  right physics for "light passing through," just needs a higher gain), added
  one new specular-highlight term (fixed light-direction dot product +
  smoothstep, same cost class as the existing rim term), retuned the existing
  rim/fresnel term warmer. True alpha transparency explicitly considered and
  rejected — costed against the renderer's one-draw-call, no-blend
  architecture.
- **§15 promotes backlog D10 from deferred to required**: `particleU`/
  `particleV` (`SoftBodyWorld.kt:431-434`, cell-local today) must become
  genuinely body-local. One data-path change fixes three things at once: the
  grain (the client's literal complaint), the subsurface depth term, and the
  new specular sweep — all read as one continuous piece instead of resetting
  per cell.
- **§16 rounded corners**: a new `particleCorner` contract field, vertex-
  interpolated exactly like the existing `vEdge`, computed once at spawn from
  `PieceShapes.neighbour()` (already answers "does this cell have a neighbour
  in this direction," already internal to `:core-sim`). Deliberately whole-
  piece-aware, not per-cell — per-cell rounding would pinch/notch at
  same-piece cell seams and reintroduce the "four separate squares" read.
  Fades toward `color-tray`, never the sky (a piece's corner is adjacent to
  the tray/another piece almost everywhere it appears in the well, never to
  the outer background).
- **§17 antialiasing**: recommended hardware MSAA on the EGL surface config
  (not a shader-side technique — keeps `Shaders.kt`'s no-blend/no-discard
  rules intact). Costed explicitly (~4x attachment bandwidth + resolve pass)
  and recommended folding into the existing `uShadeTier` ladder rather than a
  separate quality dial.
- **§18 soft shadows**: a second cheap draw pass reusing `BodyMesh`'s existing
  vertex buffer (offset + outward scale in the vertex shader, flat
  `color-shadow` fragment), blend enabled only for this one pass — the one
  place in this whole document blending is the right trade. Costed (roughly
  doubles vertex throughput for body geometry, one extra state change).
- **§19 background**: same `Background.kt` architecture (one full-screen
  quad, O(screen pixels), CPU-hoisted drift), recoloured to a light sky
  gradient. Flagged that the glow-drift discs need fresh on-device brightness
  measurement — retinting the old peak-opacity numbers bright-on-bright will
  under-read the same way the old dark-on-dark bug did, in the opposite
  direction.
- **§20-21 consequences flagged, not resolved**: `band-glow.md`'s additive
  amber glow will likely under-read against a light background (real, needs a
  pass, not designed here); HUD text contrast flips dark-on-light with a new
  `color-hud-chip` token (done in `tokens.md`); `piece-identity.md`,
  `screens/playing.md`, `accessibility.md` need small follow-up edits, listed
  but not made in this branch (they don't block Frontend starting).

The seven piece hues are **unchanged** (still CVD-designed, still
colourblind-safe) — only the material rendering them changes, and I checked
that call rather than asserting it: all seven sit at L 40-56%, comfortably
below the new sky's L ~90%+, so the hue-separation argument that built them
holds regardless of background lightness.

## What I deliberately did not do

- **No app code.** This is direction only, exactly as dispatched.
- **No band-glow.md redesign.** I flagged that its additive model needs
  revisiting against a light background (§20) but did not redesign the glow
  curve — that's real scope, owned by whoever next opens that document, and
  redesigning it here would have been scope creep past what was asked.
- **No edits to `piece-identity.md`, `screens/playing.md`, `accessibility.md`**
  beyond what I did inside `tokens.md` itself (the contrast/chip-backing fix
  lives there). All three need small follow-up edits (§21 lists exactly
  what) — I judged them non-blocking cleanup, not part of getting Frontend
  unstuck, and said so in the doc rather than quietly leaving it undocumented.
- **Did not decide the MSAA sample count or the shadow pass's default tier.**
  Both are flagged as on-device measurement calls for the Frontend Engineer,
  following the exact discipline `Shaders.kt`'s own tier table already uses —
  not mine to guess at.

## What I could not do as instructed

**I tried to message the Frontend Engineer and Backend Engineer directly**
(tried `frontend-engineer`, `backend-engineer`, `Frontend Engineer`,
`Backend Engineer`) — SendMessage reports none of them reachable; no such
agents are currently live in this session tree. I've sent the full content to
`main` for you to relay or load when you dispatch them, and it's fully
written out below so it isn't lost if that message doesn't land:

**To the Frontend Engineer**, once the client confirms §13, roughly in build
order: (1) §14 material retune + new specular term, no Backend dependency;
(2) §16 rounded corners, once `particleCorner` exists; (3) §17 MSAA,
on-device cost call; (4) §18 shadow pass; (5) §19 background recolour, with
fresh brightness measurement on the glow discs, not a retint.

**To the Backend Engineer** (Architect looped for contract wording): §15 —
promote D10, make `particleU`/`particleV` body-local
(`SoftBodyWorld.kt:431-434`), fold a per-archetype frequency-compensation
factor into the existing `uGrainScale` array. §16 — new `particleCorner`
field, computed from `PieceShapes.neighbour()`. Recommended as one PR, since
both need the same whole-piece awareness.

## Considered and rejected

- **Per-cell corner rounding** (round every cell's four corners
  independently) — rejected because it visibly pinches at same-piece cell
  seams, reintroducing the exact "four separate squares" read this whole
  redirect exists to fix. Whole-piece-aware rounding (§16) was the only
  option that doesn't fight D10's own fix.
- **Fading rounded corners toward the sky background** instead of the tray —
  rejected because a piece's corner is adjacent to the tray/another piece
  almost everywhere in the well and essentially never adjacent to the outer
  sky (which only shows in the well's empty space above the stack); fading
  toward the sky would look right only at the very top of an empty well and
  visibly wrong everywhere else. Fading toward the fixed `color-tray`
  constant is both cheaper (no position-dependent sampling) and more
  correct.
- **True alpha-blended transparency for the jelly body** — rejected on the
  same architectural grounds `Shaders.kt` already gives for keeping blending
  off everywhere else: it would need per-frame depth sorting of overlapping
  translucent geometry, which the single-draw-call renderer doesn't have.
  The existing subsurface term, gain raised, is the honest cheap proxy.
- **Shader-side edge antialiasing** (distance-field/supersampled alpha)
  instead of MSAA — rejected because it would need blending/discard exactly
  where `Shaders.kt`'s header already argues against both; MSAA operates on
  opaque coverage and needs neither.
- **A general-purpose alpha-blending system** for the shadow pass rather than
  a one-off — rejected as unnecessary scope; the shadow is a single low-
  complexity pass, not a reason to open blending up generally.

## Open questions for the client (relay via §13)

- Does the §13 target-look description match what they pictured when they
  said "glassy, glossy, transparent, candy-stick-coloured"? This is the one
  thing I explicitly deferred building against until confirmed.

## Uneasy about

- **§17/§18's stacked cost is a real, unmeasured risk.** MSAA + the shadow
  pass + the recoloured full-screen background pass are three simultaneous
  new GPU costs on a device whose entire remaining frame budget was already
  being measured in fractions of a millisecond as of round 2's shipped
  shader. Individually each is defensible; I flagged (§19, closing paragraph)
  that stacked, they're the real open risk — not resolved, because it needs
  an on-device number I can't produce from here. Recommend this gets an
  early on-device pass before Frontend builds all five sections end to end,
  the same "measure early" discipline the team has used every round so far.
- **I have not re-run the CVD check against the new material.** The seven
  hues are unchanged and the argument for why the pivot doesn't affect them
  holds on paper (§21 restates it), but "on paper" is exactly the caveat
  `piece-identity.md` already carries for the original six — someone should
  still run the actual glossy/lit render through a CVD simulator before a
  release gate, not assume the paper argument is enough twice.

## CI status

Filed after PR #29 was opened; monitoring it now. Will update this handoff or
the journal if the pipeline goes red or a review comment lands, per the "watch
your own PR" rule.
