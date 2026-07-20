# Gravitris

An Android falling-block game where pieces are heavy, deformable soft bodies
that squash and settle under real physics. Pure Kotlin, a custom XPBD solver,
OpenGL ES 3.0, no NDK, no game engine, no third-party SDKs, no network.
Distributed as a signed, sideloaded APK — see `docs/architecture.md` and
`docs/build-order.md` for the full picture.

**Status: Stage 0 (foundation).** There is no game yet — see
[What Stage 0 actually ships](docs/operations.md#what-stage-0-actually-ships).
This repository currently builds a two-module scaffold (`:core-sim`,
`:app`) with the checks and toolchain the rest of the project depends on.

## Quick start

```sh
make setup   # once per machine — installs the pinned Android SDK
make test    # runs everything: JVM tests + the two build-time checks + lint
make dev     # builds the debug APK; installs+launches it if a phone is
             # attached over adb, otherwise tells you where the APK is
```

That's it — one command to set up, one to run, one to test. `make help`
lists every other target. Full detail, including what each check does and
how to sideload the APK onto a phone, is in
**[`docs/operations.md`](docs/operations.md)**.

## Layout

```
:core-sim   pure Kotlin/JVM — no Android dependency, ever (ADR 0008).
            physics/ and game/ packages live here. Testable with plain
            JVM JUnit, no device or emulator required.
:app        the Android shell. Depends on :core-sim. GL renderer, input,
            haptics, settings — none of which exists yet at Stage 0.
```

## Documentation map

| Doc | What's in it |
| --- | ------------- |
| `docs/architecture.md` | System shape, module boundaries, the numbers the design rests on |
| `docs/build-order.md` | Why the work is sequenced the way it is |
| `docs/adr/` | Every expensive-to-reverse decision, with alternatives considered |
| `docs/contracts.md` | The exact interface between `:core-sim` and `:app` |
| `docs/operations.md` | How to run it, configure it, what breaks and where to look |
| `.env.example` | The one environment variable this build cares about |
| `.team/handoffs/` | What each stage of work actually did, and what it deliberately didn't |

## License

Not yet decided — this is client work in progress, not a public release.
