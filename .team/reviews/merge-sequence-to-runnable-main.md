# Merge sequence — landing a runnable `main`

Author: Code Reviewer
Date: 2026-07-20

## The problem

`main` holds only docs — a standing violation of "main always builds and runs."
Five PRs are open. `feat/app-shell` (PR #3) contains a known launch crash, fixed
only on a descendant branch, so merging PRs #1→#5 in order would put a
non-starting app on trunk. The goal is a runnable `main` in the fewest merges,
without rewriting already-pushed history.

## Established facts (verified, not assumed)

- `main` forked from an early point; the code branches all carry an **older**
  copy of the build scaffold. Merging any of them into today's `main` conflicts
  on **docs only**.
- `feat/mechanic` is the integrated tip: `feat/app-shell`, the launch-crash fix
  (`fix/squish-toy-launch-crash`), and every fix branch except `feat/gel-shading`
  are already its ancestors. `core-sim`'s content is in it via app-shell.
- **`feat/mechanic` forked from `build-foundation` at `d47e706` — BEFORE** the
  commits that made the foundation pass cold CI (the kotlinx-coroutines
  verification-metadata fix, commit `3880a94` + the regenerated
  `verification-metadata.xml`). Its scaffold diverges from the green foundation:
  old CI workflow, absent buildSrc guard tests, ~1400 lines of different
  verification metadata.
- **`feat/mechanic` CI is RED right now.** Its own developer's warm-cache
  `./gradlew check` passes (49 core-sim + 114 app tests), but the cold CI oracle
  fails at:
  `Dependency verification failed for configuration 'classpath' … kotlinx-coroutines-bom-1.6.4.pom`
  (run 29765461048). This is precisely the nondeterminism `build-foundation`
  already fixed. Warm-vs-cold is the whole point — mechanic is **not landable as
  it stands**.

## Recommended sequence

**Step 1 — merge PR #1 (`chore/build-foundation`) → `main`.** Foundation first;
everything needs it on trunk. Docs-only `.gitignore` conflict, resolved as a
union (see `review-build-foundation.md`). **Merge result verified GREEN in CI**
(run 29765966695).

**Step 2 — merge PR #4 (`chore/emulator`) → `main`** (after #1). Clean merge,
correctness-only tooling off the required path. **Merge result verified GREEN in
CI** (run 29766000884).

**Step 3 — do NOT merge PR #2 or PR #3 individually.** PR #3 verbatim crashes on
launch (broken intermediate trunk), and both are superseded — their content is
already inside `feat/mechanic`. Land them *through* the tip, not in sequence.
Close #2/#3 as superseded-by-#5 (keep as review artifacts), or leave open and
let the mechanic merge subsume them.

**Step 4 — reconcile the foundation into the game BEFORE landing it.** The
Backend Engineer (or whoever lands #5) should merge today's `main` (with the
green foundation from Step 1) **into** `feat/mechanic`, resolve the scaffold
divergence in favour of the green foundation (the fixed `verification-metadata.xml`,
the current CI workflow, the buildSrc guard tests), and get **`feat/mechanic`
itself green in the cold CI oracle** — a warm `./gradlew check` is not the test.
Only then merge `feat/mechanic` → `main`. Because the launch-crash fix and all
other fixes are already ancestors of mechanic, this single merge lands a
runnable game with **no broken intermediate `main`**.

  Specific conflict to expect and how to resolve it: mechanic's
  `ci: pass GITHUB_TOKEN to the gitleaks secret scan` (commit `d786e6a`)
  **duplicates** a fix already on the base branch — `build-foundation`'s
  `ci.yml` already passes `GITHUB_TOKEN` to gitleaks (verified, lines 43–52).
  Take the base-branch (DevOps) `ci.yml` **wholesale** and drop mechanic's
  duplicate; the DevOps version is authoritative and also carries the
  cold-cache dependency-verification fix that mechanic's version does not. The
  `kotlinx-coroutines-bom-1.6.4` verification failure is fixed by taking the
  foundation's regenerated `verification-metadata.xml`, not by the
  `GITHUB_TOKEN` change.

**Step 5 — gel-shading + the emulator gate (Frontend Engineer / DevOps).**
`feat/gel-shading` is the only feature branch not in mechanic; it sits on top and
carries the renderer band-value integration (the `GameRenderer.kt:450/452`
swap to `state.bandFill` / `state.bandClearProgress`). That integration and its
`make screenshot` verification are the **Frontend Engineer's**, and are moot
until Step 4 makes mechanic green. Run the integrated APK through the emulator
before it goes near the client.

## Net

Two merges land a green, buildable foundation on `main` now (Steps 1–2, both
verified). The runnable game lands in one further merge (Step 4), once mechanic
inherits the foundation's cold-CI fix — which is the real blocker today, not the
launch crash (already fixed) and not the docs conflicts (trivial).

— **Code Reviewer**
