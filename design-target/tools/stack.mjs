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

// Finds every grid edge shared by two cells that belong to *different*
// pieces in a settled stack (an edge shared by two cells of the *same*
// piece is an internal seam the material already fuses away -- not this).
// This is exactly the adjacency a real soft-body renderer already has to
// know (two separate deformable pieces touching), so a soft contact-AO
// seam drawn along it is honest, not a mockup-only trick.
// Returns [{ col, row, side }] where side is 'right' (edge between (col,row)
// and (col+1,row)) or 'bottom' (edge between (col,row) and (col,row+1)).
export function crossPieceSeams(placements) {
  const ownerOf = new Map(); // "c,r" -> piece index
  placements.forEach(({ cells }, i) => {
    for (const [c, r] of cells) ownerOf.set(`${c},${r}`, i);
  });
  const seams = [];
  for (const [key, owner] of ownerOf) {
    const [c, r] = key.split(',').map(Number);
    const rightOwner = ownerOf.get(`${c + 1},${r}`);
    if (rightOwner !== undefined && rightOwner !== owner) seams.push({ col: c, row: r, side: 'right' });
    const downOwner = ownerOf.get(`${c},${r + 1}`);
    if (downOwner !== undefined && downOwner !== owner) seams.push({ col: c, row: r, side: 'bottom' });
  }
  return seams;
}
