# Gates

The four points where the team stops and asks the client. Every request and
every decision is recorded here, so a restarted container knows where it stands.

1. Brief approved
2. Architecture & plan approved
3. Milestone demo
4. Release approved

## History

| Date | Gate | Event | Note |
| ---- | ---- | ----- | ---- |
| 2026-07-20 | — | project created | awaiting the client interview |
| 2026-07-20 | 1 | interview held | soft-body physics, coverage-band clearing, endless mode, native Kotlin, Android-only |
| 2026-07-20 | 1 | **requested** | brief written to `.team/brief.md` |
| 2026-07-20 | 1 | **APPROVED** | client: "I agree, keep it simple for the first iteration" — simplicity is a standing constraint on iteration 1 |

| 2026-07-20 | 2 | **requested** | architecture (10 ADRs), UX spec set, threat model, staffing, 12-item backlog |

| 2026-07-20 | 2 | **APPROVED** | client approved architecture, six-person staffing and the twelve-item backlog ("ja") |

| 2026-07-20 | 3 | **requested** | milestone 1 "Squish Toy" APK built, 227 tests green, install doc written |

| 2026-07-20 | 3 | **APPROVED** | client ran it on their Fairphone: softness "ja, beter", contact "ziet er goed uit nu, blokjes raken elkaar". Told the team to carry on without further gates until release. |

**Currently waiting on:** nothing. Building Stage 3 — the mechanic and the art.
Next gate is 4, release.

**Standing:** the client does not want to be asked to approve each step. Report
outcomes, ask only for product decisions and device test results.

**Note:** conversation with the client switched to **Dutch** at this point.
Repository artifacts stay in English.

**Standing client constraints:**
- Keep iteration one simple. Prefer the simple solution, defer optional complexity.
- Store-ready is the release target; a playable prototype is milestone one.
