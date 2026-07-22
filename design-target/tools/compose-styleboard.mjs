import { writeFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { TETROMINOES, outlinePath } from './shape.mjs';
import { HUES, materialStops, COATING_SHIMMER } from './color.mjs';
import { renderPiece } from './piece.mjs';
import { shoot } from './shoot.mjs';

const W = 2200, H = 2500;
const cellSize = 220;
const pageX = 616, pageY = 300; // where the annotated piece's own (0,0) sits, comfortably clear of every label

// T, not O: a notched piece is the only way to show the concave self-
// occlusion AO (an O has no concave vertex to demonstrate it on).
const cells = TETROMINOES.T.cells;
const margin = 0.55;
const marginPx = margin * cellSize;
const shadowDy = cellSize * 0.24;

// Anchor points, computed exactly the way piece.mjs computes them
// internally (not re-eyeballed): the topmost-then-leftmost filled cell for
// the highlight cluster, the cell-count centroid for the core glow, and
// outlinePath()'s own returned concave-vertex list for the AO callout --
// the single source of truth piece.mjs itself draws from.
const sorted = [...cells].sort((a, b) => (a[1] - b[1]) || (a[0] - b[0]));
const [ac, ar] = sorted[0];
const ax = pageX + (ac + 0.5) * cellSize + marginPx;
const ay = pageY + (ar + 0.5) * cellSize + marginPx;
const centreC = cells.reduce((s, c) => s + c[0], 0) / cells.length;
const centreR = cells.reduce((s, c) => s + c[1], 0) / cells.length;
const cx = pageX + (centreC + 0.5) * cellSize + marginPx;
const cy = pageY + (centreR + 0.5) * cellSize + marginPx;
const radius = cellSize * 0.30;
const { concave } = outlinePath(cells, cellSize, radius, margin);
const [aoLocalX, aoLocalY] = concave[0];
const aoX = pageX + aoLocalX, aoY = pageY + aoLocalY;

const { svg, width, height } = renderPiece(cells, HUES.T.base, { cellSize });

// Six callouts. Each anchor (x,y) is one of the exact points above -- never
// a guessed fraction of the bounding box -- and each label position (lx,ly)
// was checked against the piece's actual rendered footprint (pageX/pageY +
// width/height below) so no line crosses another label or the candy itself.
const callouts = [
  { x: ax - cellSize*0.06, y: ay - cellSize*0.42, lx: 90, ly: 260, align: 'left',
    title: '1. Specular gleam + coating sliver + sparkle', body: 'Hard white ellipse (~58%×24% of a cell), rotated -24°, screen-blended, centred on the topmost-left filled CELL. A thin pale-cyan sliver rides its edge — one fixed "wet coating" tint shared by all seven hues, not a per-piece colour. A tiny bright dot at the tip sells "wet" at a glance.' },
  { x: ax + cellSize*0.62, y: ay - cellSize*0.30, lx: W - 90, ly: 260, align: 'right',
    title: '2. Bloom halo', body: 'A second, much larger and softer white radial sits behind the gleam, screen-blended at low opacity. This is the bright-pass-and-blur a real bloom pass performs — the gleam now looks like it is genuinely overexposing the surface, not just a sticker.' },
  { x: ax + cellSize*0.10, y: ay + cellSize*0.30, lx: 90, ly: 950, align: 'left',
    title: '3. Subsurface glow + warm core pool', body: 'The existing large soft white radial (translucency proxy) is joined by a second, warmer radial tinted with the piece\'s own deep hue, centred on the piece\'s cell-count centroid rather than its highlight corner — light "pooling" where the gel is thickest.' },
  { x: aoX, y: aoY, lx: W - 90, ly: 950, align: 'right',
    title: '4. Concave self-occlusion AO', body: 'Every reflex (concave) corner of a multi-lobe piece — where an S/T/L/J/Z\'s two lobes meet — gets a soft dark radial, nudged inward from outlinePath()\'s own returned concave-vertex list. This is a real self-shadow in the crease, the same thing ambient occlusion computes at any concave joint on a deforming mesh.' },
  { x: ax - cellSize*0.55, y: ay + cellSize*0.85, lx: 90, ly: 1600, align: 'left',
    title: '5. Base gradient + rim', body: 'Body: linear, top-light → bottom-deep, ~20° off vertical, doing almost no shading of its own (matches the genre). Rim: a thin stroke on the silhouette itself, light at the top and deep at the bottom, for the glassy edge line.' },
  { x: ax + cellSize*0.15, y: ay + cellSize*1.55 + shadowDy, lx: W - 90, ly: 1600, align: 'right',
    title: '6. Contact shadow', body: 'Same silhouette, warm tray-tinted (never pure black), offset down ~24% of a cell, heavily blurred, ~45% opacity. Grounds the piece without a hard drop-shadow edge.' },
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
const TITLE_H = 40, BODY_LINE_H = 24, CHARS_PER_LINE = Math.floor(LABEL_WIDTH / 9.2);
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
  .section-title{font-family:'Fredoka',sans-serif;font-size:23px;font-weight:600;color:#5B3A73;position:absolute;left:90px;}
  .palette{position:absolute;left:0;top:1950px;width:100%;display:flex;flex-wrap:wrap;gap:26px;padding-left:90px;max-width:1900px;}
  .neutrals{position:absolute;left:0;top:2190px;width:100%;display:flex;flex-wrap:wrap;gap:26px;padding-left:90px;max-width:1900px;}
</style></head><body>
  <h1>Candy Material — Recipe &amp; Palette</h1>
  <div style="position:absolute;left:${pageX}px;top:${pageY}px;">${svg}</div>
  <svg style="position:absolute;left:0;top:0;pointer-events:none;" width="${W}" height="${H}">${calloutSvg}</svg>
  ${calloutLabels}
  <div class="section-title" style="top:1900px;">Piece hues (candy-saturated, genre-conventional mapping)</div>
  <div class="palette">${paletteRow}</div>
  <div class="section-title" style="top:2140px;">Neutrals</div>
  <div class="neutrals">${neutralRow}</div>
</body></html>`;

const __dirname = dirname(fileURLToPath(import.meta.url));
const outHtml = join(__dirname, 'styleboard.html');
const outPng = join(__dirname, '..', '03-style-board.png');
writeFileSync(outHtml, html);
shoot(outHtml, outPng, W, H);
console.log('wrote', outPng);
