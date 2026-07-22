import { writeFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { TETROMINOES } from './shape.mjs';
import { HUES, materialStops } from './color.mjs';
import { rotate90, makeWell, crossPieceSeams } from './stack.mjs';
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

// The most-recently-landed piece gets a squash: a transform on the
// wrapping div only (page composition), never a material change -- exactly
// how a real deformable mesh would present "just impacted," a transform
// applied for a couple of frames after contact, not a different shader.
const landedIdx = well.placements.length - 1;
const pieceDivs = well.placements
  .map(({ cells, hue }, i) => placePieceDiv(cells, HUES[hue].base, cellSize, gridPxX0, gridPxY0,
    i === landedIdx ? { extraStyle: 'transform:scale(1.06,0.90);transform-origin:50% 80%;' } : {}))
  .join('\n');

// Contact-AO seams: every grid edge shared by two *different* settled
// pieces gets a soft dark seam, the same adjacency a real soft-body
// renderer already has (two separate deformable meshes touching) -- not a
// per-piece material effect, a page-composition one, like the falling-piece
// cast shadow below.
const seamHTML = crossPieceSeams(well.placements).map(({ col, row, side }) => {
  const cx = gridPxX0 + col * cellSize, cy = gridPxY0 + row * cellSize;
  if (side === 'right') {
    const x = cx + cellSize - cellSize * 0.06;
    return `<div style="position:absolute;left:${x}px;top:${cy + cellSize*0.08}px;width:${cellSize*0.12}px;height:${cellSize*0.84}px;
      background:linear-gradient(90deg, rgba(60,30,20,0) 0%, rgba(60,30,20,0.22) 50%, rgba(60,30,20,0) 100%);
      filter:blur(${cellSize*0.045}px);"></div>`;
  }
  const y = cy + cellSize - cellSize * 0.06;
  return `<div style="position:absolute;left:${cx + cellSize*0.08}px;top:${y}px;width:${cellSize*0.84}px;height:${cellSize*0.12}px;
    background:linear-gradient(180deg, rgba(60,30,20,0) 0%, rgba(60,30,20,0.22) 50%, rgba(60,30,20,0) 100%);
    filter:blur(${cellSize*0.045}px);"></div>`;
}).join('\n');

// Impact burst under the landed piece: a soft ground-hugging flash plus a
// couple of tiny bright shards, at the piece's own footprint centre-bottom.
const landedCells = well.placements[landedIdx].cells;
const landedMinC = Math.min(...landedCells.map(c => c[0]));
const landedMaxC = Math.max(...landedCells.map(c => c[0]));
const landedMaxR = Math.max(...landedCells.map(c => c[1]));
const landedCx = gridPxX0 + (landedMinC + landedMaxC + 1) / 2 * cellSize;
const landedBy = gridPxY0 + (landedMaxR + 1) * cellSize;
const impactHTML = `
<div style="position:absolute;left:${landedCx - cellSize*0.9}px;top:${landedBy - cellSize*0.22}px;width:${cellSize*1.8}px;height:${cellSize*0.5}px;
  background:radial-gradient(ellipse, rgba(255,255,255,0.55), rgba(255,255,255,0) 72%);filter:blur(${cellSize*0.08}px);"></div>`;

// Coverage-band clear: the fullest row, rendered as an actual "juice"
// moment -- bright bloom core, soft radiating light rays, and a scatter of
// tiny candy-hued shard particles -- not just a static gold bar.
const rowFillCount = new Array(rows).fill(0);
for (const { cells } of well.placements) for (const [, r] of cells) rowFillCount[r]++;
let glowRow = 0, best = -1;
for (let r = 0; r < rows; r++) if (rowFillCount[r] > best) { best = rowFillCount[r]; glowRow = r; }
const glowY = gridPxY0 + glowRow * cellSize;
const glowCx = gridPxX0 + cols * cellSize / 2;
const glowCy = glowY + cellSize / 2;
const rayColors = ['#FFF6D8', '#FFE9B0'];
const rayStops = Array.from({ length: 16 }, (_, i) => {
  const a0 = (i / 16) * 360, a1 = a0 + 360 / 32;
  const c = rayColors[i % 2];
  return `transparent ${a0}deg, ${c} ${(a0+a1)/2}deg, transparent ${a1}deg`;
}).join(', ');
const shardColors = Object.values(HUES).map(h => h.base);
const shard = (x, y, s, rot, op, hex) => `<div style="position:absolute;left:${x}px;top:${y}px;width:${s}px;height:${s}px;opacity:${op};
  background:${hex};transform:rotate(${rot}deg);clip-path:polygon(50% 0%,61% 39%,100% 50%,61% 61%,50% 100%,39% 61%,0% 50%,39% 39%);
  box-shadow:0 0 ${s*0.6}px ${hex};"></div>`;
const clearShards = Array.from({ length: 12 }, (_, i) => {
  const t = i / 12;
  const x = gridPxX0 + t * cols * cellSize + (Math.sin(i*2.1)*0.5+0.5) * cellSize*0.4;
  const y = glowCy + Math.sin(i * 1.7) * cellSize * 0.7;
  const s = cellSize * (0.12 + (i % 3) * 0.05);
  return shard(x, y - s/2, s, (i * 47) % 360, 0.55 + (i % 3) * 0.12, shardColors[i % shardColors.length]);
}).join('\n');
// Drawn *behind* the settled pieces: the ambient bleed that lights the
// well itself and shows through the row's one remaining gap.
const glowUnderHTML = `
<div style="position:absolute;left:${wellX+8}px;top:${glowY - 14}px;width:${wellW-16}px;height:${cellSize+28}px;
  background:linear-gradient(90deg, rgba(255,196,60,0) 0%, rgba(255,196,60,0.85) 12%, rgba(255,236,170,0.98) 50%, rgba(255,196,60,0.85) 88%, rgba(255,196,60,0) 100%);
  filter:blur(3px); border-radius:20px;"></div>
<div style="position:absolute;left:${gridPxX0}px;top:${glowY}px;width:${cols*cellSize}px;height:${cellSize}px;
  box-shadow:0 0 56px 16px rgba(255,206,90,0.95), inset 0 0 34px rgba(255,255,255,0.55);
  border-radius:14px;"></div>`;
// Drawn *in front of* the settled pieces: the actual clear "juice" -- a
// bright screen-blended sweep across the row, radiating rays and a scatter
// of hued shard particles bursting past the pieces' own edges, the same way
// a real clear FX overlays the tiles rather than hiding behind them.
const glowOverHTML = `
<div style="position:absolute;left:${glowCx - cellSize*3.2}px;top:${glowCy - cellSize*3.2}px;width:${cellSize*6.4}px;height:${cellSize*6.4}px;
  background:conic-gradient(from 0deg at 50% 50%, ${rayStops});
  filter:blur(${cellSize*0.14}px);opacity:0.55;mix-blend-mode:screen;border-radius:50%;"></div>
<div style="position:absolute;left:${gridPxX0}px;top:${glowY + cellSize*0.16}px;width:${cols*cellSize}px;height:${cellSize*0.68}px;
  background:linear-gradient(90deg, rgba(255,222,140,0) 0%, rgba(255,236,180,0.55) 16%, rgba(255,250,220,0.8) 50%, rgba(255,236,180,0.55) 84%, rgba(255,222,140,0) 100%);
  filter:blur(${cellSize*0.05}px);mix-blend-mode:screen;border-radius:${cellSize*0.3}px;"></div>
${clearShards}`;

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

// Atmosphere: a layered mesh of soft colour pools (not one flat diagonal
// gradient) plus bigger, richer bokeh, a whisper of a vignette to focus the
// eye on the well, and a couple of very low-opacity light rays for a sense
// of one dominant light source -- still light and candy, just with a place
// behind it instead of a blank pastel backdrop.
const bokeh = (x,y,size,color,op) => `<div style="position:absolute;left:${x}px;top:${y}px;width:${size}px;height:${size}px;border-radius:50%;background:${color};opacity:${op};filter:blur(${size*0.35}px);"></div>`;
const bokehHTML = [
  bokeh(-140, 100, 460, '#CFE4FF', 0.6),
  bokeh(740, -100, 420, '#FFD6E8', 0.55),
  bokeh(-180, 1680, 500, '#C9F3DE', 0.48),
  bokeh(860, 1480, 380, '#FFEFC2', 0.5),
  bokeh(680, 2140, 440, '#FFD6E8', 0.45),
  bokeh(40, 2260, 340, '#CFE4FF', 0.45),
  bokeh(920, 760, 220, '#E7D3FF', 0.32),
  bokeh(-60, 900, 180, '#FFE3C2', 0.3),
].join('\n');
const dust = (x,y,s,op) => `<div style="position:absolute;left:${x}px;top:${y}px;width:${s}px;height:${s}px;border-radius:50%;background:#fff;opacity:${op};filter:blur(${s*0.5}px);"></div>`;
const dustHTML = Array.from({ length: 22 }, (_, i) => {
  const x = (i * 137) % W, y = (i * 271 + 40) % H;
  const s = 5 + (i % 4) * 3;
  return dust(x, y, s, 0.15 + (i % 3) * 0.08);
}).join('\n');
const sparkle = (x,y,s,op) => `<div style="position:absolute;left:${x}px;top:${y}px;width:${s}px;height:${s}px;opacity:${op};background:#fff;clip-path:polygon(50% 0%,61% 39%,100% 50%,61% 61%,50% 100%,39% 61%,0% 50%,39% 39%);"></div>`;
const sparklesHTML = [
  sparkle(150, 230, 22, 0.85), sparkle(860, 340, 16, 0.7),
  sparkle(920, 900, 14, 0.6), sparkle(60, 1000, 18, 0.6),
  sparkle(980, 1980, 15, 0.55), sparkle(120, 2080, 12, 0.5),
].join('\n');
const vignetteHTML = `
<div style="position:absolute;left:0;top:0;width:${W}px;height:${H}px;pointer-events:none;
  background:radial-gradient(ellipse at 50% 38%, rgba(120,70,110,0) 55%, rgba(70,40,80,0.14) 100%);"></div>`;
const rayHTML = `
<div style="position:absolute;left:${W*0.5 - 700}px;top:-380px;width:1400px;height:1400px;pointer-events:none;
  background:conic-gradient(from 210deg at 50% 50%, transparent 0deg, rgba(255,255,255,0.5) 6deg, transparent 14deg, transparent 40deg, rgba(255,255,255,0.32) 46deg, transparent 54deg);
  filter:blur(36px);opacity:0.5;mix-blend-mode:screen;"></div>`;

const html = `<!doctype html><html><head><meta charset="utf-8">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Fredoka:wght@500;600;700&family=Baloo+2:wght@600;700;800&display=swap" rel="stylesheet">
<style>
  *{box-sizing:border-box}
  html,body{margin:0;padding:0;width:${W}px;height:${H}px;overflow:hidden;}
  body{
    background:
      radial-gradient(circle at 15% 8%, rgba(255,255,255,0.6), rgba(255,255,255,0) 40%),
      radial-gradient(ellipse 900px 700px at 12% 6%, rgba(207,228,255,0.65), rgba(207,228,255,0) 70%),
      radial-gradient(ellipse 800px 640px at 92% 4%, rgba(243,217,255,0.55), rgba(243,217,255,0) 70%),
      radial-gradient(ellipse 820px 680px at 6% 96%, rgba(201,243,222,0.5), rgba(201,243,222,0) 70%),
      radial-gradient(ellipse 760px 620px at 96% 92%, rgba(255,225,194,0.55), rgba(255,225,194,0) 70%),
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
    box-shadow:0 8px 18px rgba(120,60,90,0.14), 0 2px 5px rgba(120,60,90,0.10),
      inset 0 -2px 5px rgba(120,70,30,0.05), inset 0 2px 4px rgba(255,255,255,0.9);
    display:flex;flex-direction:column;justify-content:center;
  }
  .score-label{font-size:19px;font-weight:600;color:#C98BAE;letter-spacing:2.5px;padding-left:34px;}
  .score-value{font-size:50px;font-weight:700;color:#FF3D77;padding-left:34px;line-height:1.08;letter-spacing:-0.5px;}
  .level-pill{
    top:60px;left:50%;transform:translateX(-50%);width:272px;height:100px;
    background:linear-gradient(160deg,#C68BFF 0%, #8A4FE0 100%);
    display:flex;align-items:center;justify-content:center;gap:13px;
    box-shadow:0 10px 22px rgba(120,50,180,0.32), 0 0 0 5px rgba(180,120,255,0.16),
      inset 0 2px 5px rgba(255,255,255,0.5);
  }
  .level-pill .txt{color:#fff;font-size:29px;font-weight:800;letter-spacing:0.5px;text-shadow:0 2px 4px rgba(80,20,120,0.35);}
  .star{width:28px;height:28px;background:#FFE580;clip-path:polygon(50% 0%,61% 35%,98% 35%,68% 57%,79% 91%,50% 70%,21% 91%,32% 57%,2% 35%,39% 35%);
        filter:drop-shadow(0 2px 3px rgba(120,50,10,0.3));}
  .pause-btn{
    top:60px;right:68px;width:100px;height:100px;border-radius:50%;
    background:linear-gradient(160deg,#FFFDF9 0%, #FBEFDD 100%);
    display:flex;align-items:center;justify-content:center;
    box-shadow:0 8px 18px rgba(120,70,30,0.16), inset 0 2px 5px rgba(255,255,255,0.9);
  }
  .pause-btn .bars{display:flex;gap:11px;}
  .pause-btn .bar{width:12px;height:34px;border-radius:6px;background:#7A4A9A;}
</style></head><body>

  ${rayHTML}
  ${bokehHTML}
  ${dustHTML}
  ${sparklesHTML}
  <div class="well"></div>
  ${glowUnderHTML}
  ${seamHTML}
  ${pieceDivs}
  ${impactHTML}
  ${glowOverHTML}
  ${fallingHTML}
  <div class="well-inner-shade"></div>
  ${vignetteHTML}

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
