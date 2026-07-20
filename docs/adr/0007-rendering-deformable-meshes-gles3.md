# 0007. Rendering deformable meshes on OpenGL ES 3.0

Status: proposed
Date: 2026-07-20

## Context

Every body deforms every frame, so its geometry is new every frame — the opposite
of the static-mesh case GPUs are optimised for. The look is procedural: gel,
rubber and dough via subsurface approximation, noise grain and rim lighting, with
no authored texture assets, and piece identity carried by hue because shape
deforms away. Bands that are nearly full glow warmly from within.

The UX Designer is specifying that look in parallel, so this ADR must define
*where the shading hooks in* and *what data it can rely on*, without specifying
the look itself.

## Decision

**One dynamic vertex buffer for all bodies, rewritten per frame; static index
buffers; a uniform block for per-body parameters; and band fill delivered as a
small array the fragment shader samples by world height.**

**1. Geometry upload.** All bodies share a single interleaved VBO. Each frame the
renderer walks the simulation's position arrays, applies the ADR 0006
interpolation lerp, and fills the buffer, then uploads with buffer orphaning
(`glBufferData(NULL)` followed by `glBufferSubData`, or `glMapBufferRange` with
`INVALIDATE_BUFFER | UNSYNCHRONIZED`) so the GPU never stalls waiting on the
previous frame's draw.

The bandwidth is trivial and worth stating because it removes a whole category of
premature optimisation: at the default tier, 1 500 particles x ~24 bytes per
vertex is **~36 KB per frame**, about 2 MB/s. This is not a bottleneck and should
not be treated as one.

**2. Index buffers are static.** A body's lattice topology never changes — only
its vertex positions do. Indices are uploaded once per lattice size (three of
them, one per quality tier) and reused for every body forever.

**3. Draw calls.** All bodies of the same lattice size share one draw call.
Per-body parameters (hue, glow, compression tint) live in a uniform block indexed
by a per-vertex body index attribute. In practice this is **one or two draw calls
for the entire stack**.

**4. Where shading hooks in — the contract with the UX Designer.** The vertex
stage supplies the fragment stage with a fixed set of varyings, and the look is
authored as a fragment function consuming only those:

| varying | meaning |
| ------- | ------- |
| `vBodyUv` | position within the body's own lattice, 0..1 — the material coordinate for noise and subsurface depth |
| `vWorldPos` | world position — for band glow lookup and lighting |
| `vBodyIndex` (flat) | piece archetype index into the palette uniform block |
| `vCompression` | current cell area / rest area — how squashed this part of the body is |
| `vEdge` | free surface, 0 interior → 1 boundary — the **brightening** rim light |
| `vContact` | contact with neighbouring material, 0..1 — the **darkening** seam, ambient occlusion, and deep colour seen through overlapping translucent material |

**Two corrections after reading the UX specs**, both of which would have caused
rework:

- **A single `vHue` float is not enough.** The palette varies saturation,
  lightness *and* grain scale independently per piece, and the alternating
  lightness is the colour-vision-deficiency backup cue — not decoration. Passing
  a hue would force a hue→(S,L,grain) lookup table in the shader that breaks
  the moment a seventh piece is added. Passing an **archetype index** into a
  palette uniform block costs the same and extends without a shader change.
- **One `vEdge` cannot serve both edge treatments.** A free surface against empty
  space *brightens* (rim light); a contact surface against a neighbour *darkens*
  (seam/crease), and that seam is ranked above the lightness ladder as the primary
  small-screen legibility cue. They must be separate terms. `vContact` comes free
  from the contact solve, which already knows neighbour count and penetration
  depth, and it doubles as the overlap-thickness term for subsurface depth —
  something `vCompression` (a body's own local area ratio) cannot express.

`vCompression` is the one worth arguing for: the area constraints (ADR 0001)
already compute it, it costs nothing to pass, and it lets the shader darken and
saturate compressed material. That is the cheapest available route to the brief's
requirement that the blocks read as *heavy*, and it is a rendering response to a
physical quantity rather than an animation.

**5. Band glow.** The band fill array from ADR 0004 is uploaded as a 20-float
uniform array each frame, **alongside a per-band clear-envelope progress array** —
fill alone cannot drive the clear animation, because a band at fill 1.0 is
indistinguishable from a band mid-dissolve, and the shader must know when it is
inside the ~120ms ignition flash since that is the one moment the emissive blend
may exceed its normal cap. The fragment shader maps `vWorldPos.y` to a band index
and modulates emissive output by fill. This keeps the glow a property of *space*
rather than of bodies, which is what the mechanic means: a band glows because that
region is nearly full, regardless of which pieces occupy it. It also means a body
straddling two bands glows correctly across its own height, with no per-body
bookkeeping.

**6. Accessibility is a render-layer concern.** Screen shake is a view-matrix
offset, so "reduced motion" disables it by zeroing one vector.

**Correction: reduced motion must NOT damp the solver**, which is what I first
specified. Two reasons, both serious. The primary squash on impact is explicitly
*unchanged* under reduced motion — it is the core weight cue, not the repetitive
motion the setting exists to remove, and damping it would silently break the
brief's success criterion about reading as heavy. And a solver-level toggle would
make physics, game outcomes and replay tests differ per user setting: **an
accessibility preference must never change what happens, only how it is drawn.**

Jiggle is therefore damped as a temporal low-pass on each particle's deviation
from its body centroid, applied during render interpolation. High-frequency
ringing is suppressed; the low-frequency squash survives; the simulation is
untouched and `SimConfig` carries no accessibility fields.

**7. No screen-space bloom and no HDR post-process** — confirmed to the UX
Designer, since it changes how celebratory the ignition flash can read. The flash
comes from the emissive blend alone. Dither noise reuses the gel-grain procedural
field and must not be baked into a texture asset.

**8. Surface.** `GLSurfaceView` with `RENDERMODE_CONTINUOUSLY` and
`preserveEGLContextOnPause`. It is the boring choice and it paces on
`eglSwapBuffers`, which is adequate for the accumulator in ADR 0006. **Trigger to
revisit:** if frame pacing proves uneven at Milestone 1, move to a plain
`SurfaceView` with our own EGL setup driven by `Choreographer`. Recorded so the
escape hatch is known rather than discovered.

## Alternatives considered

**Transform feedback / compute the deformation on the GPU** — rejected. It would
avoid the per-frame upload entirely and is the "proper" GPU answer. It lost
because the simulation must stay on the CPU: ADR 0008 requires a framework-free
core that QA can test on the JVM, and ADR 0006 requires bit-identical determinism,
neither of which survives moving physics onto the GPU. It also solves a bandwidth
problem we measured at 2 MB/s and therefore do not have.

**One draw call and one VBO per body** — rejected. It is the naive structure and
simpler to write per body, but it multiplies draw calls and state changes by the
number of pieces in the well (up to ~60). Batching into one buffer is barely more
code and an order of magnitude fewer calls.

**Persistent mapped buffers** — rejected. `GL_MAP_PERSISTENT_BIT` is not in
OpenGL ES 3.0 core, so it would mean an extension path with a fallback for a
bandwidth problem we do not have.

**Signed-distance-field or metaball rendering of the material** — rejected, and it
is the visually tempting option: it would let bodies visually merge into a
continuous mass, which suits "dough". It lost on two grounds. It is fragment-heavy
on a mobile GPU, which is the budget ADR 0006 is already protecting by staying at
60Hz. More importantly it works *against* the brief's requirement that individual
blocks stay legible once deformed together, and against hue as the identity cue —
merging material is precisely what must not happen visually.

**Authored texture atlases for the material look** — rejected, and already
rejected at the brief stage. Recorded here because it is the obvious fallback if
procedural shading disappoints; the brief makes it revisitable at the release
gate, and this ADR's varying contract would survive that change unaltered.

**Per-vertex normals uploaded from the CPU** — rejected. A 2D game's rim lighting
can be derived from `vEdge` and the body UV gradient in the fragment shader, which
avoids growing the vertex format and the per-frame upload for a lighting model
that is stylised rather than physical.

## Consequences

**Easy.** One or two draw calls for the whole scene. The art direction is a single
fragment shader with named uniforms, so the UX Designer's parameters are tunable
at runtime and the look can change without touching the renderer's structure. The
`vCompression` varying makes "reads as heavy" a shading problem with a physical
input rather than an animation problem. Band glow needs no per-body state.

**Hard.** The vertex buffer is rebuilt on the CPU every frame, which is a real
per-frame CPU cost on top of the solver and must be counted in the frame budget
(ADR 0009), not treated as free because it is "rendering". The varying set is a
contract: adding a varying later means touching the vertex format, the buffer fill
and both shaders, so it is worth the UX Designer confirming the list above covers
the intended look **before** frontend work starts.

**Live with.** Band glow quantises to 20 bands, so the glow boundary is a
horizontal step rather than a smooth gradient. If that reads badly the fix is to
interpolate between adjacent band fills in the shader — cheap, but it is a change
we should expect rather than be surprised by.
