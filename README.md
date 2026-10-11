# BlockBoost — Android Performance Engine

A Fabric mod for **Minecraft Java 26.1.2** whose primary target is **Android**:
[Zalith Launcher](https://github.com/ZalithLauncher/Zlapi) (or any launcher that can
load Fabric) with [MobileGlues v2.0.0](https://github.com/MobileGL-Dev/MobileGlues-release)
as the GL translator, usually on a Mali GPU. Desktop players get the same jar and the
same switches.

It is one jar, deliberately not a re-packaged bundle of other mods. Everything in it is
written for this repository; other projects are named in
[NOTICE.md](NOTICE.md) for ideas and for the 26.1 API shapes they confirmed, and one of
them is explicitly excluded as a source because its license forbids what would be needed
(see the table there).

**Download:** the release jar is attached to [Releases](../../releases) as
`blockboost-<version>-mc26.1.2.jar`, and the same file is committed at the repository
root so it is downloadable from the file list. Every jar is produced by
[the CI workflow](.github/workflows/build.yml) from the commit it names — the build job
compiles, runs unit tests, launches the client headlessly, verifies the jar's metadata,
and only then attaches it. Nothing here is a hand-assembled artifact.

## What it actually does

| Subsystem | Mechanism | Why it helps on a phone |
| --- | --- | --- |
| Entity visibility culling | Skips *render-state extraction* for entities occluded from the camera. Results are cached for a few ticks, computed on the client thread within a per-frame budget, or on worker threads against a coarse published occupancy grid. | A mob farm or village costs model lookups, matrix pushes, vertex writes and draw calls per entity per frame. Not building the state at all is the only version of "culling" that saves the CPU, which is the bottleneck under a translated GL stack. |
| Block entity culling | Same decision, keyed by block position, honouring vanilla's own `shouldRenderOffScreen()` so beacons and conduit beams are never hidden. | Chests, signs, spawners and hoppers are the most expensive per-unit draws on mobile: one state allocation plus a separate draw call each. |
| Frame pacing | Frame cap, vsync and an unfocused/minimized throttle, written into the game's own video options. | Uncapped menus cook a phone; a stable 55 fps feels smoother than 30–90 fps, and the cap is how you keep thermals from triggering a driver downclock. |
| Vanilla video overrides | Tri-state (`ignore` / `on` / `off`, or a number) writes to mipmaps, biome blend, entity distance, render/simulation distance, ambient occlusion, entity shadows — with your previous values remembered. | The supported channel. Nothing GL-ish is patched, so nothing breaks when 26.2 moves the renderer to Vulkan. |
| Particle ceiling | Picks vanilla's `Decreased`/`Minimal` particle mode from the configured per-frame budget and the measured device class, and refuses `Minimal` when "keep gameplay particles" is on. | The particle engine's internals are private and move every version; vanilla's own tiers skip emission at the source and stay correct about gameplay particles. |
| Chunk backlog guard | Sustained frame overrun shrinks render/simulation distance by one step and grows it back once frames recover, with a cooldown on both sides. | Meshing work is the second-biggest CPU cost after rendering; refusing to keep up is what turns a laggy moment into a laggy minute. |
| Bounded worker pools | Every background pool has a fixed thread ceiling (scaled to the cores the device reports, `big.LITTLE`-aware) and a bounded queue that drops the oldest job instead of growing. | An unbounded queue on 4 GB of RAM is an OOM crash with extra steps. |
| Memory hygiene | Fixed-capacity caches with eviction, thread-local scratch buffers under a byte ceiling, caches released on level exit, GC pauses read from the platform MXBeans. | Under a launcher the heap ceiling is often the real limit; knowing *which* collection cost you 240 ms is worth more than a "boost memory" button. |
| Adaptive control | `off` / `recommend` / `active`. Reads the rolling frame distribution, walks one step of a reversible ladder after hysteresis + cooldown, stands down immediately when you edit anything. | A phone throttles as it heats. A mod that only ever optimises harder makes that worse; one that can suggest, and be overruled, is usable. |
| Diagnostics | FPS, mean/median frame time, p95/p99, 1% lows, spike count, chunk/queue gauges, heap and GC pauses, active features with reasons, resolved renderer profile, and errors that forced something off. Text report and CSV into the game directory. | Without this, every "optimization mod" conversation is vibes. It is also the only reason the claims in this README stay honest. |
| UI | Touch-first settings screen built from vanilla widgets, 11 sections, 7 presets that preview their exact diff before applying, per-setting description/range/risk/restart flag, reset-with-confirmation, keybind, Mod Menu entry point, optional overlay. | Two thumbs, no mouse, and a screen where "FPS Cap" is a 24 px row, not a 9 px tooltip you cannot hover. |

## Requirements

* Minecraft **26.1.2** (this jar is built against 26.1.2 and declares `~26.1.2`; 26.1
  mods are not usable on other versions and pre-26.1 mods are not usable here either,
  because 26.1 changed the mapping/remapping model — see the notes in
  [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)).
* Fabric Loader **0.19.5 or newer**, **Java 25** (26.1's own requirement).
* **Fabric API is required** (`fabric-api` jar). The overlay layer, keybind registration
  and the client tick/screen events this mod uses are Fabric API, not mixins. If you
  install BlockBoost alone on a launcher, it will be reported as a missing dependency —
  that is the loader doing its job.
* No Sodium required, no Sodium bundled. It is *detected*, and where it already covers a
  subsystem BlockBoost switches that subsystem off with a reason you can read in the
  settings screen.

## Installing on Android (short version)

Full walkthrough with the pitfalls: [docs/INSTALL-ANDROID.md](docs/INSTALL-ANDROID.md).

1. Launcher → version **26.1.2**.
2. Install **Fabric Loader 0.19.5+** for that version.
3. Put `fabric-api-0.155.3+26.1.2.jar` and `blockboost-1.0.0-mc26.1.2.jar` in `mods/`.
4. Renderer: MobileGlues v2.0.0 (GL4ES) or the launcher's own choice. BlockBoost reads
   what it is given and never patches it.
5. Launch, press **O** (`BlockBoost settings` keybind, or Mod Menu → BlockBoost), pick a
   preset, read the diff, apply.

## What this mod does *not* claim

* **No benchmark numbers.** Not because the work is unmeasured, but because a number I
  invented would be worse than no number. [docs/BENCHMARKS.md](docs/BENCHMARKS.md)
  explains the harness in the jar (capped and uncapped runs are separate by design) and
  what to paste if you want to report one.
* **"Tested on a Mali device" is not a claim I can make.** CI compiles it, runs the unit
  tests, and launches the client headlessly under a virtual framebuffer — that proves
  mixins apply, the config round-trips, and the game boots with the mod loaded. It does
  not exercise Mali, GL4ES, LTW or Zink. Everything device-specific is marked *untested*
  in [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md), and the mod's defaults are
  conservative precisely because of that.
* No telemetry, no update checks, no networking of any kind. There is a test
  (`ModMetadataTest`) that fails the build if network APIs appear anywhere in the
  sources, because a promise in a README rots and an assertion does not.
* No server-side or worldgen "optimizations" smuggled into a client jar.

## Development

```bash
./gradlew build            # compile + unit tests + jar
./gradlew dumpMinecraftApi # javap the exact 26.1.2 API surface BlockBoost touches
```

The sandbox this project is developed in has no JDK-with-javac and no Maven access, so
**CI is the compiler**: `.github/workflows/build.yml` builds, tests, launches the client,
verifies the jar, and posts a condensed report — including the answers to
`tools/recon-ask.txt`, which is how every mixin target and option name here got checked
against the real jar instead of remembered from an older mapping set. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## License and tests

MIT — see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
Status badges reflect the latest run on `main`:

![build](https://github.com/Nazatric/BlockBoost-Droid/actions/workflows/build.yml/badge.svg)
