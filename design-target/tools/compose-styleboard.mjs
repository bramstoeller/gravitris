import { writeFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { TETROMINOES } from './shape.mjs';
import { HUES, materialStops } from './color.mjs';
import { renderPiece } from './piece.mjs';
import { shoot } from './shoot.mjs';

const W = 2000, H = 2300;
const cellSize = 260;
const pageX = 600, pageY = 280; // where the annotated piece's own (0,0) sits, comfortably clear of every label

const cells = TETROMINOES.O.cells;
const margin = 0.55;
const marginPx = margin * cellSize;
const ax = pageX + 0.5 * cellSize + marginPx;
const ay = pageY + 0.5 * cellSize + marginPx;
const shadowDy = cellSize * 0.24;

const { svg, width, height } = renderPiece(cells, HUES.O.base, { cellSize });

// Four callouts, one per corner, each pointing in from clear space so no
// label ever sits on top of the candy, the piece's own footprint, or
// another label (checked by computing the piece's actual pixel bounds,
// not eyeballed -- the first pass got this wrong twice).
const callouts = [
  { x: ax - cellSize*0.08, y: ay - cellSize*0.40, lx: 90, ly: 300, align: 'left',
    title: '1. Specular gleam + sparkle', body: 'Hard white ellipse (~58%×24% of a cell), rotated -24°, screen-blended, centred on the topmost-left filled CELL — not the bounding-box corner, or an L/S/J piece\'s highlight lands in clipped-away empty space. A tiny bright dot at its tip sells "wet" at a glance.' },
  { x: ax + cellSize*0.30, y: ay - cellSize*0.05, lx: W - 90, ly: 300, align: 'right',
    title: '2. Subsurface glow', body: 'Large soft radial (white, 0.65→0 opacity), heavily blurred, soft-light blend, ~2 cells across. The translucency proxy: reads as "light passing through" without real alpha blending or depth-sorted transparency.' },
  { x: ax - cellSize*0.30, y: ay + cellSize*0.75, lx: 90, ly: 1550, align: 'left',
    title: '3. Base gradient + rim', body: 'Body: linear, top-light → bottom-deep, ~20° off vertical, doing almost no shading of its own (matches the genre). Rim: a thin stroke on the silhouette itself, light at the top and deep at the bottom, for the glassy edge line.' },
  { x: ax + cellSize*0.35, y: ay + cellSize*1.05 + shadowDy, lx: W - 90, ly: 1550, align: 'right',
    title: '4. Contact shadow', body: 'Same silhouette, warm tray-tinted (never pure black), offset down ~24% of a cell, heavily blurred, ~45% opacity. Grounds the piece without a hard drop-shadow edge.' },
];

const calloutSvg = callouts.map(c => `
  <line x1="${c.x.toFixed(1)}" y1="${c.y.toFixed(1)}" x2="${c.lx.toFixed(1)}" y2="${c.ly.toFixed(1)}" stroke="#8A5A9A" stroke-width="2.5" stroke-dasharray="1 7" stroke-linecap="round"/>
  <circle cx="${c.x.toFixed(1)}" cy="${c.y.toFixed(1)}" r="8" fill="#8A5A9A"/>
  <circle cx="${c.lx.toFixed(1)}" cy="${c.ly.toFixed(1)}" r="4" fill="#8A5A9A"/>
`).join('\n');

const calloutLabels = callouts.map(c => {
  const left = c.align === 'left' ? c.lx : c.lx - 480;
  return `
  <div style="position:absolute;left:${left}px;top:${c.ly - 14}px;width:480px;${c.align === 'right' ? 'text-align:right;' : ''}">
    <div style="font-family:'Fredoka',sans-serif;font-weight:600;font-size:25px;color:#5B3A73;">${c.title}</div>
    <div style="font-family:'Inter',sans-serif;font-size:17px;line-height:1.5;color:#7A6B85;margin-top:6px;">${c.body}</div>
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
  .palette{position:absolute;left:0;top:1750px;width:100%;display:flex;gap:26px;padding-left:90px;}
  .neutrals{position:absolute;left:0;top:1990px;width:100%;display:flex;gap:26px;padding-left:90px;}
</style></head><body>
  <h1>Candy Material — Recipe &amp; Palette</h1>
  <div style="position:absolute;left:${pageX}px;top:${pageY}px;">${svg}</div>
  <svg style="position:absolute;left:0;top:0;pointer-events:none;" width="${W}" height="${H}">${calloutSvg}</svg>
  ${calloutLabels}
  <div class="section-title" style="top:1700px;">Piece hues (candy-saturated, genre-conventional mapping)</div>
  <div class="palette">${paletteRow}</div>
  <div class="section-title" style="top:1940px;">Neutrals</div>
  <div class="neutrals">${neutralRow}</div>
</body></html>`;

const __dirname = dirname(fileURLToPath(import.meta.url));
const outHtml = join(__dirname, 'styleboard.html');
const outPng = join(__dirname, '..', '03-style-board.png');
writeFileSync(outHtml, html);
shoot(outHtml, outPng, W, H);
console.log('wrote', outPng);
