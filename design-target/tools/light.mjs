// The single fixed "key light" every material effect reads from.
//
// World/screen-space, y-down: mostly from directly above, ~21 degrees
// toward the left -- matches the existing body gradient's own top-to-
// bottom-right axis (x1=0,y1=0 -> x2=0.4,y2=1) and the specular gleam's
// -24 degree screen-space tilt. This is a PAGE constant, not a per-piece
// one: a piece's own rotation must never change which way the light comes
// from -- only the piece's own cells (which grid cells it occupies) change
// when it rotates. Anything that decides "which side is lit" reads this
// one value, so a highlight can never accidentally spin with the piece
// it's drawn on -- see README "World-anchored lighting" for the full rule
// and what it means for the real per-fragment shader.
const LX = -0.36, LY = -0.93;
const LEN = Math.hypot(LX, LY);
export const LIGHT_DIR = Object.freeze({ x: LX / LEN, y: LY / LEN });

// Picks whichever of a piece's own filled cells is most "toward" the fixed
// light direction, measured from the piece's own centroid -- replaces the
// previous "topmost row, then leftmost column" tie-break. That tie-break
// happened to agree with the light direction for every piece's resting
// (spawn) orientation, purely because it was derived from the same
// top-to-bottom, left-to-right grid order the light also happens to lean
// toward -- but it was never actually reading the light vector, so nothing
// guaranteed it would keep agreeing once a piece's cells were reshuffled by
// a quarter-turn. Scoring every candidate cell against LIGHT_DIR itself is
// the version that actually generalizes across every rotation: it always
// resolves to "whichever real, filled part of this piece the fixed light
// would reach first," which is the one thing a rotation must never change.
// It still only ever picks a cell that is actually filled, so the highlight
// can never land in the clipped-away empty space of an asymmetric piece's
// own bounding box (the original bug the cell-anchor approach itself was
// built to fix -- see piece.mjs's own comment on that).
export function mostLitCell(cells) {
  const cx = cells.reduce((s, c) => s + c[0], 0) / cells.length + 0.5;
  const cy = cells.reduce((s, c) => s + c[1], 0) / cells.length + 0.5;
  let best = cells[0], bestScore = -Infinity;
  for (const [c, r] of cells) {
    const x = c + 0.5, y = r + 0.5;
    const score = (x - cx) * LIGHT_DIR.x + (y - cy) * LIGHT_DIR.y;
    if (score > bestScore) { bestScore = score; best = [c, r]; }
  }
  return best;
}
