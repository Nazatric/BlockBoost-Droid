# Changelog

## 1.0.0 — first release (Minecraft 26.1.2)

Built for Android first: Fabric on Minecraft **26.1.2**, Java 25, Loader 0.19.5,
Fabric API required, tuned for MobileGlues v2.0.0 (GL4ES) on Mali-class GPUs, and
conservative wherever the runtime cannot be identified.

### Added

* **Config engine** — versioned JSON schema (~70 validated keys), clamp-on-write,
  atomic saves with a `.bak` copy, corrupt-file recovery kept as
  `blockboost.json.corrupt-<n>`, migrations that preserve keys from newer versions,
  debounced off-thread saving.
* **Feature registry** — per-subsystem state machine with a human-readable reason for
  everything inactive (`disabled in settings` / `handled by another mod` / `not
  supported by this renderer` / `switched off after an error` / `disabled by safe mode`),
  and per-feature quarantine so one failure cannot take the mod down.
* **Entity visibility culling** — occlusion tests against live block state on the client
  thread within a per-frame budget, or against a published coarse occupancy grid on
  worker threads; verdicts cached, invalidated on level/reload/teleport; nametags kept
  while models are skipped; players, mounts, minecarts, displays, named and boss entities
  never culled.
* **Block entity visibility culling** — position-keyed cache, deferring to vanilla's
  `shouldRenderOffScreen()` and to "no renderer" so beacons/conduits and modded blocks are
  handled by the game's own flags rather than a hardcoded list.
* **Particle ceiling** — real per-frame cap on `ParticleEngine.add`, plus vanilla density
  tier selection driven by the configured budget and device class; `keep_gameplay`
  prevents the `Minimal` tier.
* **Frame pacing** — frame cap, vsync and an unfocused/minimized throttle, applied
  exclusively through the game's video options.
* **Vanilla video overrides** — tri-state (`ignore`/`on`/`off`) for mipmaps, biome blend,
  entity distance, render/simulation distance, ambient occlusion, entity shadows, with
  previous values remembered and restored when the feature is switched off.
* **Chunk backlog guard** — sustained overrun shrinks render/simulation distance one step,
  hysteresis and cooldown on both sides, full restore on stand-down.
* **Bounded CPU work** — fixed thread ceilings (`big.LITTLE` aware), drop-oldest bounded
  queues, thread-local scratch under a byte cap, pause-and-drain when the window is
  hidden.
* **Memory hygiene** — fixed-capacity caches with eviction, release on level exit, GC
  pause observation from the platform MXBeans. Never `System.gc()`, never heap resizing.
* **Adaptive control** — `off` / `recommend` / `active`, one rung at a time down a fixed
  reversible ladder, and an automatic stand-down after any manual edit.
* **Diagnostics** — frame distribution with p95/p99 and 1% lows, spike and
  missed-target counts, culled/suppressed counters, pool queue and drop counters, heap
  summary, renderer profile *and how it was resolved*, feature list; text report + CSV
  into the game directory; `-Dblockboost.bench` / `-Dblockboost.exitafter` harness.
  No telemetry, no networking (enforced by a build test).
* **UI** — touch-first screen built from vanilla widgets only: 11 sections, paged rows,
  `-`/`+`/`?` per row, every setting showing description, range, default, risk and
  restart requirement; 7 presets with an explicit diff preview before apply;
  reset-with-confirmation; `K` overlay cycle, `O` settings keybind; Mod Menu entry point
  when Mod Menu is installed.
* **Tests** — config round-trip/clamp/corruption/migration, cache and queue bounds,
  percentile monotonicity, feature isolation, raycaster termination and fail-open
  behaviour, preset validation against the schema, and packaging assertions (declared
  mixins exist, metadata versions, license/attribution files present, no network APIs,
  no other project's imports).

### Deliberately not in 1.0.0

* Vanilla chunk-builder instrumentation (upload budget, rebuild coalescing, priority
  bias) and `particles.far_limit` / `particles.group_aware`: the classes involved were
  renamed in 26.1.x and those hooks are not version-verified yet, so the keys are shown
  as reserved rather than wired to something guessed. See *Known limits* in
  [COMPATIBILITY.md](COMPATIBILITY.md).
* Text-field search in the settings screen (`EditBox`'s constructor is unverified here);
  sections and paging do the filtering.
* Any claim of measured FPS. [BENCHMARKS.md](BENCHMARKS.md) explains the harness instead.
