// Turns a set of unit-grid cells into a single rounded-corner outline path,
// exactly the "whole-piece-aware" fused silhouette the material needs:
// convex corners bulge outward, concave (reflex) corners fillet inward,
// so an S/T/L/J piece reads as one plump candy, not four stitched squares.
//
// cells: array of [col, row] integers (unit squares on a grid)
// cellSize: px size of one grid cell
// radius: corner rounding radius in px (same units as cellSize)
// returns: { d, minX, minY, maxX, maxY, width, height } in local path space
//          (path is emitted with its own bounding box starting near 0,0 plus
//          a small margin so strokes/highlights never clip)

function edgesForCell(col, row) {
  const x0 = col, y0 = row, x1 = col + 1, y1 = row + 1;
  // edges as [ [x,y], [x,y] ] in consistent CW winding (for a single cell,
  // going clockwise when y grows downward, screen space)
  return [
    [[x0, y0], [x1, y0]], // top
    [[x1, y0], [x1, y1]], // right
    [[x1, y1], [x0, y1]], // bottom
    [[x0, y1], [x0, y0]], // left
  ];
}

function key(p) { return p[0] + ',' + p[1]; }
function edgeKey(a, b) { return key(a) + '|' + key(b); }

export function outlinePath(cells, cellSize, radius, marginCells = 0.6) {
  // 1. gather all edges, cancel shared (internal) edges
  const all = [];
  for (const [c, r] of cells) all.push(...edgesForCell(c, r));
  const cancelled = new Set();
  for (let i = 0; i < all.length; i++) {
    const [a, b] = all[i];
    if (cancelled.has(i)) continue;
    for (let j = i + 1; j < all.length; j++) {
      if (cancelled.has(j)) continue;
      const [c, d] = all[j];
      // an internal edge is shared by two cells in opposite winding
      if (key(c) === key(b) && key(d) === key(a)) {
        cancelled.add(i); cancelled.add(j);
        break;
      }
    }
  }
  const boundary = all.filter((_, i) => !cancelled.has(i));

  // 2. walk boundary edges into an ordered loop
  const byStart = new Map();
  for (const e of boundary) {
    const k = key(e[0]);
    if (!byStart.has(k)) byStart.set(k, []);
    byStart.get(k).push(e);
  }
  const loop = [];
  let current = boundary[0];
  const used = new Set();
  while (current) {
    loop.push(current[0]);
    used.add(edgeKey(current[0], current[1]));
    const nextKey = key(current[1]);
    const candidates = byStart.get(nextKey) || [];
    const next = candidates.find(e => !used.has(edgeKey(e[0], e[1])));
    current = next;
    if (!current) break;
    if (key(current[0]) === key(loop[0])) { /* about to close */ }
    if (loop.length > 500) break; // safety
  }

  // 3. offset + margin so nothing clips, then classify each vertex as
  //    convex or concave by cross product of incoming/outgoing direction,
  //    and emit straight segments shortened by radius + an arc at each corner
  const marginPx = marginCells * cellSize;
  const rawPts = loop.map(([x, y]) => [x * cellSize + marginPx, y * cellSize + marginPx]);
  // collapse collinear points: two unit cells sharing a straight boundary
  // run (e.g. the flat top of an O or I piece) produce a vertex at every
  // grid line even though the edge doesn't actually turn there. Left in,
  // each one reads as a zero-angle "corner" and gets fillet-rounded into a
  // spurious notch. Drop any point whose in/out direction doesn't turn.
  const pts = rawPts.filter((cur, i) => {
    const prev = rawPts[(i - 1 + rawPts.length) % rawPts.length];
    const next = rawPts[(i + 1) % rawPts.length];
    const inDir = [cur[0] - prev[0], cur[1] - prev[1]];
    const outDir = [next[0] - cur[0], next[1] - cur[1]];
    const cross = inDir[0] * outDir[1] - inDir[1] * outDir[0];
    const dot = inDir[0] * outDir[0] + inDir[1] * outDir[1];
    return !(Math.abs(cross) < 1e-6 && dot > 0); // keep unless perfectly straight
  });
  const n = pts.length;
  // Centroid of the outline, used only to nudge each concave-crease AO spot
  // a little into the piece's body so it reads as occlusion in the notch
  // rather than sitting exactly on the boundary line.
  const centroid = [
    pts.reduce((s, p) => s + p[0], 0) / n,
    pts.reduce((s, p) => s + p[1], 0) / n,
  ];
  const concave = [];
  let d = '';
  for (let i = 0; i < n; i++) {
    const prev = pts[(i - 1 + n) % n];
    const cur = pts[i];
    const next = pts[(i + 1) % n];
    const inDir = [cur[0] - prev[0], cur[1] - prev[1]];
    const outDir = [next[0] - cur[0], next[1] - cur[1]];
    const inLen = Math.hypot(...inDir);
    const outLen = Math.hypot(...outDir);
    const inUnit = [inDir[0] / inLen, inDir[1] / inLen];
    const outUnit = [outDir[0] / outLen, outDir[1] / outLen];
    const cross = inUnit[0] * outUnit[1] - inUnit[1] * outUnit[0];
    // screen space (y down): cross > 0 means turning right => convex (CW loop)
    const convex = cross > 0;
    const r = Math.min(radius, inLen / 2, outLen / 2);
    const p1 = [cur[0] - inUnit[0] * r, cur[1] - inUnit[1] * r];
    const p2 = [cur[0] + outUnit[0] * r, cur[1] + outUnit[1] * r];
    if (i === 0) d += `M ${p1[0].toFixed(2)} ${p1[1].toFixed(2)} `;
    else d += `L ${p1[0].toFixed(2)} ${p1[1].toFixed(2)} `;
    // sweep flag: convex corners sweep the "short way" outward (1 for CW),
    // concave corners fillet the reflex notch, sweeping the other way (0)
    const sweep = convex ? 1 : 0;
    d += `A ${r.toFixed(2)} ${r.toFixed(2)} 0 0 ${sweep} ${p2[0].toFixed(2)} ${p2[1].toFixed(2)} `;
    if (!convex) {
      // This is a self-occlusion crease: two lobes of the same piece meet
      // here (an S/T/L/J/Z notch), which is exactly where a real deformable
      // mesh would self-shadow a little. Nudge toward the centroid so the
      // soft AO blob sits inside the notch, not straddling the silhouette edge.
      const toCentroid = [centroid[0] - cur[0], centroid[1] - cur[1]];
      const tcLen = Math.hypot(...toCentroid) || 1;
      const nudge = Math.max(radius, cellSize * 0.18);
      concave.push([
        cur[0] + (toCentroid[0] / tcLen) * nudge,
        cur[1] + (toCentroid[1] / tcLen) * nudge,
      ]);
    }
  }
  d += 'Z';

  const xs = pts.map(p => p[0]), ys = pts.map(p => p[1]);
  const minX = Math.min(...xs) - radius, maxX = Math.max(...xs) + radius;
  const minY = Math.min(...ys) - radius, maxY = Math.max(...ys) + radius;
  return { d, minX, minY, maxX, maxY, width: maxX + marginPx, height: maxY + marginPx, concave };
}

export const TETROMINOES = {
  I: { cells: [[0,0],[1,0],[2,0],[3,0]] },
  O: { cells: [[0,0],[1,0],[0,1],[1,1]] },
  T: { cells: [[0,0],[1,0],[2,0],[1,1]] },
  S: { cells: [[1,0],[2,0],[0,1],[1,1]] },
  Z: { cells: [[0,0],[1,0],[1,1],[2,1]] },
  J: { cells: [[0,0],[0,1],[1,1],[2,1]] },
  L: { cells: [[2,0],[0,1],[1,1],[2,1]] },
};
