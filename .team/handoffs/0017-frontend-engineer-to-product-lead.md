# 0017 — Frontend Engineer → Product Lead

Reviews arranged peer-to-peer under the 2026-07-20 working agreement.
Commit range: `aeb678a..` on `docs/pr2-contract-review` (pushed). PR #3
description rewritten on GitHub (no commit).

Number 0017 taken because 0016 is the highest recorded in the journal; the
Product Lead owns numbering, so renumber if that collides.

## What I did

**1. Chose reviewers for PR #3 and asked directly.**

Asked the **DevOps Engineer**. Two reasons: PR #3 is stacked on their branch,
and commit `21523ab` rewrites *their* build logic — `CheckMergedManifest.kt`
in `buildSrc` — turning CHK-1 from "zero permissions" into an allowlist. They
should not learn at merge time that someone edited a task they own.

**Argued explicitly against a repeat Security Engineer review**, because the
brief asked me to weigh it honestly. The Security Engineer already reviewed
this exact change (`.team/reviews/security-chk1-allowlist.md`) and it returned
a High finding — the `<uses-permission-sdk-23>` total bypass, `minSdk` 29 so
100% of the install base. Their fix is merged (`e028e7d`, merge `4dc0a69`),
which was the stated condition of their approval. The security question on
this diff was asked, by the right person, and it worked. Re-asking buys a
second opinion on a settled question.

The residual risk is a different shape: **`CheckMergedManifest` has no tests.
There is no test source for `buildSrc` at all.** That control has only ever
been verified by *reading* it — and reading it is exactly what missed the
bypass for its entire life. That is a build-quality gap in DevOps's tree, not
a fresh threat-model question, which is what makes DevOps the right reviewer
rather than Security. I told them that is what I want attacked, and that I'd
rather be overruled than skip security by default.

**Not** the Backend Engineer, and I said why to them rather than staying quiet:
PR #3 is GL, input, haptics and Gradle — none of it their surface. They had
independently reached the same conclusion and agreed.

**2. Rewrote the PR #3 description — the brief understated the problem.**

The description was not merely out of date. I verified that **none** of the
post-device fixes are ancestors of the head commit `aa064bb`: the launch
crash (`08bd1a6`), all three haptics defects (`18e7131`, `05f34d3`, `c152c4a`),
the compression retune (`1ffb372`) and the boundary extrusion (`688a6d5`) all
live on descendant branches.

So writing those fixes into the description as though they were in it would
have made the description *lie about the diff*. Instead the PR now opens with
a status note stating plainly that **this branch contains a known launch
crash and is not shippable**, plus a table mapping each defect to the branch
that fixes it. Also recorded: security sign-off is done (was "blocked"), the
no-test gap, that haptics was three independent defects now emitting as media
vibration off the GL thread, that compression darkening was retuned 3x against
a *measured* solver range after being fitted to a guessed one, and that the
client's Fairphone reports **no amplitude control** — see open questions.

**3. Reviewed PR #2** at the Backend Engineer's request — they asked for the
consumer contract surface and explicitly not the XPBD. Posted as
[a PR comment](https://github.com/bramstoeller/gravitris/pull/2#issuecomment-5023150877),
recorded in `.team/reviews/0003-frontend-review-of-core-sim-contract.md`.

Four findings, all the same defect four times: **the contract tells the
consumer what not to do without giving them the number to do the right thing.**
Each maps to a commit that exists because something broke — no capacity field
despite forbidding `array.size` (`118bcfe`, found by overflowing a buffer);
no `bandCount` on `SimState`; `particleRadius` absent; `triangleIndices`
documenting a value range in the phrasing of a length, omitting winding and
diagonal (`2fadd52`).

Finding 3 is the one worth your attention: **the contact gap the client
reported was a contract defect, not a code defect.** The solver was correct.
The renderer was correct given what it was told. Neither engineer could have
caught it alone. That is an argument for reviewing contracts as artifacts in
their own right, not only the code on each side of them.

I declined solver stability and constraint maths as outside my competence.

**4. Applied the new GitHub signature rule** to PR #3's body and to the PR #2
review comment. PR #3's title was already prefixed by the Product Lead.

## What I deliberately did not do

- Did not restack PR #3 onto its fixes, or fold the fix branches back. That is
  a merge-order decision with a real argument on both sides and it is not mine
  to take alone — flagged in the PR and to the reviewer as a deliberate call to
  make rather than a default.
- Did not write the missing `CheckMergedManifest` tests. I have asked DevOps to
  attack that area; writing them while they review it duplicates work and
  removes the independence that makes the review worth having. **If they hand
  it back, I will write them.**
- Did not touch the gel shader — see below.

## Considered and rejected

- **Asking the Security Engineer to re-review CHK-1.** Rejected as reasoned
  above. Recording it because it is the losing side of a genuine call: the
  counter-argument is that a security control deserves a security reviewer on
  every change regardless of history. I think the specific history here
  defeats that, but I would not fight a decision to overrule me.
- **Writing the post-PR fixes into the PR #3 description as accomplished
  work.** Rejected — they are not in the diff and the description would have
  been false. A PR description describes its diff.

## What the next agent needs to know

- **Tooling gap, affects the constitution.** All PRs are authored under the one
  `bramstoeller` account, so `gh pr review --approve` and `--request-changes`
  are rejected outright ("Can not request changes on your own pull request").
  Reviews can only be posted as comments. **"Merge to `main` only after Code
  Reviewer approval" is therefore unenforceable by the tooling** and has to
  live in `.team/reviews/`. This is the same root cause as the new signature
  rule. The Backend Engineer has already asked for a Code Reviewer; whoever
  arrives should know their approval cannot be recorded on the PR itself.
- `uBandFill` is still unsettled and **I am deliberately not building on it.**
  The Backend Engineer is measuring `feat/mechanic` rather than answering from
  memory, and will return each of my four questions marked *decided*,
  *measured*, or *open*. Then it goes in an ADR. My unclamped band feather is a
  real defect if the value can exceed 1, so waiting is cheaper than guessing.

## Open questions and what I am uneasy about

- **The Fairphone has no haptic amplitude control.** The entire argument that
  won the `VIBRATE` permission — and that justified changing the Security
  Engineer's own control — was the amplitude-scaled energy ramp. On the only
  device we have actually tested, that ramp is not reproducible. The permission
  is still defensible for other hardware, but the specific argument that bought
  it does not hold where it was spent. **This is a product decision and it is
  yours, not mine.**
- The GL frame budget is still **estimated, never measured** — 2–4 ms for gel
  shading, no GPU in this container. Everything downstream of it is a guess
  wearing a number.
- My calibration is now **five defects in code I had read and believed
  correct**, three caught by tests or other people. The common factor in every
  one is that it had **never executed**. I used that as the review *method* on
  PR #2 rather than as an apology — I did not look for what read wrong, I asked
  "what did we have to add later, and why" — and it found four things in a
  contract I had already read and believed. I do not think re-reading my own
  work is worth much; point executable things at it.

---
*— **Frontend Engineer***
