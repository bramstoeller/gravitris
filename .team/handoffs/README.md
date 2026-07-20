# Handoff numbering

Numbers collided three times because agents work in parallel worktrees and each
picks the next free number it can see on *its own* branch. Two agents branching
from the same point both see the same "next" number.

**The Product Lead owns this sequence.** Do not pick your own number.

Ask for one in your dispatch, or use the placeholder `NNNN-<role>-to-<to>.md`
and the Product Lead will renumber on merge. A duplicate number is not a
correctness problem — the content is what matters, and renumbering is cheap —
but it makes the log harder to read, and the log is the team's memory.

## Assigned so far

| # | Author | Subject |
| - | ------ | ------- |
| 0001 | UX Designer | initial spec set |
| 0002 | UX Designer | revision: device profile, pace deferral |
| 0003 | Architect | gate 2 architecture and solver budget spike |
| 0004 | Security Engineer | threat model, signing, dependency policy |
| 0005 | DevOps Engineer | stage 0 build foundation |
| 0006 | Backend Engineer | stage 1a `:core-sim` |
| 0007 | Frontend Engineer | stage 1b `:app` shell |
| 0008 | Architect | QA guidance, substep reconciliation |
| 0009 | Frontend Engineer | milestone 1 squish toy, haptics addendum |
| 0010 | Backend Engineer | material softness (compliance 1e-6 → 1e-4) |
| 0011 | Backend Engineer | contact gap diagnosis (rendering, not physics) |
| 0012 | Architect | piece-size leak, restitution |
| 0013 | Architect | never dilate wall-clock time |
| 0014 | Frontend Engineer | silhouette extrusion *(was a duplicate 0012)* |
| 0015 | Backend Engineer | interlocked bodies *(in progress)* |
