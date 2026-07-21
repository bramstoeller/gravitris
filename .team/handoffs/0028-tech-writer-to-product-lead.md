# 0028 — Tech Writer → Product Lead

Branch `docs/install-real-game` off `main` (based on `1fbe3d6`, PR #19 merged
partway through this task). Commit `1757d4c`. PR #20, open, CI running.

## What I did

Rewrote the client-facing install doc for the real game and retired the
Milestone-1 version:

- `docs/install-milestone-1.md` → `docs/install.md` (git-tracked rename via
  delete+add so history shows both). Retitled away from "milestone 1" per the
  brief — this is the game now, not a toy demo.
- Rewrote every section for what the merged code on `main` actually does:
  pieces spawn one at a time and accumulate, bands glow and clear, a losing
  condition exists with a settle grace and tap-to-restart.
- Kept the Security Engineer's privacy wording close to verbatim — **"no
  internet access"**, not "no permissions"; `VIBRATE` is explained; the
  correction note ("we previously said no permissions, that's no longer
  accurate") is preserved. Did not touch this wording beyond copy-fitting it
  into the new structure.
- Added a plain-language "what we'd like to know" section (heaviness, glow
  warning, clear payoff, fair losing, difficulty) and an honest-caveats
  section, per the brief for this task.
- Fixed the one live cross-reference to the old filename, in
  `docs/operations.md` line 65. Did **not** touch historical handoffs
  (`0009`, `0022`, `0026`) that mention `install-milestone-1.md` — those are
  records of what was true when written, not live documentation.

## Verified, not assumed

Every specific claim in the doc was checked against source, not taken from
the brief or the ADRs at face value:

- **Clear threshold is 0.80, not the brief's ~90% or my own task brief's
  0.80-stated-as-fact.** I found `core-sim/.../SimConfig.kt:104` defaults to
  `0.90f` on both `main` and (at the time) `feat/wire-real-game`, which
  contradicted my brief. I messaged the Frontend Engineer directly rather
  than guess or silently pick one number. While I was investigating, they
  independently caught the same drift, pushed `f4766f3` (`app/.../Tunables.kt`
  `CLEAR_THRESHOLD = 0.80f`, overriding the core default at the app layer)
  and confirmed it to me via the journal (2026-07-21 00:19:45Z): the shipped
  APK runs 0.80, core's 0.90 stays the test reference. The doc reflects 0.80
  as a plain-language "provisional guess," with no raw percentage quoted to
  the player (the game itself has no percentage readout, so a number in the
  doc would be describing something the player can never see on screen).
- **No overflow warning glow exists yet.** `GameRenderer.kt`'s own comment
  says "Overflow itself needs no handling... the stack renders while its
  grace counts down." ADR 0005 designs a warning treatment on the spawn zone;
  it is not wired up. I called this out explicitly as an honest caveat rather
  than describing the ADR's intent as shipped behaviour — a player watching
  for the described warning and not seeing one is exactly the kind of
  confidently-wrong documentation the role exists to prevent.
- **Every piece is the same square shape, only the colour varies.**
  `PieceSequence.kt`'s own comment: "the archetype is a colour index only —
  every piece is the same square lattice." The doc says this plainly so a
  tester familiar with Tetris doesn't wonder where the other shapes are.
- **No score display, no title screen, no persistence.** Checked the actual
  `MainActivity`/`GameRenderer` diff in PR #19: game opens straight into
  Playing with no menu; the game-over overlay is a bare `TextView` ("Game
  over / Tap to play again") with no score shown; grepped for
  `SharedPreferences`/persistence — none exists. The doc does not claim a
  score or best-score feature.
- **Fixed haptic pulse on the Fairphone 6 is real**, not a guess dressed up as
  fact: `ImpactHaptics.kt`'s own doc comment records the client's Fairphone 6
  reading `Mode.NO_AMPLITUDE_CONTROL` on both Milestone-1 runs.
- **Back button pauses, does not exit** (`OnBackInvokedCallback` → `togglePause`
  in `MainActivity.kt`) and **volume keys are dev tools** (shading dial /
  benchmark, unrelated to gameplay) — both documented so a non-developer isn't
  alarmed by them mid-session.
- Confirmed no persisted data exists to caveat in the uninstall section.

## What I deliberately did not do

- Did not describe the *designed* Playing/Game-Over screens from
  `docs/ux/screens/*.md` (score readout, pause icon, best-score badge) — none
  of it is built yet, and documenting the target rather than the shipment is
  the exact failure mode this task exists to fix.
- Did not fix `docs/operations.md`'s stale "What the current build ships"
  section (still describes the Milestone-1 toy, lines 88+). That file is
  DevOps's, the fix is out of this task's scope, and I only touched the one
  broken cross-reference caused by my rename. Flagging below.
- Did not quote a raw clear-threshold percentage to the player — the game has
  no percentage readout, so a number in client copy would describe something
  they can never verify on screen. Described it as "how full a band needs to
  be" instead.
- Did not touch `README.md` or `CHANGELOG.md` — out of scope for this
  dispatch; `README.md` doesn't currently reference the install doc at all,
  and there is no `CHANGELOG.md` yet in the repo.

## Considered and rejected

- **Keeping the elaborate three-step benchmark-photo ritual** from the old
  doc (photograph three fill states, toggle shading, run the volume-down
  benchmark). Rejected: the client has asked repeatedly for brevity, this
  round is about feel-testing the real mechanic rather than performance
  triage, and the on-screen readout is still there and still real if they
  want to look at it. Kept one honest-caveat line about it instead of a
  protocol.
- **Quoting the exact 0.80 figure to the player.** Rejected for the reason
  above — no in-game percentage readout exists, so stating a raw number
  would look precise while being unverifiable by the reader.

## Open questions / flags for the team

- **`docs/operations.md`'s "What the current build ships" section is stale**
  (describes Milestone 1: "no piece sequence, no coverage bands, no clearing,
  no losing, no scoring"). That's now wrong on every count. Not my file to
  rewrite under this dispatch; flagging for DevOps/Product Lead.
- **The overflow grace window has no visible warning yet.** This is a real
  gap against ADR 0005's fairness/legibility argument, not just a docs
  omission — worth a backlog item if it isn't already tracked, since "does
  losing feel fair" is one of the four things the client is being asked to
  judge, and an invisible grace period undermines exactly that.
- The losing sequence (overflow → grace → game-over → restart) has never run
  on real hardware per the Frontend Engineer's own handoff 0026 addendum —
  the doc says so under caveats so a "so that's what that did" report from
  the client isn't mistaken for a bug.

## PR

#20, `[Tech Writer] docs: rewrite install doc for the real game`. Pushed,
watching CI (was pending at time of writing this handoff — see journal for
outcome). Not merging; that's the Product Lead's call per the constitution.
