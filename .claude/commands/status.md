---
description: Where the project stands — gate, backlog, branches, blockers
---

Report the current state of the project to the client, in this order and no
longer than one screen:

1. **Gate** — which gate we are at, and whether we are waiting on the client.
   Read `/work/.team/gates.md`.
2. **Backlog** — done / in progress / next, from `/work/.team/backlog.md`.
   Name the items, do not give percentages.
3. **Branches** — `git branch -vv` and any active worktrees under
   `/work/.worktrees/`, with what each is for.
4. **Blockers** — open items from `/work/.team/blockers.md`.
5. **How to see it** — the exact command to run the product right now, the port,
   and what to look at.

If any of these files do not exist yet, say plainly that the project has not
started and offer to begin the interview.
