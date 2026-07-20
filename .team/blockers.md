# Blockers

Anything stopping an agent. Raised by any role, resolved by the Product Lead or
taken to the client.

| Date | Raised by | Blocker | Status |
| ---- | --------- | ------- | ------ |
| 2026-07-20 | architect | ~~**On-device frame timing cannot be measured in this container.**~~ **CLOSED 2026-07-20: measured 12.06x**, against my 3–7x estimate — I was wrong, optimistically, on the project's tightest budget. Tiers re-derived in ADR 0009 Amendment 1 and superseded by ADR 0011. | **closed** |
| 2026-07-20 | architect | **GPU/fragment-shader cost is entirely unmeasured.** The spike measures CPU only. ADR 0009 concludes the largest remaining performance risk is now the procedural gel/subsurface fragment shader, not the solver — a reversal of the project's founding assumption. Nothing here can measure it. To close: profile the shader on the reference device once a renderer exists. Affects the UX Designer's look budget. | open |
| 2026-07-20 | architect | **The brief contradicts itself on the performance target.** The revised *Performance target* section (added 2026-07-20) correctly makes the Fairphone 6 the reference and demotes the 2020 floor to an aspiration — but **success criterion 1 still reads "Installs and runs on a mid-range Android 10 device, holding 60fps"**, which is the unverifiable claim that section exists to retire. Criterion 1 should be reworded to name the reference device. Client-facing, so the Product Lead's to make. | open — needs Product Lead |
| 2026-07-20 | architect | **Architect challenges the brief's stated quality-scaling mechanism.** The brief (*Performance target*, "Architect to confirm or challenge") proposes degrading "solver iterations, particle count or substep rate" at runtime. Measurement says two of those three are unsafe: below 8 substeps a settled stack visibly jitters (ADR 0003), and particle count cannot change mid-run without bodies popping. **ADR 0009 scales rendering at runtime and fixes simulation quality at startup instead.** The brief's wording should be updated to match, or the Product Lead should overrule me with a reason. | open — needs Product Lead |
