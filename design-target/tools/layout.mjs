import { renderPiece } from './piece.mjs';

// Places a piece (given in absolute well-grid cell coordinates) onto the
// page at pixel position (gridPxX0, gridPxY0) = the pixel location of grid
// cell (0,0). Normalizes the cells to their own local origin first, since
// outlinePath()/renderPiece() expects a piece's own (0,0)-based cell set,
// then converts the dropped offset back into a page-pixel position.
export function placePieceDiv(cells, hueBase, cellSize, gridPxX0, gridPxY0, opts = {}) {
  const minC = Math.min(...cells.map(c => c[0]));
  const minR = Math.min(...cells.map(c => c[1]));
  const local = cells.map(([c, r]) => [c - minC, r - minR]);
  const { svg, width, height } = renderPiece(local, hueBase, { cellSize, ...opts });
  // Must match outlinePath's own `margin` (piece.mjs passes 0.55 to
  // outlinePath) -- this is the single offset between "grid cell (0,0)"
  // and "pixel (0,0) of the piece's own svg", not a second independent pad.
  const margin = cellSize * 0.55;
  const pxX = gridPxX0 + minC * cellSize - margin;
  const pxY = gridPxY0 + minR * cellSize - margin;
  return `<div style="position:absolute;left:${pxX.toFixed(1)}px;top:${pxY.toFixed(1)}px;width:${width.toFixed(1)}px;height:${height.toFixed(1)}px;">${svg}</div>`;
}
