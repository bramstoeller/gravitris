import { writeFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { TETROMINOES, outlinePath } from './shape.mjs';
import { HUES, materialStops, COATING_SHIMMER } from './color.mjs';
import { mostLitCell } from './light.mjs';
import { renderPiece } from './piece.mjs';
import { shoot } from './shoot.mjs';

const W = 2200, H = 3050;
const cellSize = 220;
const pageX = 616, pageY = 300; // where the annotated piece's own (0,0) sits, comfortably clear of every label

// T, not O: a notched piece is the only way to show the concave self-
// occlusion AO (an O has no concave vertex to demonstrate it on).
const cells = TETROMINOES.T.cells;
const margin = 0.55;
const marginPx = margin * cellSize;
const shadowDy = cellSize * 0.24;
const cornerRatio = 0.38; // must match renderPiece()'s own default below, or the
                          // concave-vertex callout points at a corner rounder or
                          // sharper than the one actually rendered

// Anchor points, computed exactly the way piece.mjs computes them
// internally (not re-eyeballed): mostLitCell() -- the same fixed-light-
// direction projection every rendered piece uses, not a bounding-box
// fraction -- for the highlight cluster, the cell-count centroid for the
// core glow, and outlinePath()'s own returned concave-vertex list for the
// AO callout -- the single source of truth piece.mjs itself draws from.
const [ac, ar] = mostLitCell(cells);
const ax = pageX + (ac + 0.5) * cellSize + marginPx;
const ay = pageY + (ar + 0.5) * cellSize + marginPx;
const centreC = cells.reduce((s, c) => s + c[0], 0) / cells.length;
const centreR = cells.reduce((s, c) => s + c[1], 0) / cells.length;
const cx = pageX + (centreC + 0.5) * cellSize + marginPx;
const cy = pageY + (centreR + 0.5) * cellSize + marginPx;
const { concave } = outlinePath(cells, cellSize, cellSize * cornerRatio, margin);
const [aoLocalX, aoLocalY] = concave[0];
const aoX = pageX + aoLocalX, aoY = pageY + aoLocalY;
// The T's stem cell (1,1) -- picked to show the ambient per-cell fill
// somewhere other than the lit anchor, since its whole point is that every
// filled cell gets one, not only the one the light hits first.
const stemX = pageX + 1.5 * cellSize + marginPx;
const stemY = pageY + 1.5 * cellSize + marginPx;
// A point on the plain right edge of the top bar -- for the fresnel and
// bevel callouts, which are boundary effects, not corner or centre ones.
const edgeX = pageX + 2 * cellSize + marginPx;
const edgeY = pageY + 0.5 * cellSize + marginPx;

const { svg, width, height } = renderPiece(cells, HUES.T.base, { cellSize });

// Eight callouts, in the same order as the layer list in piece.mjs and the
// README. Each anchor (x,y) is one of the exact points above -- never a
// guessed fraction of the bounding box -- and each label position (lx,ly)
// was checked against the piece's actual rendered footprint (pageX/pageY +
// width/height below) so no line crosses another label or the candy itself.
const callouts = [
  { x: ax - cellSize*0.06, y: ay - cellSize*0.42, lx: 90, ly: 230, align: 'left',
    title: '1. Specular gleam + coating sliver + sparkle', body: 'A soft, broad white ellipse (satin, not a hard glass streak -- lower peak opacity and more blur than attempt 4\'s), rotated -24°, screen-blended, centred on mostLitCell() -- whichever filled cell the one fixed light direction reaches first (see "World-anchored lighting" below), never a bounding-box fraction. A thin pale-cyan sliver rides its edge -- one fixed "wet coating" tint shared by all seven hues. A tiny bright dot at the tip still sells "wet" at a glance.' },
  { x: ax + cellSize*0.62, y: ay - cellSize*0.30, lx: W - 90, ly: 360, align: 'right',
    title: '2. Bloom halo', body: 'A second, much larger and softer white radial sits behind the gleam, screen-blended at low opacity -- the bright-pass-and-blur a real bloom pass performs. Eased back from attempt 4\'s strength this round so it reads as a soft satin sheen, not a glass highlight, once stacked with the richer subsurface layers below.' },
  { x: ax + cellSize*0.10, y: ay + cellSize*0.30, lx: 90, ly: 700, align: 'left',
    title: '3. Subsurface glow + warm core pool', body: 'Both pushed harder, per the winegum brief: the highlight-corner glow is now tinted with the piece\'s own bright stop instead of flat white (translucent light picks up the medium\'s colour, it doesn\'t stay neutral), and the centroid-anchored core pool\'s opacity and radius are both up -- "light gathering where the gel is thickest" is now the dominant read, not a subtle accent.' },
  { x: stemX, y: stemY, lx: W - 90, ly: 700, align: 'right',
    title: '4. Ambient per-cell fill (new)', body: 'One small soft blob of the same bright-tinted colour, per filled cell, at low opacity -- so the far side of a multi-lobe piece (an L\'s foot, an S\'s other lobe) still gets some subsurface lift even though it sits nowhere near the lit corner or the centroid. Sized per-cell rather than to the whole bounding box on purpose: a bbox-sized version was tried first and washed a piece\'s own single-cell-wide arm out almost to white (found by rendering, not assumed) -- a real thick gel scatters light through its whole body, but a THIN cross-section still only has a little of it to scatter.' },
  { x: edgeX, y: edgeY, lx: 90, ly: 1300, align: 'left',
    title: '5. Fresnel / edge-translucency (new)', body: 'A thin, brighter band right at the silhouette boundary itself, screen-blended -- a thin cross-section passes more light than a thick one under the same light, so a real gummy\'s own edge rims brighter than its middle. Thinner and less blurred than the bevel below, which sits a little further in, so the two read as distinct: a bright rim, then a darker crease just inside it.' },
  { x: edgeX + cellSize*0.02, y: edgeY + cellSize*0.55, lx: W - 90, ly: 1300, align: 'right',
    title: '6. Chunky-3D bevel (new)', body: 'A stroke on the piece\'s own outline, half of it clipped away by that same outline, leaving an inset dark band hugging the INSIDE of every edge -- top, sides and bottom alike, not just the bottom-anchored AO below. This is the "soft inner shadow that reads as thickness" the brief asked for: a real self-shadow where a thick chewy mass turns the corner at its own silhouette, on every side, not only where it sits on the tray.' },
  { x: aoX, y: aoY, lx: 90, ly: 1870, align: 'left',
    title: '7. Concave self-occlusion AO', body: 'Unchanged from attempt 4: every reflex (concave) corner of a multi-lobe piece -- where an S/T/L/J/Z\'s two lobes meet -- gets a soft dark radial, nudged inward from outlinePath()\'s own returned concave-vertex list. A real self-shadow in the crease, the same thing ambient occlusion computes at any concave joint on a deforming mesh.' },
  { x: ax - cellSize*0.55, y: ay + cellSize*1.55 + shadowDy, lx: W - 90, ly: 1870, align: 'right',
    title: '8. Base gradient + rim + contact shadow', body: 'Unchanged: body is a linear top-light to bottom-deep gradient doing almost no shading of its own (matches the genre); rim is a thin light-to-deep stroke on the silhouette for the glassy edge line; contact shadow is the same silhouette, warm tray-tinted (never pure black), offset down and heavily blurred, grounding the piece without a hard edge. The corner radius itself is up (0.30 → 0.38 of a cell) for a chunkier, more pronounced bevel silhouette.' },
];

// A straight line from a piece anchor to a label can end up slicing
// diagonally across the label's own paragraph if it approaches from the
// side the text sits on -- found by rendering and looking, not assumed
// (the very failure mode this whole codebase's naming convention warns
// about). The fix: connect to whichever edge of the label block -- its
// top or its bottom -- the anchor is already on the outside of, so the
// line's y stays monotonically outside the block's own y-range for its
// entire path and only touches it at the final corner.
const LABEL_WIDTH = 460;
const TITLE_H = 46, BODY_LINE_H = 24, CHARS_PER_LINE = Math.floor(LABEL_WIDTH / 9.2);
function blockHeight(body) {
  const lines = Math.ceil(body.length / CHARS_PER_LINE);
  return TITLE_H + lines * BODY_LINE_H;
}
const routed = callouts.map(c => {
  const top = c.ly - 14;
  const bottom = top + blockHeight(c.body);
  const mid = (top + bottom) / 2;
  const useBottom = c.y > mid; // anchor is below the block -> connect at its bottom
  const ty = useBottom ? bottom : top;
  return { ...c, ty };
});

const calloutSvg = routed.map(c => `
  <line x1="${c.x.toFixed(1)}" y1="${c.y.toFixed(1)}" x2="${c.lx.toFixed(1)}" y2="${c.ty.toFixed(1)}" stroke="#8A5A9A" stroke-width="2.5" stroke-dasharray="1 7" stroke-linecap="round"/>
  <circle cx="${c.x.toFixed(1)}" cy="${c.y.toFixed(1)}" r="8" fill="#8A5A9A"/>
  <circle cx="${c.lx.toFixed(1)}" cy="${c.ty.toFixed(1)}" r="4" fill="#8A5A9A"/>
`).join('\n');

const calloutLabels = routed.map(c => {
  const left = c.align === 'left' ? c.lx : c.lx - LABEL_WIDTH;
  return `
  <div style="position:absolute;left:${left}px;top:${c.ly - 14}px;width:${LABEL_WIDTH}px;${c.align === 'right' ? 'text-align:right;' : ''}">
    <div style="font-family:'Fredoka',sans-serif;font-weight:600;font-size:24px;color:#5B3A73;">${c.title}</div>
    <div style="font-family:'Inter',sans-serif;font-size:16px;line-height:1.5;color:#7A6B85;margin-top:6px;">${c.body}</div>
  </div>`;
}).join('\n');

function swatch(label, hex) {
  const m = materialStops(hex);
  return `
  <div style="display:flex;flex-direction:column;align-items:center;gap:8px;">
    <div style="width:126px;height:66px;border-radius:14px;overflow:hidden;display:flex;box-shadow:0 6px 14px rgba(120,70,30,0.15);">
      <div style="flex:1;background:${m.light}"></div>
      <div style="flex:1;background:${m.base}"></div>
      <div style="flex:1;background:${m.deep}"></div>
    </div>
    <div style="font-family:'Fredoka',sans-serif;font-size:17px;font-weight:600;color:#5B3A73;">${label}</div>
    <div style="font-family:'JetBrains Mono',monospace;font-size:13px;color:#B98CC4;">${hex}</div>
  </div>`;
}

const neutrals = [
  ['Sky top', '#EAF1FF'], ['Sky bottom', '#FFE6D6'],
  ['Well cream', '#FFFBF3'], ['Well deep', '#F4E1C4'],
  ['Score pink', '#FF3D77'], ['Level purple', '#8A4FE0'],
  ['Coating sliver', COATING_SHIMMER],
];

const paletteRow = Object.entries(HUES).map(([k, v]) => swatch(`${k} · ${v.name}`, v.base)).join('\n');
const neutralRow = neutrals.map(([label, hex]) => `
  <div style="display:flex;flex-direction:column;align-items:center;gap:8px;">
    <div style="width:126px;height:66px;border-radius:14px;background:${hex};box-shadow:0 6px 14px rgba(120,70,30,0.15), inset 0 0 0 1px rgba(0,0,0,0.06);"></div>
    <div style="font-family:'Fredoka',sans-serif;font-size:17px;font-weight:600;color:#5B3A73;">${label}</div>
    <div style="font-family:'JetBrains Mono',monospace;font-size:13px;color:#B98CC4;">${hex}</div>
  </div>`).join('\n');

const html = `<!doctype html><html><head><meta charset="utf-8">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Fredoka:wght@500;600;700&family=Inter:wght@400;500&family=JetBrains+Mono:wght@500&display=swap" rel="stylesheet">
<style>
  *{box-sizing:border-box}
  html,body{margin:0;padding:0;width:${W}px;height:${H}px;overflow:hidden;position:relative;}
  body{background:linear-gradient(160deg,#F7F3FB 0%, #FDF6EE 100%);}
  h1{position:absolute;left:90px;top:50px;font-family:'Fredoka',sans-serif;font-size:36px;color:#5B3A73;margin:0;}
  .subtitle{position:absolute;left:90px;top:104px;font-family:'Inter',sans-serif;font-size:17px;color:#8A7A95;max-width:900px;line-height:1.5;}
  .light-note{position:absolute;right:90px;top:88px;width:640px;background:#FFFFFF;border-radius:18px;padding:18px 24px;
    box-shadow:0 10px 24px rgba(120,70,110,0.10);}
  .light-note .lt{font-family:'Fredoka',sans-serif;font-weight:600;font-size:19px;color:#5B3A73;}
  .light-note .lb{font-family:'Inter',sans-serif;font-size:14.5px;line-height:1.5;color:#7A6B85;margin-top:6px;}
  .section-title{font-family:'Fredoka',sans-serif;font-size:23px;font-weight:600;color:#5B3A73;position:absolute;left:90px;}
  .palette{position:absolute;left:0;top:2500px;width:100%;display:flex;flex-wrap:wrap;gap:26px;padding-left:90px;max-width:1900px;}
  .neutrals{position:absolute;left:0;top:2740px;width:100%;display:flex;flex-wrap:wrap;gap:26px;padding-left:90px;max-width:1900px;}
</style></head><body>
  <h1>Candy Material — Recipe &amp; Palette</h1>
  <div class="subtitle">v3 "winegum" pass: dense, chewy, translucent -- built from gradients, blurs and blend modes only, every layer numbered below is something a fragment shader can do on a deformable mesh.</div>
  <div class="light-note">
    <div class="lt">World-anchored lighting</div>
    <div class="lb">Every lit effect above reads one fixed light direction (tools/light.mjs, ~21° off vertical, upper-left) -- a PAGE constant, not a per-piece one. A piece's own rotation changes its cells, never the light. See 04-rotation-strip.png for the same piece in all four quarter-turns, proving the highlight stays in the same screen corner instead of spinning with the piece.</div>
  </div>
  <div style="position:absolute;left:${pageX}px;top:${pageY}px;">${svg}</div>
  <svg style="position:absolute;left:0;top:0;pointer-events:none;" width="${W}" height="${H}">${calloutSvg}</svg>
  ${calloutLabels}
  <div class="section-title" style="top:2450px;">Piece hues (candy-saturated, genre-conventional mapping)</div>
  <div class="palette">${paletteRow}</div>
  <div class="section-title" style="top:2690px;">Neutrals</div>
  <div class="neutrals">${neutralRow}</div>
</body></html>`;

const __dirname = dirname(fileURLToPath(import.meta.url));
const outHtml = join(__dirname, 'styleboard.html');
const outPng = join(__dirname, '..', '03-style-board.png');
writeFileSync(outHtml, html);
shoot(outHtml, outPng, W, H);
console.log('wrote', outPng);
