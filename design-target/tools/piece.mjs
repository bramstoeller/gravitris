import { outlinePath } from './shape.mjs';
import { materialStops, COATING_SHIMMER } from './color.mjs';

let uid = 0;

// Renders one candy-material tetromino piece as an SVG fragment.
// Returns { svg, width, height } where svg is a self-contained <svg> element
// (own defs, viewBox) that can be dropped anywhere and scaled by width/height.
export function renderPiece(cells, hueBase, { cellSize = 120, cornerRatio = 0.30 } = {}) {
  const id = 'p' + (uid++);
  const radius = cellSize * cornerRatio;
  const margin = 0.55;
  const { d, width, height, concave } = outlinePath(cells, cellSize, radius, margin);
  const m = materialStops(hueBase);
  const w = width, h = height;
  const shadowDy = cellSize * 0.24;
  const rimWidth = Math.max(2, cellSize * 0.05);

  // Anchor the highlight/glow to an actual filled cell (topmost, then
  // leftmost) instead of a bounding-box fraction. An L or S piece's mass
  // sits far from its bbox corner; a bbox-relative highlight lands in
  // clipped-away empty space and simply doesn't render (found by looking
  // at the first render, not assumed).
  const sorted = [...cells].sort((a, b) => (a[1] - b[1]) || (a[0] - b[0]));
  const [ac, ar] = sorted[0];
  const marginPx = margin * cellSize;
  const ax = (ac + 0.5) * cellSize + marginPx;
  const ay = (ar + 0.5) * cellSize + marginPx;

  // Warm inner-glow "pool" sits toward the piece's own centre of mass, not
  // the highlight corner -- it reads as light gathering in the thickest
  // part of the gel, the way real subsurface scattering is strongest where
  // the material is deepest, independent of where the surface gleam is.
  const centreC = cells.reduce((s, c) => s + c[0], 0) / cells.length;
  const centreR = cells.reduce((s, c) => s + c[1], 0) / cells.length;
  const cx = (centreC + 0.5) * cellSize + marginPx;
  const cy = (centreR + 0.5) * cellSize + marginPx;

  // outlinePath() already built `margin` worth of breathing room into `d`
  // and into w/h symmetrically on every side (for rim stroke + highlight
  // bleed) -- do NOT add a second pad here, or the piece's pixel position
  // silently drifts from where layout.mjs thinks grid cell (0,0) is (this
  // is exactly what happened the first time: pieces rendered ~65px below
  // their intended row, poking out past the well's floor, because two
  // independent margins were each applied but only one was compensated
  // for). The shadow needs a little extra room below the shape only.
  const shadowRoom = shadowDy + cellSize * 0.11 * 3;
  const vbW = w, vbH = h + shadowRoom;
  const svg = `
<svg width="${vbW}" height="${vbH}" viewBox="0 0 ${vbW} ${vbH}" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <clipPath id="clip-${id}"><path d="${d}"/></clipPath>
    <linearGradient id="body-${id}" x1="0" y1="0" x2="0.4" y2="1">
      <stop offset="0" stop-color="${m.light}"/>
      <stop offset="0.5" stop-color="${m.base}"/>
      <stop offset="1" stop-color="${m.deep}"/>
    </linearGradient>
    <radialGradient id="glow-${id}" cx="0.32" cy="0.26" r="0.7">
      <stop offset="0" stop-color="#ffffff" stop-opacity="0.65"/>
      <stop offset="0.6" stop-color="#ffffff" stop-opacity="0.12"/>
      <stop offset="1" stop-color="#ffffff" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="core-${id}" cx="0.5" cy="0.5" r="0.5">
      <stop offset="0" stop-color="${m.core}" stop-opacity="0.55"/>
      <stop offset="0.7" stop-color="${m.core}" stop-opacity="0.16"/>
      <stop offset="1" stop-color="${m.core}" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="bloom-${id}" cx="0.5" cy="0.5" r="0.5">
      <stop offset="0" stop-color="#ffffff" stop-opacity="0.42"/>
      <stop offset="0.5" stop-color="#ffffff" stop-opacity="0.13"/>
      <stop offset="1" stop-color="#ffffff" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="aoSpot-${id}" cx="0.5" cy="0.5" r="0.5">
      <stop offset="0" stop-color="${m.deep}" stop-opacity="0.34"/>
      <stop offset="0.6" stop-color="${m.deep}" stop-opacity="0.12"/>
      <stop offset="1" stop-color="${m.deep}" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="rim-${id}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="${m.rimLight}"/>
      <stop offset="1" stop-color="${m.rimDeep}"/>
    </linearGradient>
    <linearGradient id="ao-${id}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0.55" stop-color="${m.deep}" stop-opacity="0"/>
      <stop offset="1" stop-color="${m.deep}" stop-opacity="0.6"/>
    </linearGradient>
    <filter id="shadowBlur-${id}" x="-60%" y="-60%" width="220%" height="220%">
      <feGaussianBlur stdDeviation="${cellSize * 0.11}"/>
    </filter>
    <filter id="specBlur-${id}" x="-100%" y="-100%" width="300%" height="300%">
      <feGaussianBlur stdDeviation="${cellSize * 0.045}"/>
    </filter>
    <filter id="softBlur-${id}" x="-100%" y="-100%" width="300%" height="300%">
      <feGaussianBlur stdDeviation="${cellSize * 0.16}"/>
    </filter>
    <filter id="aoBlur-${id}" x="-150%" y="-150%" width="400%" height="400%">
      <feGaussianBlur stdDeviation="${cellSize * 0.17}"/>
    </filter>
  </defs>

  <path d="${d}" fill="${m.shadow}" opacity="0.45" filter="url(#shadowBlur-${id})"
        transform="translate(0 ${shadowDy}) scale(0.96 0.9)"/>

  <g clip-path="url(#clip-${id})">
    <path d="${d}" fill="url(#body-${id})"/>
    ${concave.map(([qx, qy]) => `<circle cx="${qx.toFixed(2)}" cy="${qy.toFixed(2)}" r="${(cellSize*0.42).toFixed(2)}"
             fill="url(#aoSpot-${id})" filter="url(#aoBlur-${id})" style="mix-blend-mode:multiply"/>`).join('\n    ')}
    <ellipse cx="${cx.toFixed(2)}" cy="${(cy + cellSize*0.18).toFixed(2)}" rx="${cellSize*0.62}" ry="${cellSize*0.5}"
             fill="url(#core-${id})" style="mix-blend-mode:soft-light"/>
    <ellipse cx="${ax.toFixed(2)}" cy="${(ay - cellSize*0.04).toFixed(2)}" rx="${cellSize*1.05}" ry="${cellSize*0.92}"
             fill="url(#glow-${id})" style="mix-blend-mode:soft-light"/>
    <path d="${d}" fill="url(#ao-${id})" style="mix-blend-mode:multiply"/>
    <ellipse cx="${ax.toFixed(2)}" cy="${(ay - cellSize*0.14).toFixed(2)}" rx="${cellSize*0.78}" ry="${cellSize*0.56}"
             fill="url(#bloom-${id})" filter="url(#softBlur-${id})"
             style="mix-blend-mode:screen"/>
    <ellipse cx="${ax.toFixed(2)}" cy="${(ay - cellSize*0.14).toFixed(2)}" rx="${cellSize*0.58}" ry="${cellSize*0.24}"
             fill="#ffffff" opacity="0.95" filter="url(#specBlur-${id})"
             transform="rotate(-24 ${ax.toFixed(2)} ${(ay - cellSize*0.14).toFixed(2)})"
             style="mix-blend-mode:screen"/>
    <ellipse cx="${(ax + cellSize*0.34).toFixed(2)}" cy="${(ay - cellSize*0.32).toFixed(2)}" rx="${cellSize*0.24}" ry="${cellSize*0.10}"
             fill="${COATING_SHIMMER}" opacity="0.75" filter="url(#specBlur-${id})"
             transform="rotate(-24 ${(ax + cellSize*0.34).toFixed(2)} ${(ay - cellSize*0.32).toFixed(2)})"
             style="mix-blend-mode:screen"/>
    <circle cx="${(ax + cellSize*0.44).toFixed(2)}" cy="${(ay - cellSize*0.42).toFixed(2)}" r="${cellSize*0.055}"
            fill="#ffffff" opacity="0.95" style="mix-blend-mode:screen"/>
  </g>

  <path d="${d}" fill="none" stroke="url(#rim-${id})" stroke-width="${rimWidth}" opacity="0.65"/>
</svg>`.trim();

  return { svg, width: vbW, height: vbH };
}
