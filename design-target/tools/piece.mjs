import { outlinePath } from './shape.mjs';
import { materialStops, COATING_SHIMMER } from './color.mjs';
import { mostLitCell } from './light.mjs';

let uid = 0;

// Renders one candy-material tetromino piece as an SVG fragment.
// Returns { svg, width, height } where svg is a self-contained <svg> element
// (own defs, viewBox) that can be dropped anywhere and scaled by width/height.
//
// v3 ("winegum pass"): the client's own words were "I expected more
// wine-gum-like blocks" -- the material was reading as a flat glossy hard-
// candy gem, not a dense chewy winegum. Real wine gums (Haribo/Maoam-style)
// were looked at directly for this pass, not worked from memory (see
// README "Winegum reference" for what was actually pulled and what it
// confirmed: a firm, dense, chewy body; a soft satin sheen from a thin wax/
// sugar glaze, not a mirror-hard streak; and -- the physically defining
// trait of any thick translucent gel -- light visibly gathering deeper and
// warmer at the thick centre while the thinnest cross-section, the piece's
// own edge, rims brighter, the ordinary behaviour of subsurface scattering
// in a dense medium). Three shifts do that work below: a softer/broader
// specular (satin, not glass), a harder push on subsurface glow at both the
// centre (already had this) and now the edge (new), and an all-around
// inner bevel that darkens every edge, not just the bottom, so the piece
// reads as a solid chewy mass with real thickness rather than a flat
// rounded rectangle. See README "Material recipe" for the full annotated
// layer list -- this comment covers only what changed and why.
export function renderPiece(cells, hueBase, { cellSize = 120, cornerRatio = 0.38 } = {}) {
  const id = 'p' + (uid++);
  const radius = cellSize * cornerRatio;
  const margin = 0.55;
  const { d, width, height, concave } = outlinePath(cells, cellSize, radius, margin);
  const m = materialStops(hueBase);
  const w = width, h = height;
  const shadowDy = cellSize * 0.24;
  const rimWidth = Math.max(2, cellSize * 0.05);

  // Anchor the highlight/glow to an actual filled cell, and specifically to
  // whichever one is most "toward" the page's one fixed light direction
  // (tools/light.mjs) -- never a bounding-box fraction (an L or S piece's
  // mass sits far from its bbox corner; a bbox-relative highlight lands in
  // clipped-away empty space and simply doesn't render) and never a plain
  // topmost-then-leftmost grid sort either. That sort is what this used to
  // do, and it happened to agree with the light direction for a piece's own
  // spawn orientation only because grid order and the light's own lean both
  // run top-to-bottom, left-to-right -- it was never actually reading the
  // light vector, so nothing guaranteed it would keep agreeing once the
  // piece's cells were reshuffled by a quarter-turn. mostLitCell() reads
  // the same LIGHT_DIR constant every other lit effect on this piece reads,
  // so the anchor a piece picks in ANY rotation is always "whichever real
  // part of this shape the fixed light would reach first" -- see
  // design-target/README.md "World-anchored lighting" and
  // 04-rotation-strip.png, which exists specifically to show this holds.
  const [ac, ar] = mostLitCell(cells);
  const marginPx = margin * cellSize;
  const ax = (ac + 0.5) * cellSize + marginPx;
  const ay = (ar + 0.5) * cellSize + marginPx;

  // How much real surface does the lit cell actually have to carry a
  // highlight on, in each direction? A cellSize-scale highlight cluster
  // looks like a soft rounded spot on a piece that is several cells wide at
  // the anchor -- but a piece that is only ONE cell wide there (a vertical
  // I, or the plain stem of a rotated L/J) has no width for a gradient
  // that size to fall off across, so instead of a spot it paints the whole
  // available width uniformly bright: a flat wash, not a highlight (found
  // by rendering the rotated L on its own and looking -- its stem bleached
  // out almost entirely). rowSpan/colSpan is the piece's own real extent
  // through the anchor cell, in cells; every highlight-cluster radius below
  // is capped to it, so the gradient always has to do at least some of its
  // own falloff *inside* the piece instead of maxing out across the whole
  // available surface.
  const rowSpan = cells.filter(([, r]) => r === ar).length;
  const colSpan = cells.filter(([c]) => c === ac).length;
  const hiRx = (want) => Math.min(want, rowSpan * cellSize * 0.62);
  const hiRy = (want) => Math.min(want, colSpan * cellSize * 0.62);

  // Warm inner-glow "pool" sits toward the piece's own centre of mass, not
  // the highlight corner -- it reads as light gathering in the thickest
  // part of the gel, the way real subsurface scattering is strongest where
  // the material is deepest, independent of where the surface gleam is.
  const centreC = cells.reduce((s, c) => s + c[0], 0) / cells.length;
  const centreR = cells.reduce((s, c) => s + c[1], 0) / cells.length;
  const cx = (centreC + 0.5) * cellSize + marginPx;
  const cy = (centreR + 0.5) * cellSize + marginPx;

  // A multi-lobe piece (S/L/J/Z/T) can have real mass a couple of cells
  // away from both the lit anchor and the centroid pool above -- caught by
  // actually rendering an L and looking, not assumed: the far arm of a
  // fixed-radius glow read as flat and muddy, all bevel-shadow and no
  // subsurface lift, exactly where the client's own "juicy" ask most needed
  // to hold. Sizing the core pool's own radius off the piece's bounding box
  // (not a flat cellSize constant) is the first half of that fix.
  const minC = Math.min(...cells.map(c => c[0])), maxC = Math.max(...cells.map(c => c[0]));
  const minR = Math.min(...cells.map(c => c[1])), maxR = Math.max(...cells.map(c => c[1]));
  const bboxW = (maxC - minC + 1) * cellSize;
  const bboxH = (maxR - minR + 1) * cellSize;
  const coreRx = Math.max(cellSize * 0.74, bboxW * 0.5);
  const coreRy = Math.max(cellSize * 0.6, bboxH * 0.5);

  // The second half is an "ambient fill" wash, one small soft blob PER
  // filled cell rather than one big ellipse sized to the whole bounding
  // box -- tried the bbox version first and it overcorrected: a bbox
  // ellipse sized for a wide 3-cell arm is, by construction, just as wide
  // when that same piece's *other* arm is a single cell across, so it
  // washed a thin column out almost entirely (rendered and looked: the
  // rotated L's plain 1-wide stem, screen right next to its own foot,
  // bleached pale while the foot read fine). A per-cell blob's radius
  // answers to the one cell it sits on, so a thin stem gets exactly the
  // same modest lift as any other single cell, and only genuinely wide
  // spans (where several blobs overlap) build up any extra brightness --
  // proportional to how much actual mass is there, not to the shape's
  // overall silhouette extent.
  const ambientCells = cells.map(([c, r]) =>
    [(c + 0.5) * cellSize + marginPx, (r + 0.5) * cellSize + marginPx]);

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
      <stop offset="0" stop-color="${m.bright}" stop-opacity="0.58"/>
      <stop offset="0.6" stop-color="${m.bright}" stop-opacity="0.15"/>
      <stop offset="1" stop-color="${m.bright}" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="core-${id}" cx="0.5" cy="0.5" r="0.5">
      <stop offset="0" stop-color="${m.core}" stop-opacity="0.72"/>
      <stop offset="0.7" stop-color="${m.core}" stop-opacity="0.24"/>
      <stop offset="1" stop-color="${m.core}" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="ambient-${id}" cx="0.5" cy="0.5" r="0.5">
      <stop offset="0" stop-color="${m.bright}" stop-opacity="0.16"/>
      <stop offset="0.6" stop-color="${m.bright}" stop-opacity="0.05"/>
      <stop offset="1" stop-color="${m.bright}" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="bloom-${id}" cx="0.5" cy="0.5" r="0.5">
      <stop offset="0" stop-color="#ffffff" stop-opacity="0.26"/>
      <stop offset="0.5" stop-color="#ffffff" stop-opacity="0.08"/>
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
      <stop offset="0.6" stop-color="${m.deep}" stop-opacity="0"/>
      <stop offset="1" stop-color="${m.deep}" stop-opacity="0.42"/>
    </linearGradient>
    <filter id="shadowBlur-${id}" x="-60%" y="-60%" width="220%" height="220%">
      <feGaussianBlur stdDeviation="${cellSize * 0.11}"/>
    </filter>
    <filter id="specBlur-${id}" x="-100%" y="-100%" width="300%" height="300%">
      <feGaussianBlur stdDeviation="${cellSize * 0.095}"/>
    </filter>
    <filter id="softBlur-${id}" x="-100%" y="-100%" width="300%" height="300%">
      <feGaussianBlur stdDeviation="${cellSize * 0.16}"/>
    </filter>
    <filter id="aoBlur-${id}" x="-150%" y="-150%" width="400%" height="400%">
      <feGaussianBlur stdDeviation="${cellSize * 0.17}"/>
    </filter>
    <filter id="bevelBlur-${id}" x="-40%" y="-40%" width="180%" height="180%">
      <feGaussianBlur stdDeviation="${cellSize * 0.065}"/>
    </filter>
    <filter id="fresnelBlur-${id}" x="-40%" y="-40%" width="180%" height="180%">
      <feGaussianBlur stdDeviation="${cellSize * 0.028}"/>
    </filter>
  </defs>

  <path d="${d}" fill="${m.shadow}" opacity="0.45" filter="url(#shadowBlur-${id})"
        transform="translate(0 ${shadowDy}) scale(0.96 0.9)"/>

  <g clip-path="url(#clip-${id})">
    <path d="${d}" fill="url(#body-${id})"/>
    <!-- Ambient subsurface fill: one very soft, bbox-sized wash, independent
         of both the lit anchor and the centroid pool below. A thick gel
         scatters light through its *whole* body, not only the surface
         facing the lamp -- without this, a multi-lobe piece's far arm (an
         L's long foot, an S's far lobe) sat outside every other glow's
         reach and read flat and muddy, all bevel-shadow and no "lit from
         within" (found by rendering an L and looking, not assumed). -->
    ${ambientCells.map(([ex, ey]) => `<circle cx="${ex.toFixed(2)}" cy="${ey.toFixed(2)}" r="${(cellSize*0.64).toFixed(2)}"
             fill="url(#ambient-${id})" style="mix-blend-mode:soft-light"/>`).join('\n    ')}
    ${concave.map(([qx, qy]) => `<circle cx="${qx.toFixed(2)}" cy="${qy.toFixed(2)}" r="${(cellSize*0.42).toFixed(2)}"
             fill="url(#aoSpot-${id})" filter="url(#aoBlur-${id})" style="mix-blend-mode:multiply"/>`).join('\n    ')}
    <!-- Chunky-3D bevel: a stroke on the piece's own outline, half of it
         clipped away by that same outline (this g is already clipped to
         the same silhouette path), leaving only an inset dark band hugging
         the INSIDE of every edge -- top, sides and bottom alike, not just
         the bottom-anchored ao gradient below. This is the "soft inner
         shadow that reads as thickness" the winegum brief asks for: a real
         self-shadow where a thick chewy mass turns the corner at its own
         silhouette. -->
    <path d="${d}" fill="none" stroke="${m.edgeShade}" stroke-width="${cellSize * 0.3}"
          opacity="0.32" filter="url(#bevelBlur-${id})" style="mix-blend-mode:multiply"/>
    <ellipse cx="${cx.toFixed(2)}" cy="${(cy + cellSize*0.18).toFixed(2)}" rx="${coreRx.toFixed(2)}" ry="${coreRy.toFixed(2)}"
             fill="url(#core-${id})" style="mix-blend-mode:soft-light"/>
    <ellipse cx="${ax.toFixed(2)}" cy="${(ay - cellSize*0.04).toFixed(2)}" rx="${hiRx(cellSize*0.95).toFixed(2)}" ry="${hiRy(cellSize*0.82).toFixed(2)}"
             fill="url(#glow-${id})" style="mix-blend-mode:soft-light"/>
    <path d="${d}" fill="url(#ao-${id})" style="mix-blend-mode:multiply"/>
    <!-- Fresnel / edge-translucency: a thin, brighter band right at the
         silhouette boundary itself (thinner and crisper than the bevel
         above, which sits a little further in). A thin cross-section
         passes more light than a thick one under the same light -- this is
         that, made visible: the piece's own edge glows, on top of the
         bevel's structural shadow just inside it. -->
    <path d="${d}" fill="none" stroke="${m.edgeGlow}" stroke-width="${cellSize * 0.12}"
          opacity="0.22" filter="url(#fresnelBlur-${id})" style="mix-blend-mode:screen"/>
    <ellipse cx="${ax.toFixed(2)}" cy="${(ay - cellSize*0.14).toFixed(2)}" rx="${hiRx(cellSize*0.86).toFixed(2)}" ry="${hiRy(cellSize*0.66).toFixed(2)}"
             fill="url(#bloom-${id})" filter="url(#softBlur-${id})"
             style="mix-blend-mode:screen"/>
    <ellipse cx="${ax.toFixed(2)}" cy="${(ay - cellSize*0.14).toFixed(2)}" rx="${hiRx(cellSize*0.68).toFixed(2)}" ry="${hiRy(cellSize*0.34).toFixed(2)}"
             fill="#ffffff" opacity="0.44" filter="url(#specBlur-${id})"
             transform="rotate(-24 ${ax.toFixed(2)} ${(ay - cellSize*0.14).toFixed(2)})"
             style="mix-blend-mode:screen"/>
    <ellipse cx="${(ax + cellSize*0.34).toFixed(2)}" cy="${(ay - cellSize*0.32).toFixed(2)}" rx="${cellSize*0.24}" ry="${cellSize*0.10}"
             fill="${COATING_SHIMMER}" opacity="0.5" filter="url(#specBlur-${id})"
             transform="rotate(-24 ${(ax + cellSize*0.34).toFixed(2)} ${(ay - cellSize*0.32).toFixed(2)})"
             style="mix-blend-mode:screen"/>
    <circle cx="${(ax + cellSize*0.44).toFixed(2)}" cy="${(ay - cellSize*0.42).toFixed(2)}" r="${cellSize*0.05}"
            fill="#ffffff" opacity="0.85" style="mix-blend-mode:screen"/>
  </g>

  <path d="${d}" fill="none" stroke="url(#rim-${id})" stroke-width="${rimWidth}" opacity="0.65"/>
</svg>`.trim();

  return { svg, width: vbW, height: vbH };
}
