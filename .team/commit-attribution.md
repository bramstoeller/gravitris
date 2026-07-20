# Commit attribution — work owed

**Status: outstanding.** Client asked (2026-07-20) that `git log` show which role
did which work. From commit `25c23f8` onward every agent commits under its own
role identity, so this file only covers the commits made *before* that rule
existed.

## Why this file exists rather than agent memory

Specialist agents do not persist. They exist for the duration of one task and
are then gone — asking them to "remember which commits were theirs" would lose
the information rather than preserve it. So the mapping is derived and recorded
here, in the repository, while it is still recoverable from branch topology and
the handoff record.

## The plan, and why it has not been done yet

Rewriting these commits means `git filter-branch` / `git filter-repo` over
published history, followed by a force-push to `main`. Deferred deliberately:

1. **Agents were mid-flight.** Branches were actively being built on top of these
   commits; rewriting would have pulled the base out from under running work.
2. **`main` is published** and the team constitution forbids rewriting published
   history, with a guard hook enforcing it. Doing this needs the client to lift
   that guard consciously, not to have it routed around quietly.
3. **The information is not lost meanwhile.** Handoffs in `.team/handoffs/`,
   reviews in `.team/reviews/` and the journal record authorship in far more
   detail than an author field carries.

Do it at a quiet point when no agent is running.

## The mapping

Each commit is listed against the role that **first introduced** it. Branches
inherit their ancestors' commits, so branch membership alone over-attributes;
this list is deduplicated by point of introduction.

### Architect
| Commit | Subject |
| ------ | ------- |
| `0771610` | docs(architecture): record solver budget spike and gate 2 architecture |
| `25c6c7d` | docs(architecture): correct contracts against ux specs |
| `4390f7d` | docs(architecture): adopt gravitris working title |
| `17dfe9b` | docs(team): record branch contamination and merge order in handoff |

### Security Engineer
| Commit | Subject |
| ------ | ------- |
| `83ebade` | chore(security): ignore android signing material |
| `7c1a355` | docs(security): add threat model, dependency policy and signing guidance |
| `9098900` | docs(security): revise signing guidance for direct apk distribution |

### DevOps Engineer
| Commit | Subject |
| ------ | ------- |
| `4272f58` | chore(build): ignore android build artifacts and signing material |
| `8a6e365` | build: scaffold pinned two-module Gradle project |
| `334991b` | build: add task runner, SDK bootstrap script, and operations docs |
| `2d0384d` | ci: add minimal reproducible-build workflow |
| `6e4c53a` | docs(team): add devops handoff 0005 for stage 0 build foundation |

### Backend Engineer
| Commit | Subject |
| ------ | ------- |
| `9df0e4a` | feat(core-sim): add substepped xpbd soft-body solver |
| `80252be` | test(core-sim): cover stability, determinism and zero allocation |
| `c12c563` | docs(team): add backend handoff 0006 for stage 1 core-sim |

### Code Reviewer
| Commit | Subject |
| ------ | ------- |
| `23661e6` | docs(review): record code review of build foundation |

### Product Lead
Everything else on `main`: the brief, gates, backlog, blockers, the interview
record, journal updates, merges, and the `.gitignore` and constitution changes.

## Three that are genuinely ambiguous — do not attribute mechanically

- **`16fd999`** *(docs(ux): bring ux specs and coordination onto a clean branch)*
  — committed by the Product Lead, but the **content is the UX Designer's**. It
  exists because the Product Lead's `git add -A` in a shared working tree had
  polluted the original branch with a 4.6MB build artifact, and the work had to
  be carried across to a clean branch. Attribute the content to the UX Designer
  and the act to the Product Lead, or leave it with the Product Lead and note it.
  See `.team/handoffs/0001-` and `0002-ux-designer-to-product-lead.md`.

- **`0a2ec58`** *(chore(config): allow git merge, checkout and worktree)* — the
  **client** wrote these permission rules by hand, on their own disk, after the
  Product Lead was correctly blocked from granting itself the permission. Not
  the Product Lead's work in any meaningful sense.

- **Early UX and architecture commits swept into Product Lead commits.** Before
  worktree isolation was adopted, several `docs(brief)` commits on the shared
  checkout also carried other agents' in-progress files. Those commits are
  genuinely mixed and cannot be cleanly attributed to one role. See
  [`parallel-agents-need-worktrees`] — the Security Engineer, who used a
  worktree, is the only role whose commits were never contaminated.
