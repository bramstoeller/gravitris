export function hexToRgb(hex) {
  const h = hex.replace('#', '');
  return [parseInt(h.slice(0,2),16), parseInt(h.slice(2,4),16), parseInt(h.slice(4,6),16)];
}
export function rgbToHex([r,g,b]) {
  return '#' + [r,g,b].map(v => Math.round(Math.max(0,Math.min(255,v))).toString(16).padStart(2,'0')).join('');
}
export function mix(hexA, hexB, t) {
  const a = hexToRgb(hexA), b = hexToRgb(hexB);
  return rgbToHex(a.map((v,i) => v + (b[i]-v)*t));
}
export const WHITE = '#ffffff';
export const BLACK = '#000000';

// The 7 tetromino hues: candy-saturated, genre-conventional (I=cyan, O=yellow,
// T=purple, S=green, Z=red, J=blue, L=orange) so muscle memory transfers,
// each with light/base/deep stops derived from one base hex so the material
// recipe is mechanically the same piece to piece (only the base hex differs).
export const HUES = {
  I: { name: 'Blue Raspberry', base: '#22C7E5' },
  O: { name: 'Mango Lemon',    base: '#FFCC33' },
  T: { name: 'Grape Fizz',     base: '#B463FF' },
  S: { name: 'Green Apple',    base: '#42D97A' },
  Z: { name: 'Cherry',         base: '#FF5470' },
  J: { name: 'Blueberry',      base: '#4A7DFF' },
  L: { name: 'Tangerine',      base: '#FF9A3D' },
};

export function materialStops(baseHex) {
  return {
    light: mix(baseHex, WHITE, 0.45),
    base: baseHex,
    deep: mix(baseHex, BLACK, 0.38),
    rimLight: mix(baseHex, WHITE, 0.70),
    rimDeep: mix(baseHex, BLACK, 0.55),
    shadow: mix(mix(baseHex, '#8a5a3a', 0.5), BLACK, 0.25), // warm tray-coloured shadow, not black
    // A deep, saturated pool tone for the warm inner-glow core: thicker gel
    // reads warmer and denser at its own centre, not whiter. Still a
    // mechanical mix of the one base hex -- no hand-tuned colour added.
    core: mix(baseHex, BLACK, 0.15),
    // Winegum pass (v3): three more mechanical mixes of the same one base
    // hex, each with exactly one job in the material recipe below.
    //
    // A real translucent gummy doesn't glow neutral-white where light
    // passes through it -- the light picks up the medium's own colour. The
    // subsurface glow ellipse used to be flat white; `bright` replaces that
    // white with a still-luminous but hue-tinted stop, so the "light from
    // within" reads as candy-coloured, not a white bulb under the surface.
    bright: mix(baseHex, WHITE, 0.68),
    // A thin thing glows brighter than a thick thing under the same light --
    // basic subsurface behaviour, and the reason a real gummy's own edge
    // (its thinnest cross-section) rims brighter than its middle. `edgeGlow`
    // is that fresnel/edge-translucency band, one step less washed-out than
    // `bright` so it stays legible as a thin line rather than blowing out.
    edgeGlow: mix(baseHex, WHITE, 0.55),
    // The "chunky 3D volume" ask needs an edge that reads as thickness on
    // *every* side, not just the bottom (the existing bottom-anchored `ao`
    // gradient already covers "grounded," this covers "solid"). `edgeShade`
    // is deliberately darker than `deep` -- the bevel it shades needs to
    // read as a distinct structural crease turning the corner of a thick
    // mass, not a continuation of the same top-light/bottom-deep gradient.
    edgeShade: mix(baseHex, BLACK, 0.50),
  };
}

// The thin iridescent sliver riding the edge of the specular gleam is a
// shared "wet coating" effect (like a candy shell's clear glaze), not a
// per-hue material property -- every piece gets the exact same coating
// tint, the way a real glazed candy's clear coat doesn't change with
// flavour. One constant, not seven tuned values.
export const COATING_SHIMMER = '#D8F0FF';
