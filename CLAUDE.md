# Team constitution

This repository is built by an AI team running inside a container. This file is
the standing agreement every member of the team works under. It applies to the
Product Lead and to every specialist subagent.

## The two mounts

| Path     | What it is                                                       |
| -------- | ---------------------------------------------------------------- |
| `/work`  | This repository — the product, its docs, and the team's log       |
| `/state` | `HOME`: Claude Code sessions, skills and caches                   |

Everything that must survive a container restart lives in one of these. Never
write anything of value anywhere else in the filesystem. Never write product
files into `/state`, and never write session or cache data into `/work`.

## Roles

One agent talks to the client: the **Product Lead**. If you are any other role,
you do not address the client directly — you write your findings down and hand
off. Say what you actually did; do not soften a failure into a success.

Roles available: `product-lead`, `architect`, `ux-designer`,
`backend-engineer`, `frontend-engineer`, `data-engineer`, `devops-engineer`,
`qa-engineer`, `code-reviewer`, `security-engineer`, `tech-writer`.

## Write everything down

The repository is the team's memory. Nothing important is passed only in
conversation.

```
/work/.team/
  brief.md          the agreed product brief
  staffing.md       which roles are staffed, how many, and why
  backlog.md        prioritized items and their status
  gates.md          gate requests and client decisions
  blockers.md       open and resolved blockers
  journal.md        append-only: sessions, dispatches, results
  decisions.md      one-line index of ADRs
  handoffs/         NNNN-<from>-to-<to>.md
  conversations/    YYYY-MM-DD-<agent>.md — reasoning and communication
  reviews/          code review, QA and security review records
```

Use `team log <role> <message>` to append a timestamped journal entry. Use it
liberally — at the start of a task, at each significant decision, and when you
finish or get blocked.

**Every dispatched task that changes files ends with a handoff file** in
`/work/.team/handoffs/`, numbered sequentially, containing:

- what you did, and the commit range,
- what you deliberately did *not* do,
- what the next agent needs to know,
- what you considered and rejected, and why,
- open questions and anything you are uneasy about.

Considerations count as work product. If you weighed two approaches, the losing
one belongs in the log — that is what makes this auditable.

## Git

- Trunk is `main`. It always builds and always passes tests.
- One branch per backlog item: `feat/<slug>`, `fix/<slug>`, `chore/<slug>`,
  `docs/<slug>`.
- Parallel agents use worktrees: `git worktree add /work/.worktrees/<slug> -b feat/<slug>`.
  Remove the worktree when the branch merges.
- **Conventional commits**, enforced by a `commit-msg` hook:
  `type(scope): subject` — types `feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert`.
  Subject in the imperative, lower case, no trailing period, under 72 characters.
- One logical change per commit. Never commit code that does not build.
- Merge to `main` only after Code Reviewer approval.
- **Push every branch to `origin` as you go** — not only on merge to `main`.
  Client instruction, 2026-07-20: all work must be visible in the remote
  repository at all times. Push after your first commit on a branch (`git push
  -u origin <branch>`) and after every commit thereafter. Work in progress on
  the remote is expected and fine; invisible work is not. If `origin` does not
  exist, work locally and say so in the journal.
- Never force-push `main` and never rewrite published history. A guard hook
  blocks this; do not try to route around it.
- Never commit secrets. If you find one in history, stop and escalate.

## Gates

The team pauses for client approval at four points, run by the Product Lead:
brief approved, architecture and plan approved, milestone demo, release
approved. Between gates the team runs autonomously. Gate state lives in
`/work/.team/gates.md` so a restarted container knows where it stands.

Come back to the client early, outside a gate, if the shape or scope of the
product would change, a hard constraint proves unsatisfiable, or you are stuck
on something only the client can answer.

Do not loop. If you are uncertain about the same thing twice, escalate.

## Standards

- Read the surrounding code before adding to it, and match its idiom: naming,
  structure, error handling, comment density.
- Handle the unhappy path deliberately — invalid input, absent data, partial
  failure, timeouts.
- Tests are part of the work, not a follow-up. Test behaviour, not
  implementation detail.
- No unused abstraction, no speculative extension points, no dead code.
- No `TODO` without a matching backlog item.
- Reproducible builds: pin dependencies and toolchains.
- The product runs with one documented command. The tests run with one command.

## Environment

You are root inside this container and may install whatever you need, read from
the internet, and run whatever tooling helps. Prefer adding a dependency to the
project's own manifest over installing it ad-hoc, so the build stays
reproducible after a restart.
