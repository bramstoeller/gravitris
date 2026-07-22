import { writeFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { TETROMINOES } from './shape.mjs';
import { HUES } from './color.mjs';
import { rotate90, makeWell } from './stack.mjs';
import { placePieceDiv } from './layout.mjs';
import { shoot } from './shoot.mjs';

const W = 1080, H = 2340;
const cols = 7, rows = 15;
const cellSize = 122;
const innerPad = 24;
const wellW = cols * cellSize + innerPad * 2;
const wellH = rows * cellSize + innerPad * 2;
const wellX = Math.round((W - wellW) / 2);
const wellY = 300;
const gridPxX0 = wellX + innerPad;
const gridPxY0 = wellY + innerPad;

const well = makeWell(cols, rows);
const r90 = rotate90;

// A hand-picked drop sequence: real hard-drop landings (via well.drop), so
// the stack is guaranteed non-overlapping and physically plausible, with
// enough shape/colour variety to double as a piece-identity check. Column
// choices deliberately leave an uneven skyline (real boards aren't flat)
// and drive one row to near-complete for the coverage-band glow.
// Nine pieces only: enough for a believable, varied, interlocking settle
// with an uneven skyline, while leaving the top ~40% of the well clear for
// the falling pieces below (a topped-out board is a bad screenshot).
well.drop(TETROMINOES.O.cells, 0, 'O');
well.drop(TETROMINOES.L.cells, 2, 'L');
well.drop(r90(TETROMINOES.I.cells), 5, 'I'); // vertical I (1 wide)
well.drop(TETROMINOES.J.cells, 4, 'J');
well.drop(TETROMINOES.T.cells, 0, 'T');
well.drop(TETROMINOES.S.cells, 2, 'S');
well.drop(TETROMINOES.Z.cells, 0, 'Z');
well.drop(r90(TETROMINOES.L.cells), 5, 'L'); // vertical+foot (2 wide)
well.drop(TETROMINOES.O.cells, 3, 'O');

const pieceDivs = well.placements
  .map(({ cells, hue }) => placePieceDiv(cells, HUES[hue].base, cellSize, gridPxX0, gridPxY0))
  .join('\n');

// Coverage-band glow: highlight the fullest row to sell "about to clear".
const rowFillCount = new Array(rows).fill(0);
for (const { cells } of well.placements) for (const [, r] of cells) rowFillCount[r]++;
let glowRow = 0, best = -1;
for (let r = 0; r < rows; r++) if (rowFillCount[r] > best) { best = rowFillCount[r]; glowRow = r; }
const glowY = gridPxY0 + glowRow * cellSize;
const glowHTML = `
<div style="position:absolute;left:${wellX+8}px;top:${glowY - 14}px;width:${wellW-16}px;height:${cellSize+28}px;
  background:linear-gradient(90deg, rgba(255,196,60,0) 0%, rgba(255,196,60,0.85) 12%, rgba(255,236,170,0.98) 50%, rgba(255,196,60,0.85) 88%, rgba(255,196,60,0) 100%);
  filter:blur(3px); border-radius:20px;"></div>
<div style="position:absolute;left:${gridPxX0}px;top:${glowY}px;width:${cols*cellSize}px;height:${cellSize}px;
  box-shadow:0 0 40px 12px rgba(255,206,90,0.9), inset 0 0 30px rgba(255,255,255,0.5);
  border-radius:14px;"></div>`;

// Two falling pieces, higher in the well, each with a soft cast shadow
// projected further down (independent of the piece's own built-in contact
// shadow) so height reads even though nothing is touching yet.
const fall1Row = 1, fall1Col = 1;
const fall2Row = 3, fall2Col = 5;
const fallingHTML = [
  `<div style="position:absolute;left:${gridPxX0 + fall1Col*cellSize}px;top:${gridPxY0 + (fall1Row+3.4)*cellSize}px;width:${cellSize*3}px;height:${cellSize*0.5}px;background:radial-gradient(ellipse, rgba(120,80,50,0.28), rgba(120,80,50,0) 70%);filter:blur(10px);"></div>`,
  placePieceDiv(TETROMINOES.T.cells.map(([c,r])=>[c+fall1Col,r+fall1Row]), HUES.Z.base, cellSize, gridPxX0, gridPxY0),
  `<div style="position:absolute;left:${gridPxX0 + fall2Col*cellSize - cellSize*0.3}px;top:${gridPxY0 + (fall2Row+1.6)*cellSize}px;width:${cellSize*1.6}px;height:${cellSize*0.4}px;background:radial-gradient(ellipse, rgba(120,80,50,0.25), rgba(120,80,50,0) 70%);filter:blur(8px);"></div>`,
  placePieceDiv(TETROMINOES.O.cells.map(([c,r])=>[c+fall2Col,r+fall2Row]), HUES.O.base, cellSize, gridPxX0, gridPxY0),
].join('\n');

const bokeh = (x,y,size,color,op) => `<div style="position:absolute;left:${x}px;top:${y}px;width:${size}px;height:${size}px;border-radius:50%;background:${color};opacity:${op};filter:blur(${size*0.35}px);"></div>`;
const bokehHTML = [
  bokeh(-120, 120, 420, '#CFE4FF', 0.55),
  bokeh(760, -80, 380, '#FFD6E8', 0.5),
  bokeh(-160, 1700, 460, '#C9F3DE', 0.4),
  bokeh(880, 1500, 340, '#FFEFC2', 0.45),
  bokeh(700, 2150, 400, '#FFD6E8', 0.4),
  bokeh(60, 2250, 300, '#CFE4FF', 0.4),
].join('\n');
const sparkle = (x,y,s,op) => `<div style="position:absolute;left:${x}px;top:${y}px;width:${s}px;height:${s}px;opacity:${op};background:#fff;clip-path:polygon(50% 0%,61% 39%,100% 50%,61% 61%,50% 100%,39% 61%,0% 50%,39% 39%);"></div>`;
const sparklesHTML = [
  sparkle(150, 230, 22, 0.8), sparkle(860, 340, 16, 0.7),
  sparkle(920, 900, 14, 0.6), sparkle(60, 1000, 18, 0.6),
].join('\n');

const html = `<!doctype html><html><head><meta charset="utf-8">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Fredoka:wght@500;600;700&family=Baloo+2:wght@600;700;800&display=swap" rel="stylesheet">
<style>
  *{box-sizing:border-box}
  html,body{margin:0;padding:0;width:${W}px;height:${H}px;overflow:hidden;}
  body{
    background:
      radial-gradient(circle at 15% 8%, rgba(255,255,255,0.6), rgba(255,255,255,0) 40%),
      linear-gradient(165deg, #EAF1FF 0%, #F1E9FB 30%, #FDEFE6 65%, #FFE6D6 100%);
    font-family:'Fredoka','Baloo 2',sans-serif;
    position:relative;
  }
  .well{
    position:absolute;left:${wellX}px;top:${wellY}px;width:${wellW}px;height:${wellH}px;
    border-radius:52px;
    background:linear-gradient(180deg, #FFFBF3 0%, #FBEEDA 55%, #F4E1C4 100%);
    box-shadow:
      0 40px 70px rgba(120,70,30,0.18),
      inset 0 10px 24px rgba(120,70,30,0.10),
      inset 0 -6px 14px rgba(255,255,255,0.5);
  }
  .well-inner-shade{
    position:absolute;left:${wellX}px;top:${wellY}px;width:${wellW}px;height:${wellH}px;
    border-radius:52px;
    box-shadow: inset 0 0 0 2px rgba(255,255,255,0.35);
    pointer-events:none;
  }
  .pill{
    position:absolute;background:#FFFDF9;border-radius:100px;
    box-shadow:0 10px 22px rgba(120,70,30,0.16), inset 0 -2px 6px rgba(120,70,30,0.06), inset 0 2px 4px rgba(255,255,255,0.9);
    display:flex;flex-direction:column;justify-content:center;
  }
  .score-label{font-size:22px;font-weight:600;color:#C98BAE;letter-spacing:3px;padding-left:36px;}
  .score-value{font-size:56px;font-weight:700;color:#FF3D77;padding-left:36px;line-height:1.05;}
  .level-pill{
    top:64px;left:50%;transform:translateX(-50%);width:280px;height:104px;
    background:linear-gradient(160deg,#C68BFF 0%, #8A4FE0 100%);
    display:flex;align-items:center;justify-content:center;gap:14px;
    box-shadow:0 14px 26px rgba(120,50,180,0.35), inset 0 2px 5px rgba(255,255,255,0.5);
  }
  .level-pill .txt{color:#fff;font-size:34px;font-weight:800;letter-spacing:1px;text-shadow:0 2px 4px rgba(80,20,120,0.35);}
  .star{width:30px;height:30px;background:#FFE580;clip-path:polygon(50% 0%,61% 35%,98% 35%,68% 57%,79% 91%,50% 70%,21% 91%,32% 57%,2% 35%,39% 35%);
        filter:drop-shadow(0 2px 3px rgba(120,50,10,0.3));}
  .pause-btn{
    top:64px;right:70px;width:104px;height:104px;border-radius:50%;
    background:linear-gradient(160deg,#FFFDF9 0%, #FBEFDD 100%);
    display:flex;align-items:center;justify-content:center;
    box-shadow:0 14px 26px rgba(120,70,30,0.20), inset 0 2px 5px rgba(255,255,255,0.9);
  }
  .pause-btn .bars{display:flex;gap:12px;}
  .pause-btn .bar{width:14px;height:38px;border-radius:7px;background:#7A4A9A;}
</style></head><body>

  ${bokehHTML}
  ${sparklesHTML}
  <div class="well"></div>
  ${glowHTML}
  ${pieceDivs}
  ${fallingHTML}
  <div class="well-inner-shade"></div>

  <div class="pill" style="left:56px;top:64px;width:300px;height:120px;">
    <div class="score-label">SCORE</div>
    <div class="score-value">12,480</div>
  </div>

  <div class="pill level-pill">
    <div class="star"></div>
    <div class="txt">LEVEL 4</div>
  </div>

  <div class="pill pause-btn">
    <div class="bars"><div class="bar"></div><div class="bar"></div></div>
  </div>

</body></html>`;

const __dirname = dirname(fileURLToPath(import.meta.url));
const outHtml = join(__dirname, 'screen.html');
const outPng = join(__dirname, '..', '01-full-screen.png');
writeFileSync(outHtml, html);
shoot(outHtml, outPng, W, H);
console.log('wrote', outPng);
