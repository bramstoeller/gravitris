# Visual-layer review screenshots (handoff 0035, PR #25)

Captured on the **software-rendered** correctness emulator (SwiftShader /
swangle). They prove **what draws** and show the **appearance direction** — they
are **not** the fidelity or performance of the client's OLED Fairphone 6. No
frame-time or perf claim attaches to any of these; on-device measurement via the
(now hidden) frame-time readout is the owed follow-up.

Downscaled to 540px wide from 1080×2400 captures.

| File | What it shows |
|------|---------------|
| `01-background-hud.png` | Empty well: the procedural environment (indigo gradient + soft glows) replacing the black void, and the HUD (score, `LV 1` chip, pause icon). |
| `02-stack-hues.png` | A short stack — magenta + emerald gel pieces against the environment, grain/subsurface visible. |
| `03-clear-juice-embers.png` | A band clearing: the **amber ember burst** fanning across the band, the clearing green band lit and dissolving. |
| `04-clear-juice-embers2.png` | Another clear — embers spraying across an azure + violet clear. |
| `05-game-over.png` | The designed game-over screen: dimmed topped-out stack (all seven hues) behind the scrim, `SCORE 0`, the amber PLAY AGAIN pill. |

Score reads `0` because scoring is backlog D8 — the HUD/game-over present the
real field, wired and ready, not a faked number.
