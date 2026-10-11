# Architecture

```
src/main/java/dev/blockboost/
  BlockBoost.java              logging, id() — no Minecraft client types
  BlockBoostRuntime.java       config lifecycle, save coalescing, registry handle
  config/     SettingKey, ConfigSchema, Settings, ConfigStore, ConfigMigrations, Preset
  core/       Feature, FeatureId, FeatureRegistry, FeatureStatus, FeatureEnvironment,
              FeatureRuntimeContext
  device/     DeviceCapabilities, DeviceClass, RendererProfile, GpuInfo
  util/       LongByteCache, RollingStats, ScratchBuffers, TaskPools, CooldownTimer, Json*
  compat/     ModCompatibility (detection + suppression)
  culling/    LevelProbe, OccupancyField, OcclusionRaycaster   ← no Minecraft types at all
src/main/resources/            fabric.mod.json, lang, icon
src/client/java/dev/blockboost/client/
  BlockBoostClient.java        entry point: services, features, keybind, HUD, bench props
  ClientServices.java          pools, scratch, sampler, GC watcher, chunk stats, reports,
                               and the FeatureEnvironment features see
  HotState.java                the static state the mixins read (refreshed between frames)
  culling/AsyncVisibilityEngine   cache, budget, worker hand-off, invalidation
  features/                    one class per subsystem + VanillaOptions (the only writer
                               of vanilla video options)
  adaptive/AdaptiveController  hysteresis, cooldown, ladder, stand-down on manual edit
  ui/                          BlockBoostSettingsScreen, InfoScreen, PerformanceHud
  services/                    FrameSampler, GcMonitor, ChunkPipelineStats,
                               DiagnosticsService, ReportWriter
  mixin/                       4 targets, all listed in blockboost.client.mixins.json
  compat/BlockBoostModMenuApiImpl
src/test/java/dev/blockboost/  unit + packaging tests (no game required)
```

## The three rules that shape it

**1. `config`, `util`, `core`, `device`, `compat` and `culling` contain no Minecraft
types.** That is not purity for its own sake: it is what makes the interesting parts
(cache eviction, percentile maths, migration, ray-march bounds, feature state machine)
testable without booting a game, and those are exactly the parts where a subtle bug costs
a user their session. The client layer reaches *down*; nothing reaches *up*.

**2. Mixins read `HotState`, never config objects.** A check that runs once per entity per
frame must be a field load, not a map lookup — and it must be the same value for the whole
frame, because a config change landing halfway through an entity loop would produce a
half-culled frame. `HotState.refresh()` is the only writer and runs between frames.

**3. Every subsystem is optional at runtime and individually accountable.** Each feature
declares `enabledByConfig` and may refuse with a reason; activation is wrapped, so a
throwing subsystem is quarantined, its reason is recorded, and the other ten keep working.
The same list of reasons is what the report and the settings screen print, so "is it
actually on?" always has an answer.

## Threading

| Thread | Owns | Never touches |
| --- | --- | --- |
| Client/render | world state, `ClientLevel`, vanilla options, all UI, `HotState` writes | worker internals |
| `blockboost-visibility-N` | arithmetic on published `OccupancyField` snapshots | `ClientLevel`, `BlockState`, any MC object |
| `blockboost-config-saver` | writing `blockboost.json` (atomic, `.bak` first) | anything MC |

Worker results come back through a fixed-size ring of packed longs drained at the start of
a frame, so exactly one table is owned by exactly one thread: no locks, no torn reads, no
unbounded structure. A full ring drops work (an object is drawn that could have been
skipped) rather than blocking or corrupting state — dropping is always the safe failure
direction for this mod.

The occupancy grid is deliberately conservative (a voxel is solid only if every sampled
block in it is a full opaque cube), so a one-block wall hides nothing. That understates
what could be culled, which is the correct bias: this design can lose an optimization
opportunity, never a visible entity.

## Why so few mixins (four)

`Minecraft.renderFrame` (frame boundaries), `LevelRenderer.extractEntity` +
`extractVisibleEntities` + `allChanged` (entity visibility, camera capture, invalidation),
`BlockEntityRenderDispatcher.tryExtractRenderState` (block entity visibility),
`ParticleEngine.add` (particle ceiling).

Everything else goes through supported surfaces: Fabric API events (tick, lifecycle, key
binds, HUD layers, Mod Menu entry point) and *vanilla video options* for anything that
changes quality or pacing. Each extra target is a future version's rename waiting to happen,
and 26.1→26.2 is a live demonstration: the renderer's extraction layer moved to
`net.minecraft.client.renderer.extract.LevelExtractor`, which is exactly the kind of change
that strands a clever mixin and harms none of these.

## Build and CI as part of the design

The development sandbox has no JDK-with-javac and no Maven access, so CI is the compiler —
and to keep that loop honest, `./gradlew dumpMinecraftApi` runs `javap` over the classes
listed in `tools/recon-classes.txt` against the **real 26.1.2 jar**, and
`.github/scripts/report.py` answers the specific questions in `tools/recon-ask.txt` and
posts the answers to the build issue. So every method name in this codebase is verified
against the artifact it will run against, rather than remembered from an older mapping set.
The release jar is produced by CI only, and verified there before it is attached.
