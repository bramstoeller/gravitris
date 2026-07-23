import { writeFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { TETROMINOES } from './shape.mjs';
import { rotate90 } from './stack.mjs';
import { HUES } from './color.mjs';
import { mostLitCell, LIGHT_DIR } from './light.mjs';
import { renderPiece } from './piece.mjs';
import { shoot } from './shoot.mjs';

// Client note 2: "Is er nagedacht over rotatie?" -- this is the answer,
// rendered rather than asserted. The pieces quarter-turn during play (and
// deform, soft-body); the material has to keep reading correctly in every
// orientation, and specifically the highlight/sheen must NOT spin with the
// piece -- it has to stay anchored to the one fixed light direction
// (tools/light.mjs), the same rule "World-anchored lighting" on the style
// board names. This page shows the same T piece in all four quarter-turns
// plus one squashed (mid-deformation) variant, with the actual computed
// highlight anchor marked on each -- not just claimed, measured off the
// same mostLitCell() call piece.mjs itself uses, so this page can't drift
// out of sync with what actually renders.

const W = 2600, H = 1200;
const cellSize = 132;
const cardW = 480, cardH = 760;
const cardY = 300;
const cardXs = [80, 80 + cardW, 80 + cardW * 2, 80 + cardW * 3, 80 + cardW * 4];
const margin = 0.55;

let cells = TETROMINOES.T.cells;
const orientations = [0, 90, 180, 270].map(deg => {
  const c = cells;
  cells = rotate90(cells);
  return { deg, cells: c };
});

function cardHTML(cells, hueBase, x, extraStyle = '', squashLabel = false) {
  const { svg, width, height } = renderPiece(cells, hueBase, { cellSize });
  const pieceX = x + (cardW - width) / 2;
  const pieceY = cardY + (cardH - 170 - height) / 2;

  // The exact anchor cell renderPiece() itself will pick, so the marker
  // dot below is a measurement, not an illustration -- it reads off the
  // same mostLitCell() call and the same margin math as piece.mjs.
  const [ac, ar] = mostLitCell(cells);
  const marginPx = margin * cellSize;
  const ax = pieceX + (ac + 0.5) * cellSize + marginPx;
  const ay = pieceY + (ar + 0.5) * cellSize + marginPx;

  return `
  <div style="position:absolute;left:${pieceX}px;top:${pieceY}px;width:${width}px;height:${height}px;${extraStyle}">${svg}</div>
  <div style="position:absolute;left:${(ax-11).toFixed(1)}px;top:${(ay-11).toFixed(1)}px;width:22px;height:22px;border-radius:50%;
       border:3px solid #8A5A9A;box-shadow:0 0 0 3px rgba(255,255,255,0.85);pointer-events:none;"></div>
  ${squashLabel ? `<div style="position:absolute;left:${x}px;top:${cardY + cardH - 150}px;width:${cardW}px;text-align:center;
       font-family:'Inter',sans-serif;font-size:15px;color:#B98CC4;">deformed (mid-landing squash)</div>` : ''}
  `;
}

const cards = orientations.map(({ deg, cells }, i) => `
  <div style="position:absolute;left:${cardXs[i]}px;top:${cardY}px;width:${cardW}px;height:${cardH}px;border-radius:36px;
       background:linear-gradient(180deg,#FFFCF6 0%, #F7EAD6 100%);
       box-shadow:0 24px 44px rgba(120,70,30,0.14), inset 0 2px 5px rgba(255,255,255,0.8);"></div>
  ${cardHTML(cells, HUES.T.base, cardXs[i])}
  <div style="position:absolute;left:${cardXs[i]}px;top:${cardY + cardH - 96}px;width:${cardW}px;text-align:center;
       font-family:'Fredoka',sans-serif;font-weight:600;font-size:24px;color:#5B3A73;">${deg}°</div>
  <div style="position:absolute;left:${cardXs[i]}px;top:${cardY + cardH - 58}px;width:${cardW}px;text-align:center;
       font-family:'Inter',sans-serif;font-size:15px;color:#B98CC4;">quarter-turn ${i}</div>
`).join('\n');

// Fifth card: the spawn orientation again, but visibly squashed -- the same
// wrapper-only transform compose-screen.mjs already uses for a just-landed
// piece, applied here to prove the material (and the anchor rule) holds
// under deformation too, not only under rotation.
const squashCard = `
  <div style="position:absolute;left:${cardXs[4]}px;top:${cardY}px;width:${cardW}px;height:${cardH}px;border-radius:36px;
       background:linear-gradient(180deg,#FFFCF6 0%, #F7EAD6 100%);
       box-shadow:0 24px 44px rgba(120,70,30,0.14), inset 0 2px 5px rgba(255,255,255,0.8);"></div>
  ${cardHTML(orientations[0].cells, HUES.T.base, cardXs[4], 'transform:scale(1.1,0.86);transform-origin:50% 62%;', true)}
  <div style="position:absolute;left:${cardXs[4]}px;top:${cardY + cardH - 96}px;width:${cardW}px;text-align:center;
       font-family:'Fredoka',sans-serif;font-weight:600;font-size:24px;color:#5B3A73;">0° squashed</div>
`;

// One fixed light-source glyph for the whole page, not per-card -- the
// point being proven is that there is exactly ONE light, shared by every
// orientation below, not five different ones that happen to agree.
const lightAngleDeg = Math.atan2(LIGHT_DIR.y, LIGHT_DIR.x) * 180 / Math.PI;
const lightGlyph = `
  <div style="position:absolute;left:90px;top:90px;width:280px;">
    <svg width="110" height="110" viewBox="0 0 120 120">
      <circle cx="60" cy="60" r="22" fill="#FFE580" stroke="#E0A83D" stroke-width="3"/>
      ${Array.from({ length: 8 }, (_, i) => {
        const a = (i / 8) * Math.PI * 2;
        const x1 = 60 + Math.cos(a) * 32, y1 = 60 + Math.sin(a) * 32;
        const x2 = 60 + Math.cos(a) * 48, y2 = 60 + Math.sin(a) * 48;
        return `<line x1="${x1.toFixed(1)}" y1="${y1.toFixed(1)}" x2="${x2.toFixed(1)}" y2="${y2.toFixed(1)}" stroke="#E0A83D" stroke-width="4" stroke-linecap="round"/>`;
      }).join('')}
    </svg>
    <div style="font-family:'Fredoka',sans-serif;font-weight:600;font-size:19px;color:#5B3A73;margin-top:-4px;">One fixed light, upper-left</div>
    <div style="font-family:'Inter',sans-serif;font-size:14.5px;line-height:1.5;color:#7A6B85;margin-top:4px;">Shared by all five cards -- a page constant (tools/light.mjs, ${Math.round(lightAngleDeg)}°), never per-piece.</div>
  </div>`;

const html = `<!doctype html><html><head><meta charset="utf-8">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Fredoka:wght@500;600;700&family=Inter:wght@400;500&display=swap" rel="stylesheet">
<style>
  *{box-sizing:border-box}
  html,body{margin:0;padding:0;width:${W}px;height:${H}px;overflow:hidden;font-family:'Fredoka',sans-serif;position:relative;}
  body{
    background:
      radial-gradient(circle at 12% 10%, rgba(255,255,255,0.7), rgba(255,255,255,0) 45%),
      linear-gradient(160deg, #EAF1FF 0%, #F3EAFB 30%, #FDEFE6 70%, #FFE6D6 100%);
  }
  h1{position:absolute;left:90px;top:230px;font-size:36px;color:#5B3A73;margin:0;}
  .subtitle{position:absolute;left:90px;top:${230+52}px;width:1700px;font-family:'Inter',sans-serif;font-size:17px;line-height:1.5;color:#8A7A95;}
</style></head><body>
  ${lightGlyph}
  <h1 style="top:90px;left:360px;">Rotation &amp; deformation — the light stays put</h1>
  <div class="subtitle" style="top:150px;left:360px;">Same T piece, four quarter-turns plus one squashed. The marked dot on each is the measured
    highlight anchor (mostLitCell(), the same call piece.mjs makes) — it moves to a different CELL each turn because the piece's own
    shape changes, but it never leaves the side of the piece that faces the fixed light above. Compare the dot's position across all
    five cards: always upper-left-of-mass, never rotating to trail the piece's own shape.</div>
  ${cards}
  ${squashCard}
</body></html>`;

const __dirname = dirname(fileURLToPath(import.meta.url));
const outHtml = join(__dirname, 'rotation.html');
const outPng = join(__dirname, '..', '04-rotation-strip.png');
writeFileSync(outHtml, html);
shoot(outHtml, outPng, W, H);
console.log('wrote', outPng);
