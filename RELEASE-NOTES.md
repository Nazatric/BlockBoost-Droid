# BlockBoost 1.0.0 for Minecraft 26.1.2 (Android first)

The file to download: **`blockboost-1.0.0-mc26.1.2.jar`**, attached to this release. The same
bytes are in the CI artifact `blockboost-jar` for the commit it names; the jar is deliberately
not committed into the branch, because a binary pushed onto a working branch by CI moves it
under whoever is working there. Built locally, it lands at
`build/libs/blockboost-1.0.0+mc26.1.2.jar`.

## What you need alongside it

| Required | Version |
| --- | --- |
| Minecraft (Java Edition) | **26.1.2** — the mod declares `~26.1.2`; 26.1 mods do not load on 1.21.x |
| Fabric Loader | **0.19.5** or newer |
| **Fabric API** | **0.155.3+26.1.2** — a hard dependency, not optional. The HUD, keybind, client tick and screen events are Fabric API rather than mixins, so without it the mod refuses to start. |
| Java runtime | **Java 25** (Minecraft 26.1's own minimum) |

Optional: **Mod Menu 21.0.0** (adds a config-screen button; BlockBoost's own screen is on
the `O` keybind either way), **Sodium**, **Entity Culling / More Culling**, **Lithium** —
each is detected and BlockBoost steps aside where they overlap.

On Android: Zalith Launcher with **MobileGlues v2.0.0** is the tuned target, on a Mali-class
GPU. The mod does **not** require MobileGlues and does not patch it: it detects the renderer
and scales its own heuristics, and runs identically on any other GL path.

## What it does

Client-side render-loop work, in eleven switchable subsystems: entity and block-entity
visibility culling with a cached, budgeted occlusion test; a real per-frame particle
ceiling; bounded, drop-oldest worker pools with thread-local scratch; memory hygiene with
fixed-capacity caches; adaptive frame-time control (advise or act, with hysteresis, cooldown
and automatic stand-down); vanilla video-option overrides (tri-state, restores on switch
off); chunk backlog guard; a frame cap and vsync written through the game's own limiter rather
than a limiter of ours; idle throttling for an unfocused or minimized window; capability-based
renderer profiles; a device-class automatic profile that runs once on the first frame;
diagnostics (frame distribution with p95/p99 and 1% lows, GC pauses, culled and suppressed
counts, pool queue/drop counters, the game's own throttle reason); and a touch-first settings
screen with six sections (Performance overview, Smart optimization, Rendering & visuals, Chunks
& world, Memory & stability, Advanced), a filter field, permanent one-line explanations on every
row, side-effect notes, six presets with a diff preview, and reset-with-confirmation per
section.

Full list and design rationale: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md),
[`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md). Install steps:
[`docs/INSTALL-ANDROID.md`](docs/INSTALL-ANDROID.md).

## What changed in this build

* **A regression audit, and deletions.** The frame cap no longer forces vsync, and the mod's own
  end-of-frame limiter is gone together with its two settings (`render.soft_pacing`,
  `render.smooth_pacing`); the ceiling is written into `FramerateLimitTracker`, the limiter the
  render loop already respects. Occlusion work that previously cost about 220 000 level reads per
  frame now runs inside a probe **and** millisecond budget with block-shape memoization, an
  allocation-free hot path, reusable job slots with a reclaim path, and a fail-open direction that
  draws anything it cannot decide in time.
* **Automatic device profile.** `Automatic` classifies the device from cores/heap/arch/GL strings
  once, on the first frame, on a fresh install only, and never writes a frame cap, a vsync state or
  one of your video options — there are tests that walk the whole heuristic grid to prove it.
* **Settings screen rebuilt** around what a person is deciding, with all text and bars drawn from
  prepared strings and every widget created once. It is exercised on a live client by the game test
  now, not just compiled.
* **CI**: the publish job stops committing the jar into the branch (release asset only), and the
  client-launch report now greps the game test's own measurement lines instead of tailing a log.

## What this release has *not* been measured on

No FPS or latency claim is made anywhere in this project, because none has been measured by
us on real hardware. Verified so far, by CI on every push:

* compiles and packages against the real 26.1.2 jar, with mixin targets checked against
  `javap` output of that same jar
* unit tests for config serialization/migration/clamping/corruption recovery, bounded
  cache and queue behaviour, percentile monotonicity, raycaster bounds and fail-open
  direction, preset validity, and packaging metadata (license, no network APIs, no other
  project's imports, every declared mixin class present)
* **a client game test that boots the actual game** (`runClientGameTest`): the mod
  initializes, mixins apply, the subsystems report `ACTIVE` with zero feature failures, the
  visibility hot path is timed over 2000 decisions inside one frame budget, every section of
  the settings screen is opened and required to lay out controls, and the config round-trips
  through a real game directory. That run happens on a software framebuffer under Xvfb, so
  its timings are a regression check and not a device benchmark.
* **the shipped jar launched in a real client** (`runProductionClientGameTest`), which proves
  the remapped artifact initializes inside a game without the test harness around it. It runs
  no assertions — the test code is deliberately not in the release jar.

  Correction, because it matters for how much to trust earlier notes: until this build the
  launch job only ran the production boot, so the assertion suite above had never executed in
  CI even where its results were described as if it had. Both runs are wired now, and the job
  fails on an assertion.

Not tested: a physical Mali GPU, MobileGlues/GL4ES/LTW/Zink translation behaviour, shader
packs, long-session thermal or battery effect, and any dedicated-server scenario beyond
"loads and stays idle". `docs/BENCHMARKS.md` documents the harness (and the methodology) if
you want numbers that mean something.

Keys that are *not* wired to anything in this release, and are shown as reserved rather
than silently ignored: `chunks.upload_budget`, `chunks.rebuild_coalescing`,
`chunks.coalesce_ticks`, `chunks.priority_bias`, `particles.far_limit`,
`particles.group_aware`. 26.1.x renamed the chunk render classes, so those hooks need their
own version-verified touch point; the ones that did get verified — `chunks.backlog_guard`,
`chunks.worker_limit`, the particle ceiling — are real.

## Changelog

See [`docs/CHANGELOG.md`](docs/CHANGELOG.md).

## License

GPL-3.0-only, see [`LICENSE.txt`](LICENSE.txt) and
[`NOTICE.md`](NOTICE.md) for attribution. No code was copied from other mods: the design
was informed by reading how Sodium, EntityCulling and Lithium approach these problems, and
Everything is original to this repository.
