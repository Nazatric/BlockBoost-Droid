# Measuring BlockBoost (method, not results)

**There are no benchmark numbers in this repository, on purpose.** A table of "−38% frame
time on a Redmi Note 12" copied into a README is the single least reproducible artifact in
Minecraft modding: it silently encodes one GPU driver build, one thermal state, one world,
one render distance, one heap size, one background app. This project ships the harness so
that the numbers people quote are *measured, dated, and complete enough to argue with*.

## The harness in the jar

Two JVM properties, no commands and no keybinds needed (a launcher's console or
`java_args` is enough):

```
-Dblockboost.bench=3600          # frames to sample, then write the report
-Dblockboost.exitafter=180       # seconds; writes the report and closes the client
```

Both write into `<game dir>/blockboost/`:

* `bench-report.txt` — the full report: resolved renderer profile and why, device class,
  active/inactive features with reasons, frame distribution (mean, median, p95, p99, 1%
  lows, spike count, missed-target frames), GC pause count and total, culled entity and
  block entity counts, suppressed particle count, pool queue/drop counters, heap summary.
* `bench-frames-latest.csv` — one row of the same numbers, for diffing two runs.

The report also records the *conditions*: render distance, simulation distance, particles
tier, entity distance scaling, vsync state, frame cap, whether the game was in a menu.
A number without those is a rumour.

## How to run a test that means something

1. **Same world, same coordinates, same thing to look at.** A farm or a village with a
   known entity count. Standing in a flower field measures nothing.
2. **Same thermal state.** Run 60–90 seconds first, then measure. A cold phone and a
   45 °C phone are different machines — this is the variable that makes most Android
   benchmarks unreproducible.
3. **Two separate tests: capped and uncapped.** These measure different things and
   averaging them is how you get a result that describes neither.
   * *capped* (`render.framerate_cap` = 60, vsync matching your panel): measures
     consistency — frame-time p95/p99 and 1% lows. This is what "smooth" means.
   * *uncapped* (cap 0, vsync off): measures throughput — how much real work was removed.
4. Change **one** setting at a time, or one preset. `BlockBoost settings` → the preset
   screen shows exactly which keys a change touched, so you can paste that diff.
5. Repeat 3× and take the middle run. Phones are noisy; two runs is an anecdote, three is
   a measurement.

If you report a result anywhere, the useful form is: device + SoC, launcher, Minecraft,
loader, Fabric API, renderer, the `bench-report.txt` conditions block, capped and uncapped
numbers separately.

## What the harness deliberately does not do

* No auto-tuning loop that "optimizes until FPS is highest": that optimises against its own
  metric, and frame count rewards drawing less. The adaptive controller can only step
  through a fixed, reversible ladder, and it stands down the moment you touch anything.
* No "boost" numbers in the UI (no fake memory freed, no invented GPU clock). If a figure
  is not measured, it is not printed.
* No comparison against other mods, run by us, without their authors' measurement setup.
  That comparison is available to anyone: install one, run the harness, install both,
  run it again.
