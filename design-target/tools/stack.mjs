// A tiny hard-drop simulator so the settled stack in the mockup is a
// physically plausible tetromino tiling (no overlaps, real landing
// heights) instead of hand-plotted cells that are easy to get wrong.

export function rotate90(cells) {
  const maxR = Math.max(...cells.map(c => c[1]));
  const rotated = cells.map(([c, r]) => [maxR - r, c]);
  const minC = Math.min(...rotated.map(c => c[0]));
  const minR = Math.min(...rotated.map(c => c[1]));
  return rotated.map(([c, r]) => [c - minC, r - minR]);
}

export function makeWell(cols, rows) {
  const heightTop = new Array(cols).fill(rows); // "floor" below row (rows-1)
  const placements = [];
  function drop(cells, leftCol, hue) {
    const byCol = new Map();
    for (const [c, r] of cells) {
      const col = leftCol + c;
      if (col < 0 || col >= cols) {
        throw new Error(`drop(${hue}) at leftCol=${leftCol} places a cell at column ${col}, outside 0..${cols - 1}`);
      }
      if (!byCol.has(col) || r > byCol.get(col)) byCol.set(col, r);
    }
    let shift = Infinity;
    for (const [col, bottomOffset] of byCol) {
      shift = Math.min(shift, heightTop[col] - 1 - bottomOffset);
    }
    const placedCells = cells.map(([c, r]) => [leftCol + c, shift + r]);
    for (const [col, row] of placedCells) {
      heightTop[col] = Math.min(heightTop[col], row);
    }
    placements.push({ cells: placedCells, hue });
    return placedCells;
  }
  return { heightTop, placements, drop };
}
