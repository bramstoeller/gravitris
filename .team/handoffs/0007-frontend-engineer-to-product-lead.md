# 0007 — Frontend Engineer → Product Lead

**Stage 1, Track B — the `:app` shell.**
Branch `feat/app-shell`, based on `chore/build-foundation`.
Commits `fcded24..d546475` (5 commits). Pushed to `origin` after each.

---

## What exists now

`make build` produces an installable debug APK at
`app/build/outputs/apk/debug/app-debug.apk`. `make test` runs 80 JVM tests in
`:app` (plus the existing 1 in `:core-sim`), all passing, together with lint
and the merged-manifest security checks.

Launching it gives you one piece falling into an empty well. Drag anywhere on
screen to move it, tap to rotate, flick down to hard-drop. It squashes on
impact, the phone buzzes in proportion to how hard it landed, and the
bottom-left corner shows live frame timings. Landed pieces stay, so the well
fills as you play; at 60 bodies it resets.

| Area | Where |
| ---- | ----- |
| Renderer, accumulator, frame instrument | `app/src/main/kotlin/gravitris/app/GameRenderer.kt` |
| GL buffers, shaders, lattice topology | `app/src/main/kotlin/gravitris/app/gl/` |
| Gestures → `InputFrame` | `app/src/main/kotlin/gravitris/app/input/` |
| Impact haptics | `app/src/main/kotlin/gravitris/app/haptics/` |
| Frame-time statistics and readout | `app/src/main/kotlin/gravitris/app/perf/` |
| Well geometry from insets | `app/src/main/kotlin/gravitris/app/WellLayout.kt` |
| Every tunable number from the specs | `app/src/main/kotlin/gravitris/app/Tunables.kt` |

---

## The frame-time readout — how to read it

Five lines, bottom-left, monospace, ~55% opacity:

```
 16.7ms mean  17.9ms p95
  4.1ms cpu   31.2ms max
  59.9 fps        2 jank/s
  1920 tri       60 bodies
  11.7 KB/frame  haptics:scaled
```

- **mean / p95 / max** — frame-to-frame wall time over the trailing second.
- **jank/s** — frames in the last second that missed 16.7ms.
- **cpu** — time spent stepping the simulation and filling the vertex buffer,
  i.e. everything before we hand work to the driver.
- **tri / bodies / KB per frame** — how much geometry produced those numbers.
- **haptics:scaled** vs **haptics:fixed** — whether the device has vibration
  amplitude control.

Every figure is an aggregate over the trailing second. There is deliberately no
instantaneous frame time: the text refreshes at ~4Hz, so an instantaneous
reading would be one arbitrary frame out of fifteen — noise wearing the
authority of a measurement. The *statistics* are computed from every single
frame, so a lone 40ms hitch still shows in `max` and `jank` even while `mean`
stays green. That is the specific dishonesty I built this to avoid.

**What to ask the client for:** the five lines, photographed or read out, at
three moments — an empty well, a half-full well, and a full one. The `bodies`
count makes those three comparable.

---

## Early signal on GPU cost — the risk I was closest to

I have **no measurement**. This container has no GPU and no usable emulator, so
nothing here has ever rendered a frame. Everything below is structural
reasoning, and I want it read as such.

What I can say with confidence:

- **Bandwidth is a non-issue, confirmed.** ADR 0007 estimated ~36 KB/frame; the
  Stage 1 vertex format is leaner (position only in the dynamic buffer, 8 bytes
  per particle) at **~11.7 KB/frame at full occupancy**, about 0.7 MB/s. This
  should be struck off the risk list rather than re-examined.
- **Geometry is trivial.** 1920 triangles and one draw call for the entire
  stack. No mobile GPU from the last decade cares about this.
- **The remaining risk is entirely fragment cost, and Stage 1 cannot measure
  it.** The Stage 1 fragment shader is one flat varying, one uniform lookup, one
  write — about as cheap as a fragment shader can be. So the number the client
  reports is a **floor**: the cost of geometry, overdraw and compositing with
  effectively zero per-pixel work.

**That floor is still worth having, and it is the main reason to ship this
build.** It answers three things a Stage 3 measurement could not separate:

1. Whether `GLSurfaceView` paces evenly on this panel, or whether ADR 0007's
   escape hatch (`SurfaceView` + `Choreographer`) is needed. Uneven pacing will
   show as `jank` with a healthy `mean`.
2. Whether the 60Hz `setFrameRate` request is honoured on a Fairphone 6's LTPO
   panel. If `fps` sits near 120 or wanders, it is not.
3. The CPU baseline before the real solver exists, so Stage 2 can attribute any
   regression to the solver rather than to the shell.

Then Stage 3's number on the same device, minus this one, is the true price of
the art direction. **The subtraction is the whole point — please make sure
whoever runs Stage 3 measures the same way.**

One caveat I want on the record: the `cpu` figure is honest, but `mean - cpu`
is **not** a clean GPU time. OpenGL ES 3.0 has no timer queries in core and
`GL_EXT_disjoint_timer_query` is not dependable across mobile drivers, so what
we actually measure is time blocked in `eglSwapBuffers` plus submission. Treat
it as an upper bound on GPU cost, not a measurement of it.

---

## The one thing needing a decision before merge

**I added `android.permission.VIBRATE`, which required changing the security
check the client specifically asked to have enforced.**

`Vibrator.vibrate()` requires it. The only permission-free alternative,
`View.performHapticFeedback()`, plays a fixed canned effect with no amplitude
or duration control — which discards the entire energy ramp in
`feel-feedback.md`, i.e. the thing the brief ranks highest for making blocks
read as heavy. Shipping Stage 1 without scaled haptics would have removed the
highest-return item in my scope.

CHK-1 asserted **zero** `<uses-permission>` elements. I changed it to an
explicit allowlist containing exactly `VIBRATE`, with `INTERNET` banned
unconditionally even if someone later adds it to that list.

My reasoning: the property worth protecting was never "zero permissions" — it
was "no permission arrives that nobody decided on". A dependency contributing
anything at all still turns the build red. `VIBRATE` is normal, install-time,
grants no data access and shows no runtime prompt; the no-network guarantee
rests on `INTERNET`, which is untouched and now doubly guarded.

I verified the check still fails closed by temporarily adding `INTERNET` — both
guards fired — then reverted it.

**This needs security-engineer sign-off before merge.** I changed their file and
I do not own that decision. If they prefer, the alternative is unscaled haptics
with no permission, and I would want the client told that the impacts will all
feel identical.

---

## Deviations from spec, and why

**1. Two vertex buffers, not one interleaved (ADR 0007 §1).** Positions are
dynamic and rewritten every frame; archetype indices are static per body and
rewritten only when the set of bodies changes. Interleaving would re-upload
1500 identical integers per frame. This does not conflict with Stage 3: every
varying ADR 0007 adds later (`vCompression`, `vEdge`, `vContact`, `vBodyUv`) is
per-particle and per-frame, so all of them join the dynamic buffer — the
interleaving the ADR protects is unaffected. Archetype was the one member of
that list that was never dynamic.

**2. Crossing touch slop does not spend the slop distance.** `gestures.md`
specifies the 8dp slop and the 1:1 drag mapping but not what happens to the
slop distance when a drag engages. Spending it would jump the piece by up to
8dp at the instant a drag starts. I discard exactly the slop and keep
everything beyond it. The document's own reason for reusing the platform slop
value — "so it already matches the player's muscle memory from every other
app" — argues for matching the platform's *behaviour* around it too.
**Flagging for UX to confirm.**

**3. Back pauses, with no pause screen.** ADR 0010 §2 requires back not to
destroy a run; menus are out of scope for Stage 1. Back toggles the simulation
and back again resumes. `screens/paused.md` replaces this at Stage 5.

**4. Well height is clamped to 12–30 world units.** Outside that range the world
stops being isotropic, so a squash would read differently horizontally and
vertically. No phone in portrait comes near the clamps; a tablet in landscape
would. Recorded rather than solved.

---

## What I deliberately did not do

- **No shading of any kind.** Flat colours only, per the hard constraint. See
  the open question below — this has a consequence worth a decision.
- **No landing silhouette, screen shake, band glow, or impact propagation** —
  Stages 3 and 4.
- **No game logic**: no scoring, clearing, losing, spawn sequence or difficulty
  ramp. The harness respawns pieces so the toy stays usable; that is not a
  spawn rule.
- **No settings, menus, title or game-over screens.**
- **No androidx dependency.** `dependency-policy.md` R5 sets a high bar and the
  version catalog anticipated `androidx-core-ktx` for inset handling. I did not
  need it — platform `WindowInsets` covers API 29+ with one guarded branch. The
  catalog entry is still unused; the merged manifest still has zero third-party
  contributions.
- **No `SurfaceView` + `Choreographer`.** ADR 0007's escape hatch stays
  unopened until the readout says it is needed.
- **I did not run the app.** No GPU, no emulator. I want this stated plainly
  rather than buried: nothing in this build has been seen working. See below.

---

## What I considered and rejected

**Uploading the full ADR 0007 vertex format now** (compression, edge, contact,
UV) even though the flat shader ignores them, so the per-frame buffer-fill cost
would be Stage-3-realistic from day one. Rejected: it is dead code by the
constitution's rule, and the arithmetic gets us the same information — Stage 3's
dynamic buffer will be roughly 3x this one's, and the readout reports KB/frame
so that scaling can be checked rather than assumed. I think this was close, and
if the Product Lead would rather have the pessimistic number measured than
computed, it is about twenty lines to add.

**Using `vCompression` to darken squashed material at Stage 1.** ADR 0007 calls
it "the cheapest available route to the brief's requirement that the blocks read
as heavy". Rejected because the Stage 1 constraint is explicit that flat colour
exists so the physics can be felt without art confusing the judgement. But see
the open question — I am not fully comfortable with this one.

**Writing a soft-body solver in the harness to make the demo feel real.**
Rejected outright: it is Track A's job, doing it badly would produce a false
signal at exactly the moment the client is asked "does this feel heavy", and two
solvers is worse than none. The harness is explicitly kinematic and says so at
the top of the file.

**Duplicating the contract types into `:core-sim`.** Rejected — Tracks A and B
share no files by design. The stand-ins live in
`app/src/main/kotlin/gravitris/app/sim/Contract.kt`, spelled identically to
`docs/contracts.md`, so Stage 2 integration is: delete that file, change
`gravitris.app.sim` to `gravitris.game` in three files, swap `RenderHarness` for
`Simulation`. Field names and semantics match exactly, which is the whole reason
for writing it that way.

**A separate "stress mode" to load the renderer for measurement.** Rejected as
unnecessary once landed pieces stack — normal play reaches 60 bodies on its own,
which is both the real design point and a better measurement than a synthetic
one.

---

## Things I am uneasy about

**1. Flat colour may make the demo unreadable in the way that matters most.**
With one flat colour per body, interior deformation is invisible — only the
silhouette carries the squash. The client is being asked "does this feel
heavy", and a large part of that answer is visual. I obeyed the constraint
rather than quietly compromising it, but I think there is a real risk the
Milestone 1 demo under-sells the physics for want of about six lines of shader.
**A single compression-driven darkening term is not "procedural shading" in the
gel/subsurface/rim-light sense the constraint is aimed at, and it is a rendering
response to a physical quantity rather than an animation.** I would like a
decision on this rather than making it myself.

**2. Nothing here has rendered a frame.** The GL code is written from the spec
and reviewed against it, but never executed. I mitigated what I could — the
lattice topology is extracted into a pure function and tested for index range,
cell coverage, winding, per-body offset and degenerate triangles, because a
malformed index buffer shows up as a black screen rather than a wrong picture,
and a black screen is the single most expensive failure to debug remotely. But
shader compilation, attribute binding, VAO state and the integer vertex
attribute path are all unverified. **If the first install is a black screen,
that is where to look**, and `GlProgram` throws with the driver's info log
attached specifically so the reason reaches logcat.

**3. Two real bugs were found by tests, not by review.** A `Long.MIN_VALUE`
sentinel in the rotate debounce overflowed, which would have swallowed the first
touch of every session and then every touch after it — total, silent input
failure. And the gesture recogniser was being configured from a UI-thread read
of a GL-thread-owned layout, which could have set drag sensitivity ~40x too
high. Both are fixed and covered. I mention them because both were in code I had
already read and thought was correct, which is my honest calibration on how much
of the rest is unverified.

**4. The haptic curve is untested on hardware.** The energy→duration/amplitude
mapping is exactly as specified and unit-tested, but whether 10ms at amplitude
60 is actually perceptible on a Fairphone 6, and whether 40ms at 255 reads as
"heavy" rather than "alarming", is unknowable from here. The readout reports
whether the device has amplitude control at all, so if the client says every
impact feels the same we can tell hardware from curve without a day of retuning.

---

## Open questions

1. **Does a compression-driven darkening term get pulled forward to Stage 1?**
   (Uneasy point 1. Needs Product Lead, probably with UX.)
2. **Does the slop-crossing behaviour match UX's intent?** (Deviation 2.)
3. **Security-engineer sign-off on the CHK-1 allowlist change.** Blocks merge.
4. **Is `bodyLattice` fixed at 5 for Milestone 1**, or does the startup quality
   calibration (ADR 0009) need to select it? The renderer takes lattice as a
   constructor parameter and the topology is built per lattice size, so
   supporting 4/5/6 is small — but nothing selects it today.

---

## For the next agent

- Stage 2 integration is described precisely at the top of
  `app/src/main/kotlin/gravitris/app/sim/Contract.kt`. Read it before touching
  anything in `:app`.
- `RenderHarness` and `Contract.kt` are both marked for deletion at Stage 2.
  Neither should be extended.
- Every tunable from `gestures.md` and `feel-feedback.md` is a named constant in
  `Tunables.kt`. Both documents ask for this explicitly so numbers can be
  retuned against the client's device without a rebuild-from-spec. Please do not
  inline any of them back.
- The vertex format is the thing Stage 3 will need to grow. `BodyMesh` and
  `Shaders` are the only two files involved.
