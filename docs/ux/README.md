# UX specs — index

Read in this order if you're implementing from scratch:

1. `flows.md` — the three journeys that matter, entry to outcome, with
   drop-out risk marked.
2. `ia.md` — the five screens and how navigation between them works.
3. `band-glow.md` — **the core visual language.** How fill% maps to glow,
   how it teaches coverage-band clearing with no tutorial. Read this before
   touching any material shader.
4. `piece-identity.md` — the six-hue colourblind-safe piece palette, and the
   reserved amber band that keeps it from colliding with band-glow.
5. `gestures.md` — concrete drag/tap/swipe thresholds.
6. `landing-silhouette.md` — how the pre-drop projection stays honest about
   a genuinely uncertain landing shape.
7. `feel-feedback.md` — haptics, screen shake, and the full timeline of the
   band-clear payoff sequence.
8. `tokens.md` — colour, type, spacing, radii, shadow, UI-chrome motion.
9. `accessibility.md` — what reduced motion actually changes, contrast,
   target size, focus order, and the CVD verification status.
10. `screens/` — per-screen layout and every state (empty, loading,
    populated sparse/dense, error, offline-N/A) for Title, Playing, Paused,
    Game Over, Settings.

Not part of the current build: `pace.md` — a reaction-time accommodation
(decoupling fall speed from block mass) that was briefly first-class, then
deferred by the client to a later iteration. Kept, clearly marked deferred,
so the reasoning isn't lost. Do not build against it without checking with
the Product Lead.

All numeric thresholds and timings in this directory (gesture thresholds,
glow ramps, haptic/shake curves, clear-sequence timing) are **prototype-
milestone starting points**, not final values — they're written as named,
tunable constants specifically so they can be retuned against the client's
real device per the brief's Performance Verification section. All of them
are wall-clock (ms / dp / dp-per-second), not frame-based — the confirmed
client device (Fairphone 6) runs an adaptive 10–120Hz panel, and nothing in
this spec set assumes or requires 120Hz to work correctly.
