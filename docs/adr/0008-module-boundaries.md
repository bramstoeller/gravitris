# 0008. Two modules: a framework-free simulation core and an Android shell

Status: proposed
Date: 2026-07-20

## Context

QA needs to test physics deterministically on the JVM without a device — that is
the stated requirement, and ADR 0006's determinism guarantee is only useful if the
simulation can actually be instantiated outside Android. That is a constraint on
module structure, not a preference.

The counter-pressure is the client's standing instruction to keep the first
iteration simple. A module per concern would be architecture for its own sake on a
single-mode game.

## Decision

**Two Gradle modules.**

```
:core-sim   pure Kotlin/JVM. Depends on the Kotlin stdlib and nothing else.
            No Android imports, ever.
            packages:  physics/   solver, bodies, contacts, broadphase
                       game/      pieces, spawning, coverage bands, rules, scoring
:app        Android application. Depends on :core-sim.
            GL renderer, input, haptics, audio, lifecycle, settings persistence,
            the dev tuning panel.
```

Two modules, not five. `physics` and `game` are *packages* inside `:core-sim`
rather than separate modules because they change together, are tested together,
and splitting them would buy a boundary nobody needs to cross independently.

**The rule that matters: `:core-sim` has no Android dependency.** Not "minimal" —
none. This is enforced mechanically by a build check that fails if any
`android.*` or `androidx.*` import appears in `:core-sim`, so the boundary cannot
erode by accident. That single rule delivers:

- `:core-sim` tests run as plain JVM JUnit — no device, no emulator, no
  Robolectric, milliseconds per test.
- The solver spike already runs this way, so the approach is demonstrated rather
  than hoped for.
- Deterministic replay fixtures (ADR 0006) are ordinary JVM tests.
- A headless desktop harness for tuning is possible later at near-zero cost. Not
  built now.

**Direction of dependency is strictly one way.** `:core-sim` never calls into
`:app`. It has no callbacks, no listeners, no observers into the shell. The
simulation advances and exposes state; the shell reads state and renders it. There
is no inversion-of-control seam because there is nothing to invert.

**State is exposed as plain arrays, read-only by convention.** `SimState` hands
out the underlying `FloatArray`s rather than wrapping them in accessors or copying
them. This is deliberate and it is a real tradeoff: it means the renderer *could*
write to simulation state, and nothing stops it at compile time. The alternative —
defensive copies each frame — would allocate on every frame, which ADR 0001
measured at 0 bytes/frame and intends to keep there. **Convention plus code review
is the enforcement mechanism, and it is written here so the choice is explicit
rather than sloppy.**

**Configuration and tuning.** All tunables (compliance, coverage threshold, grace
window, substeps, damping, friction) live in one `SimConfig` data class passed at
construction. The dev tuning panel in `:app` rebuilds a `SimConfig` and restarts
the simulation; it does not reach into the core and mutate fields. That keeps the
core free of live-mutation concerns and keeps determinism intact for a given
config.

The full interface contract is in `/work/docs/contracts.md`.

## Alternatives considered

**Single module** — rejected. It is simplest, and it is what "keep it simple"
argues for on its face. It lost on the one hard requirement: with Android on the
classpath there is nothing preventing the solver from picking up an Android
dependency, and the moment it does, JVM testing is gone and with it the whole
determinism strategy. The second module is the cheapest possible guarantee.

**Four or five modules (`:solver`, `:game`, `:render`, `:app`, `:tuning`)** —
rejected. It would be the conventional shape for a larger product and gives
finer-grained build caching. It lost to the client's simplicity instruction and to
honesty about scale: nobody will depend on `:solver` without `:game`, no second
renderer is planned, and the module boundaries would cost coordination on every
change while preventing nothing. **Trigger to revisit:** a second game mode, or a
second rendering backend — neither of which is on any roadmap.

**A `SimState` interface with defensive copies** — rejected on the allocation
grounds above. A per-frame copy of 1 500 particles is ~12 KB of garbage per frame,
90 KB/s, which is exactly the GC pressure ADR 0001 chose SoA to avoid.

**An event/observer bus between core and shell** — rejected. Haptics and audio
need to know about impacts, which is the usual reason to reach for events. Simpler
answer: the core exposes an impact list in `SimState` that the shell drains each
frame. No subscription, no lifetime management, no allocation, and it stays
deterministic because it is just more state.

**Making `:core-sim` a Kotlin Multiplatform module** — rejected. It would cost
nothing much today and enable a future port. The brief says Android-only with no
port path planned, and the client confirmed it directly when Godot was rejected.
Building for a port that has been explicitly ruled out is the definition of a
speculative extension point.

## Consequences

**Easy.** Physics is testable in milliseconds on the JVM, which makes QA's replay
strategy practical and makes tuning iterations fast. The backend and frontend
engineers work against one narrow, explicit seam and can proceed in parallel from
day one — that is what makes the Milestone 1 build order possible. The core has no
lifecycle, no threading model and no framework, so it is the easiest part of the
product to reason about.

**Hard.** The no-Android rule needs the build check to be written early, or it
will be violated within a week by something innocuous like using `android.util.Log`
for debugging. Exposing raw arrays trades compile-time safety for zero allocation,
and that trade must be actively policed in review rather than assumed.

**Live with.** Two modules means a slightly more involved Gradle setup than one,
and the `:app` module carries everything Android — renderer, input, settings, dev
panel — so it will be the larger and less-tested half. That asymmetry is
acceptable because the risky, subtle logic is on the tested side of the line; but
it does mean the renderer is the part most likely to harbour bugs, and QA should
plan for that rather than assume the JVM tests cover the product.
