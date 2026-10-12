# BlockBoost — Android performance mod for Minecraft Java 26.1.2

A Fabric mod built first for **Android**: [Zalith Launcher](https://github.com/ZalithLauncher/Zlapi)
or any launcher that loads Fabric, with [MobileGlues v2.0.0](https://github.com/MobileGL-Dev/MobileGlues-release)
(GL4ES) or another translator as the renderer, usually on a Mali GPU. Desktop players get the same
jar and the same switches.

It is one modular jar. It is **not** a repackaged bundle of other mods: everything in it was written
for this repository, other projects are named in [NOTICE.md](NOTICE.md) for the ideas and the 26.1
API shapes they confirmed, and one of them is explicitly excluded as a source because its license
forbids what merging would require.

The design rule for this project is boring on purpose: **never be slower than vanilla in comparable
scenarios.** Every subsystem here has a per-frame cost, and that cost is budgeted, bounded and
reported in the same place as its benefit. Where a mechanism could not be shown to pay for itself,
it was deleted rather than defaulted off.

---

## Install

| | |
| --- | --- |
| Minecraft | **26.1.2** (the jar declares `~26.1.2`; 26.1 changed the remapping model, so pre-26.1 mods are unusable here in either direction) |
| Java | **25** (Minecraft 26.1's own requirement) |
| Fabric Loader | **0.19.5** or newer |
| Fabric API | **required** — `fabric-api-0.155.3+26.1.2.jar`. The overlay, keybind category and client tick/screen events are Fabric API, not mixins, so installing BlockBoost alone is reported by the loader as a missing dependency. That is correct. |
| Sodium | **not required, not bundled.** If it is present its subsystems are detected and switched off with a reason you can read on screen |
| Mod Menu | optional; provides the "Settings" button and is registered in `fabric.mod.json` |
| Config file | `<game dir>/config/blockboost.json` |
| Downloads | release asset `blockboost-<version>-mc26.1.2.jar` on [Releases](../../releases). Every asset is produced by [the CI workflow](.github/workflows/build.yml) from the commit it names: compile, unit tests, a headless client game test, then jar-metadata verification — and only then upload. Nothing is assembled by hand. The jar is deliberately *not* committed into the repository: a binary on the branch moves it under whoever is working on it. |

1. Launcher → version **26.1.2**, install Fabric Loader.
2. Put `fabric-api-…+26.1.2.jar` and `blockboost-1.0.0-mc26.1.2.jar` in `mods/`.
3. Renderer: MobileGlues v2.0.0, or the launcher's own choice. BlockBoost never assumes which one you used — it asks the driver at runtime and tells you what it found.
4. Start the game. **It is already optimized**: the automatic profile runs on the first frame and needs nothing from you. Press `O` for settings, `K` for the overlay (both rebindable under *Controls → BlockBoost*).

Full walkthrough with the pitfalls: [docs/INSTALL-ANDROID.md](docs/INSTALL-ANDROID.md).

---

## The settings screen

Open it with `O`, from Mod Menu, or from *Options → …* if you installed Mod Menu. Six sections, in
the order you are likely to need them:

| Section | Question it answers |
| --- | --- |
| **Performance overview** | What is running, what is *not* running and why, what was detected, what it costs, what the game is doing to itself right now |
| **Smart optimization** | What the automatic profile chose for this device, what it refused to touch, and the presets |
| **Rendering & visuals** | Frame submission, culling, particles |
| **Chunks & world** | Background chunk work and the CPU that serves it |
| **Memory & stability** | Caches, allocation pressure, the switches that make the mod back off |
| **Advanced** | Diagnostics, sampling, the adaptive controller, and every knob that only matters while investigating something |

What each setting shows, always, without hovering (a finger cannot hover a tooltip): a human name,
its current value, a one-line explanation in ordinary words, and a permanent note about whether a
restart is needed. The detail dialog adds the full documentation text and the *side effects* — what
else moves when you move this. Numeric rows draw their position inside their allowed range, so "am I
at the edge?" is answerable at a glance. There are no raw implementation names in the row text, no
rows that clip, no 3000 px page of scrolling, and nothing here is a switch that does nothing.

Two whole-screen actions instead of a wall of toggles:

* **Presets** live in their own row. Tapping one opens the exact diff first — "N of M keys differ"
  with the old and new value of each — and applying changes only those keys. Six of them: maximum
  FPS, balanced, smooth gameplay, battery/cooler, shaders, and *compatibility safe*, which is the
  one to use when you want to know whether BlockBoost is involved in a problem at all.
* **Reset** is in every section, with a confirmation that lists what it will set back to default.
  Deleting `config/blockboost.json` does the same thing for the whole file, and a config that
  cannot be parsed is recovered rather than fatal: the bad file is kept next to a working one and
  the reason is on screen.

The screen allocates nothing per frame: every widget is built once and only relabelled, all text is
prepared during layout, and every control is a vanilla `Button`/`EditBox`. That is a deliberate
constraint, not laziness — 26.1.2's dump shows there is no `net.minecraft.client.gui.layout` package
at all, so any layout container would be a guess about an API that is not there.

---

## What it actually does

| Subsystem | Mechanism | Honest cost |
| --- | --- | --- |
| **Entity visibility culling** | Skips *render-state extraction* for entities behind terrain, honouring vanilla's own rules for what must never be hidden. Frustum-only mode, on-thread raycasts with a per-frame probe budget **and** a millisecond budget (`culling.entity_budget_ms`), or an asynchronous mode that resolves against a coarse occupancy grid published by the client thread — with results reused for a few ticks and jobs capped at 48 submissions/frame over fixed reusable slots, plus a 2 s reclaim for slots that never completed. | A decision costs ~1 µs on a mid device in the micro-benchmark the game test runs; each hidden entity saves model lookups, matrix work and draw calls. When a result cannot be produced in time the answer is *draw it*, never *skip it*. |
| **Block entity culling** | Same decision keyed by block position; `shouldRenderOffScreen()` is respected, so beacons and conduit beams are never hidden. | One AABB + ray set per block entity per refresh window, cached. |
| **Frame cap and vsync** | The cap is written into the game's own limiter (`FramerateLimitTracker.setFramerateLimit`), which is what its render loop parks against. Requesting 0 hands back the value the game had before BlockBoost first touched it. `render.vsync` writes the vanilla option and never a GL call. | Zero cost when unused. **There is no second limiter here any more**: an earlier version slept the render thread at the end of a frame, and that made late frames, so it was removed along with its two settings (`render.soft_pacing`, `render.smooth_pacing`). A cap you did not ask for is never set automatically, and no automatic path may write vsync — there is a unit test that walks the whole heuristic grid to enforce that. |
| **Unfocused / minimized throttle** | While the window is hidden or no world is open, the cap drops to the idle value and render distance and particles ease off; everything is put back on the transition, not per frame. | The largest measured win on a phone that is not in a world at all — a menu at 300 fps is a phone at 100 % GPU load for nothing. |
| **Vanilla video overrides** | Opt-in (`render.video_overrides`) tri-state writes to render/simulation distance, biome blend, entity distance, mipmaps, ambient occlusion, entity shadows and particle tier — with your previous values remembered and restored. | Nothing GL-ish is patched, so 26.2 moving the renderer to Vulkan costs nothing here. |
| **Particle ceiling** | Picks vanilla's `Decreased`/`Minimal` tiers from the configured ceiling and the device class, and refuses `Minimal` while "keep gameplay particles" is on. | The particle engine's internals are private and move every version; vanilla's tiers skip emission at the source and stay correct. |
| **Chunk backlog guard** | Sustained overrun shrinks render/simulation distance one step and grows it back after recovery, with a cooldown both sides and a hard stop if you edit anything. | Turning a laggy moment into a laggy minute is what an unbounded meshing backlog does; this is the cheapest way to refuse it. |
| **Bounded worker pools** | Every pool has a thread ceiling scaled to the cores the JVM reports and a bounded queue that drops the *oldest* job, counting the drop so it is visible on screen. | An unbounded queue on a 4 GB phone is an OOM crash with extra steps. |
| **Memory hygiene** | Fixed-capacity caches with eviction, thread-local scratch under a byte ceiling, caches released on level exit, GC pauses read from platform MXBeans. | Knowing *which* collection cost 240 ms is worth more than a "boost memory" button, and there is no such button here. |
| **Adaptive control** | `off` / `recommend` / `active`. Reads the rolling frame distribution, steps one reversible rung after hysteresis and cooldown, stands down the moment you touch a setting. It never measures on the render thread. | The default from the automatic profile is **recommend**: a phone throttles as it heats, and a mod that only ever optimises harder makes that worse. |
| **Diagnostics** | FPS, mean/median/p95/p99 frame time, 1 % lows, spike count, chunk and queue gauges, heap, GC pauses, active features with reasons, resolved renderer profile, and the errors that forced something off. Text report and CSV into the game directory. | This is the only reason the claims in this file stay honest, and it is what to paste with a bug report. |

## Device detection and the automatic profile

Detection happens **once**, on the first frame (the GL strings need a current context), and is cached
for the session. It is never re-read per frame, and nothing is reported as active unless it
initialized. What is read: OS/arch from the JVM, cores, heap ceiling, JVM flags, Android environment
hints, the GL vendor/renderer/extensions, and the window's display mode. What is *not* readable from
a Fabric mod, and is therefore never printed as a number: SoC model, thermal state, GPU clock, driver
version, per-cluster core frequencies. No device or Mali capability list is hardcoded anywhere.

`Automatic` (the default) classifies the device from what it *can* see and writes a starting config:

| Class | Chosen when | What it does |
| --- | --- | --- |
| `LOW` | ≤ 6 cores **and** < 2 GB heap | Frustum-only entity culling, occlusion off, 1 worker, no async, particles capped at 192 with gameplay particles kept, idle pause/throttle on, adaptive = recommend |
| `MID` | everything else that reports real numbers | 2 workers, async culling only at ≥ 8 cores, particles ≤ 384 |
| `HIGH` | ≥ 8 cores **and** ≥ 5 GB **and** not a mobile arch | up to `min(4, cores/3)` workers, async culling on, particles ≤ 768 |
| `UNKNOWN` | cores or heap unreadable | 1 worker, no async, particles ≤ 256 — conservative, and it says so |

The profile is applied **once**, only on a fresh install, and only while `android.device_class` is
`auto`. If you set a class by hand, yours wins and the diagnostics note that. A GPU name never buys
aggressiveness on its own: a flagship Adreno in a 2 GB heap is still `LOW`, and there is a test for
exactly that. Applying the profile never enables a frame cap, never forces vsync, and never raises
your render distance — it can only *reduce* what costs you frames, and every line it wrote is listed
in the log and on the Smart optimization tab.

Visual quality is not traded without permission: there is no forced "minimum graphics", no floor on
render distance, no automatic particle change beyond the ceilings above, and the shader preset
deliberately stops touching anything visual.

## Measurement — what exists and what does not

Real numbers on real Android hardware: **not measured, and not claimed.** This repository has no
phone, and a software framebuffer in CI is not a proxy for a thermal-limited SoC. Anything on this
point would be fabricated, so instead:

* **What CI does measure**, on every push, inside a live client. `runClientGameTest` runs the mod's
  own assertions on a real, running game: the hot path — 2000 visibility decisions inside one frame
  budget, asserted under 8 ms, with the per-decision microseconds and the occlusion probe state
  logged; a walk of every section of the settings screen requiring that rows actually get laid out;
  every feature initializing without throwing; and a config round-trip through a real game
  directory. Separately, `runProductionClientGameTest` boots the **shipped jar** in a real client,
  which proves the remapped artifact launches and initializes but runs none of those assertions
  (the test code is not in the release jar). Both run on a software framebuffer under Xvfb: that is
  a regression test, not a benchmark, and the summary it writes says so in its own header.
* **Unit tests** pin the behaviour that is easy to get wrong silently: the frame cap and vsync can
  never appear in an automatic patch, values the advisor writes are always schema-valid, the pools
  drop instead of growing, a throwing task cannot kill a worker, the occupancy cache stays bounded.
* **On a device**, the honest protocol is in [docs/BENCHMARKS.md](docs/BENCHMARKS.md): same world,
  same render distance, same seed, airplane mode, screen brightness fixed, ≥ 3 runs per configuration,
  report **median frame time and 1 % lows**, not the peak on the FPS overlay — a mod can win the
  counter and lose the feel. Compare vanilla / Fabric-only / Fabric + BlockBoost / one subsystem
  group at a time, using the presets' *compatibility safe* as the "is this mod involved" control.
  The overlay and the CSV reports exist so those numbers are yours to read and paste.

A claim this project *can* make is the negative one: after the audit that preceded the current code,
the mechanisms that cost frames were removed rather than tuned — unbounded on-thread occlusion
(about 220 000 level reads per frame, allocating per probe), an async path that returned "visible"
unconditionally while still allocating, four map lookups and three wall-clock calls per decision, a
512-entry rescan of the occupancy grid, a frame cap that silently enabled vsync, and a limiter that
slept at the end of the frame. The replacements are the budgeted, cached, allocation-free paths in
the table above.

## Compatibility

The rule: optional integrations are guarded, never assumed. A mod BlockBoost does not find is not an
error; a mod whose internals moved is disabled with a reason on screen instead of a crash. Full
detail in [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

| Mod | Status | How it was established |
| --- | --- | --- |
| Fabric API `0.155.3+26.1.2` | **required** — verified running together in the CI client game test | CI |
| Fabric Loader 0.19.5, Mixin/MixinExtras 0.17.4/0.5.5 | verified running | CI |
| Java 25, Minecraft 26.1.2 | verified building and launching | CI |
| Mod Menu `21.0.0` (26.1 line) | **expected** — compiled against, entry point declared, config screen handed back a parent screen; never *run* with it in CI | compile-only |
| Sodium (26.1.2 release exists) | **expected** — detected; overlapping subsystems (its occlusion/frustum work, chunk culling) stand down with a reason. No mixing into its renderer, no duplicated work: the occlusion approach here is vanilla's own `VisGraph`/raycast on the client thread, not Sodium's pipeline | code reading of its 26.1.2 tree, not runtime |
| Iris, ImmediatelyFast | **expected** — both only affect paths BlockBoost reaches through vanilla options and render-state skipping; ImmediatelyFast's batching is why BlockBoost never issues GL calls itself | untested together |
| Entity Culling, More Culling | **expected, deliberately coexistable** — BlockBoost's entity culling can be switched off (`culling.entity.mode = off`) if you would rather one of them own it. More Culling's code is *not* merged or reimplemented from its source: its license forbids it (see [NOTICE.md](NOTICE.md)) | untested together |
| Lithium, FerriteCore, ModernFix, C2ME, Noisium | **expected** — server/worldgen-side or memory-side; no shared hooks. C2ME/Noisium-class work is *not* sold here as an FPS win, and nothing in the default client config pretends to be worldgen parallelism | untested together |
| Placeholder API | **expected** — no dependency declared; overlay text is our own | untested together |
| MobileGlues (GL4ES / LTW / Zink), physical Mali drivers | **untested, untestable here** — BlockBoost therefore never calls into GL directly; GL strings are read through guarded reflection, and `auto` vsync means "do not touch" | see the honest note below |

Because 26.2 replaces the GL renderer with Vulkan, nothing here depends on GL internals: the work is
render-state extraction, culling decisions, allocation, chunk scheduling, particle counts and vanilla
video options. That is also why the mod survives a renderer swap it has not seen yet.

## Development

```bash
./gradlew build                        # compile + unit tests, jar in build/libs/
./gradlew runClientGameTest            # the assertion suite, on a live client under xvfb
./gradlew runProductionClientGameTest  # the shipped jar booting in a real client
./gradlew dumpMinecraftApi             # verify an API against the real 26.1.2 jar
```

`dumpMinecraftApi` reads `tools/recon-classes.txt` / `recon-keywords.txt` / `recon-packages.txt` /
`recon-ask.txt` and prints `javap` output for those targets from the de-obfuscated 26.1.2 jar. That
is how every "this exists in 26.1.2" claim in this repository was checked, and it is the workflow
the CI report repeats on every push: **do not implement against remembered mappings here.** If you
change `tools/recon-ask.txt`, the next run answers the asks in the CI comment (order matters; the
report truncates).

Docs: [architecture](docs/ARCHITECTURE.md) · [compatibility](docs/COMPATIBILITY.md) ·
[benchmarks](docs/BENCHMARKS.md) · [install on Android](docs/INSTALL-ANDROID.md) ·
[troubleshooting](docs/TROUBLESHOOTING.md) · [changelog](docs/CHANGELOG.md)

## What this mod does *not* claim

* It does not claim a frame-rate improvement on any specific phone. There is no such measurement and
  no pretending otherwise.
* It does not claim MobileGlues compatibility beyond "it initializes and the mod makes no GL calls of
  its own". GL4ES, LTW, Zink and physical Mali driver behaviour are **untested** — [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) is what to check if a device disagrees with that.
* It does not skip gameplay ticks, change world generation, touch the server, send anything anywhere,
  or collect anything. No telemetry, no network, no analytics, no remote config.
* It does not promise a global GL state "fix", driver-specific hacks, or an unbounded cache anywhere.
* It does not require Sodium and does not include it.
* It does not change your launcher's `-Xmx`. Heap guidance in the docs is guidance; the mod reads the
  ceiling the JVM reports and never sets one.

## License and attribution

MIT, see [LICENSE](LICENSE) and [LICENSE.txt](LICENSE.txt). Ideas and API-shape confirmations from
other projects are attributed in [NOTICE.md](NOTICE.md); no upstream code is bundled, and one
project's license forbids the merge that would need doing, so it is not.

Tests live in `src/test/java` (pure logic, no Minecraft needed) and `src/gametest/java` (live
client). CI runs both on every push; the badge-free truth is the last run in the Actions tab.
