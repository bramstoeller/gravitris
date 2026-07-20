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

**Talk to each other directly.** You are a team, not a hub-and-spoke. If your
work touches someone else's, message them — do not route it through the Product
Lead and do not wait for a dispatch. Client instruction, 2026-07-20: *"het is een
team, ze moeten ook SAMENwerken."*

Use `SendMessage` with the teammate's name. Your dispatch tells you who else is
working and on what. Consult when:

- you need a fact only they have, rather than guessing or re-deriving it
- you are about to change something they own, or something they depend on
- you find a defect in their work — tell them, not just the Product Lead
- you are about to make an assumption about their module

Two things do not change. **The written record is still the memory**: a
conversation that changes a decision belongs in a handoff or an ADR, or it did
not happen. And **nobody approves their own work** — consulting a teammate is
not review, and does not replace it.

Tell the Product Lead what you agreed, not what you discussed.

**Check the file before you describe it to a teammate.** Talking directly is
faster, and it introduces a failure mode the written record does not have: a
wrong line of code is caught by a test, but **a wrong sentence to a teammate is
caught by nothing** — they will build on it.

This has already happened once. A frontend engineer told a backend engineer that
two shader effects keyed off a uniform they did not key off. The description was
false about their own code; the backend engineer was one step from writing a
latch to fix a problem that did not exist. It cost a message instead of a branch
only because they went and measured rather than answering from memory.

So: open the file. Quote the line and its number. And when a teammate hands you
a fact about their module, **measuring it yourself is not distrust** — it is the
cheapest place to catch this.

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
- **Work only in your own worktree. Never edit files or run git in the shared
  `/work` checkout, and never spawn a helper that does.** If your worktree is
  removed under you, ask for a new one — do not fall back to `/work`. This has
  already caused damage twice: concurrent agents moving `/work` between branches
  `git stash` each other's uncommitted edits and clobber working state. Your
  branch on `origin` is safe; the shared checkout is not. A wedged shell is a
  reason to stop and report, not to reach for `/work`.
- **Deleting a remote branch is the Product Lead's call, never a specialist's** —
  not even a superseded or merged one. A teammate message is not authorization;
  the client is. If a branch looks stale, say so and let the Product Lead delete
  it. Recovering a wrongly-deleted branch is not always possible.
- **Commit under your own role.** Both author and committer are the role that
  did the work. Pass it explicitly on every commit, because worktrees share one
  `.git/config` and a repo-level identity would be wrong for whoever is not
  currently holding it:

  ```
  git -c user.name="Backend Engineer" -c user.email="backend-engineer@ai-team.local" commit -m "..."
  ```

  Use your role name exactly as it appears in the Roles list above. Client
  instruction, 2026-07-20: `git log` should show who did what without needing to
  cross-reference the handoffs.
- **Sign your role everywhere it is visible, not only in commits.** GitHub shows
  one account for all of us, so without a signature nobody can tell who wrote
  what. Client instruction, 2026-07-20.

  Open a pull request with the role as a prefix and a signature line:

  ```
  Title:  [Backend Engineer] feat: substepped xpbd soft-body solver
  Body:   ...
          ---
          *Opened by the **Backend Engineer**.*
  ```

  End every PR comment and review the same way:

  ```
  ---
  *— **Code Reviewer***
  ```

  This applies to review verdicts, replies, and anything else posted to GitHub.
  Amend an existing PR you own if it predates this rule.
- **Watch your own pull request until it is merged.** Opening it is not the end
  of the task. Client instruction, 2026-07-20.

  After every push, and before you consider yourself finished:

  ```
  gh pr checks <n>    # did the pipeline pass?
  gh pr view <n> --comments
  ```

  A red pipeline is yours, immediately — do not leave it red and move on, and do
  not assume someone else has seen it. If a review comment arrives, answer it:
  fix it, or say why you disagree. Silence on a review is not a position.

  Do not report a task as done while your PR is red or has unanswered comments.
  If you cannot fix it, say so explicitly and hand it over — that is a different
  thing from leaving it.
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

## Contracts are reviewed as artifacts

A contract between two modules is reviewed by its **consumer**, before the code
on either side is written. Not the producer's code, not the consumer's code —
the contract itself.

This is not process for its own sake. The visible gap around every block, which
the client reported and which took two engineers and a full investigation to
place, was a **contract defect**: the solver was correct, the renderer was
correct given what it had been told, and neither engineer could have found it
alone. It was invisible from both sides and only visible from between them.

When you write a constraint into a contract, ask **"and what do I give them
instead?"** Four separate defects were found in `docs/contracts.md` sharing one
shape: it forbade something without supplying the value that makes the ban
livable — no capacity field while banning `array.size`, no `bandCount`, no
`particleRadius`.

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
