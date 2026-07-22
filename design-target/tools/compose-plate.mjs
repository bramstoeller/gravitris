import { writeFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { TETROMINOES } from './shape.mjs';
import { HUES } from './color.mjs';
import { renderPiece } from './piece.mjs';
import { shoot } from './shoot.mjs';

const W = 1760, H = 2420;
const cellSize = 108;

const order = ['I', 'O', 'T', 'S', 'Z', 'J', 'L'];
const cards = order.map(key => {
  const { svg, width, height } = renderPiece(TETROMINOES[key].cells, HUES[key].base, { cellSize });
  return `
  <div class="card">
    <div class="piece-wrap" style="width:${width}px;height:${height}px;">${svg}</div>
    <div class="label">
      <div class="name">${key} &middot; ${HUES[key].name}</div>
      <div class="hex">${HUES[key].base}</div>
    </div>
  </div>`;
}).join('\n');

const html = `<!doctype html><html><head><meta charset="utf-8">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Fredoka:wght@500;600;700&family=JetBrains+Mono:wght@500&display=swap" rel="stylesheet">
<style>
  *{box-sizing:border-box}
  html,body{margin:0;padding:0;width:${W}px;height:${H}px;overflow:hidden;font-family:'Fredoka',sans-serif;}
  body{
    background:
      radial-gradient(circle at 12% 10%, rgba(255,255,255,0.7), rgba(255,255,255,0) 45%),
      linear-gradient(160deg, #EAF1FF 0%, #F3EAFB 30%, #FDEFE6 70%, #FFE6D6 100%);
    display:flex;flex-wrap:wrap;align-content:flex-start;justify-content:center;
    gap:26px;padding:50px;
  }
  .card{
    border-radius:32px;
    background:linear-gradient(180deg,#FFFCF6 0%, #F7EAD6 100%);
    box-shadow:0 24px 44px rgba(120,70,30,0.14), inset 0 2px 5px rgba(255,255,255,0.8);
    display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;
    padding:30px 34px 26px;
  }
  .piece-wrap{display:flex;align-items:center;justify-content:center;}
  .label{text-align:center;}
  .name{font-size:26px;font-weight:600;color:#7A4A9A;}
  .hex{font-family:'JetBrains Mono',monospace;font-size:19px;color:#B98CC4;margin-top:4px;letter-spacing:1px;}
</style></head><body>
${cards}
</body></html>`;

const __dirname = dirname(fileURLToPath(import.meta.url));
const outHtml = join(__dirname, 'plate.html');
const outPng = join(__dirname, '..', '02-candy-plate.png');
writeFileSync(outHtml, html);
shoot(outHtml, outPng, W, H);
console.log('wrote', outPng);
