# Troubleshooting

Read the top of a diagnostics report first: it lists the resolved renderer profile *and
why*, the active features, and every subsystem that is **not** active with its reason.
Most "it isn't working" reports are answered by that list alone.

## The mod does not appear / the game will not start

| Symptom | Cause | Fix |
| --- | --- | --- |
| Loader says `Mod BlockBoost requires *Fabric API*` | Fabric API missing or partial | Install the full `fabric-api-0.155.3+26.1.2.jar` adapter, not an individual module. |
| `requires minecraft ~26.1.2` | Wrong game version | 26.1 mods do not load on 1.21.x, and pre-26.1 mods do not load on 26.1 either (the remapping model changed). Match the version exactly. |
| `requires java >=25` | Launcher using an older bundled runtime | Select Java 25 in the launcher's runtime settings. |
| Crash mentioning `dev.blockboost.client.mixin` | A mixin failed to apply — usually another mod already rewrote that method | Note the *other* mod's name from the crash, and set `compat.crash_on_failure` to `false` (default) so BlockBoost quarantines the subsystem instead of aborting; then report it. |
| Nothing in the log mentioning BlockBoost at all | Jar is in the wrong folder or is a source archive | The file must be `blockboost-<version>-mc26.1.2.jar` inside the instance's `mods/`. |

## Something looks wrong in the world

* **Mobs or chests that appear when you move, or not at all.** That is the visibility
  cache being too sticky for your scene. Turn `culling.entity_debug` on (it makes the
  decision exact instead of budgeted), then reduce or switch off `culling.block_entity_occlusion`.
  Culling is designed to fail open: if a decision is ever in doubt, the thing is drawn.
  If you can *prove* it hid something visible, that is a bug — open an issue with the
  entity, the block it is behind, and a report.
* **Flickering at a chunk border or during teleport.** Expected for one or two frames by
  design: a teleport or dimension change invalidates the whole cache, and the frames while
  it is rebuilt draw everything. It should never persist.
* **Beacon beams or conduit lasers missing.** Should be impossible: block entity culling
  defers to vanilla's own `shouldRenderOffScreen()`, which is what marks those renderers.
  If it happens with a modded block, that mod's renderer is the odd one out — set
  `culling.block_entity` off and say which block in the report.
* **Rain, damage or critical-hit particles vanished.** Raise `particles.max_per_tick`, or
  leave `particles.keep_gameplay` on so the density tier never drops to `Minimal`.

## It feels slower with the mod installed

That is a legitimate outcome on some devices, and the mod is built so it can be pinned down:

1. Open the overlay (`K` cycles off → basic → detailed) and look at `missed target frames`
   and the pool line. `dropped` growing every second means your worker ceiling is too high
   for the device — lower `cpu.*`, or turn on `android.big_little` awareness.
2. `adv.diagnose_detailed` off and `diag.enabled` off removes all sampling overhead.
3. Frame *time* mattering more than frame *count*? Then look at p95/p99, not FPS: a cap
   that is too high on a hot phone invites a driver downclock, which shows up as periodic
   200 ms frames.
4. To rule us out completely: `compat.safe_mode` on. That disables every subsystem with a
   visible quality or timing side effect and leaves only the ones that cannot change what
   you see.

## Config problems

* **Reset everything:** delete `<instance>/config/blockboost.json`. A `.bak` of the previous
  file is kept by every save; a file that failed to parse is kept as
  `blockboost.json.corrupt-<n>` rather than overwritten.
* **"clamped …" in the startup chat:** a value in the file was outside the range the
  schema allows (hand-edit, or a config from a newer version). The clamped keys are listed
  by name; nothing else was touched.
* **A setting that says "restart needed":** it is read once when the subsystem starts
  (cache sizes, pool ceilings, mixin-gated behaviour). The value was saved; it applies on
  the next launch.

## Reporting

Paste three things: the report's header block (versions, profile and why, feature list),
the conditions block (render distance, cap, vsync, particles), and — if the game crashed —
the top of the crash report including the mixin line. Device model and Android version are
useful; a benchmark screenshot is not, unless it came from the harness in
[BENCHMARKS.md](BENCHMARKS.md).
