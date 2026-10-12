# Installing BlockBoost on Android

Target setup this mod is developed against:

| Piece | Version | Notes |
| --- | --- | --- |
| Minecraft Java | **26.1.2** | BlockBoost declares `~26.1.2`. A 26.1 jar does not load on 1.21.x and vice versa — 26.1 also changed the remapping model, so pre-26.1 Fabric mods generally cannot be loaded here at all. |
| Fabric Loader | **0.19.5** or newer | |
| Fabric API | **0.155.3+26.1.2** | **Required.** The overlay, keybind and client tick/screen events are Fabric API, not mixins. |
| Java runtime | **Java 25** | Minecraft 26.1's own minimum. |
| GL translator | MobileGlues **v2.0.0** (GL4ES) | Optional. BlockBoost runs without it and never patches it. |
| Launcher | Zalith (or any launcher that loads Fabric on 26.1.x) | |

## Steps

1. **Download the jar** from [Releases](../releases) — `blockboost-<version>-mc26.1.2.jar`.
   The same file is committed at the repository root, so it can also be downloaded from
   the file list. Do not rename it.
2. **Install Fabric Loader 0.19.5+ for 26.1.2** in the launcher (client install).
3. **Copy two jars into `mods/`**: `fabric-api-0.155.3+26.1.2.jar` and the BlockBoost jar.
   A partial Fabric API ("fabric-api" minus its modules) is a common source of
   `NoClassDefFoundError` at startup — use the full adapter jar.
4. **Pick the renderer** in the launcher. MobileGlues v2.0.0 is the tuned path; the
   launcher's own GL option works too. BlockBoost *detects* which one it is running
   under and scales its own heuristics; it never calls into the translator.
5. **Launch once, then configure.** Press **O** (default `BlockBoost settings` keybind)
   — use the launcher's on-screen keyboard if a physical one is not attached — or open
   Mod Menu → BlockBoost if you installed it. There is also a startup chat line the
   first time you run it, which tells you the config file path.
6. Choose a preset, read the diff it previews, apply. Then open the `diagnostics`
   section and switch the overlay on if you want to see numbers while you play.

## Config file location

`<game directory>/config/blockboost.json` — on Zalith that is normally
`Android/data/<launcher package>/files/games/<instance>/config/blockboost.json`.
The exact path is printed in chat on first run and at the top of every diagnostics
report. Reports and CSVs are written to `<game directory>/blockboost/` — nothing is
written anywhere else, and there is no network code in the jar to send it anywhere.

A corrupt or unreadable config is never silently replaced: the broken file is kept
alongside as `blockboost.json.corrupt-<n>`, defaults are used, and you get a chat message
saying so.

## Heaps, and why this README does not tell you to raise yours

The most common Android advice — "give it 6 GB of heap" — is not something this mod can
recommend, because it is usually wrong on a phone: a bigger heap means a bigger pause
when the collector runs, and thermal headroom is the actual limit on most SoCs.

Use the mod's own numbers instead. The overlay and the report show **GC pause
duration and count** (read from the JVM's own GarbageCollectorMXBeans — BlockBoost only
observes, it never calls `System.gc()` and never changes your launcher's heap setting).
If your stutters line up with 150 ms+ collections, the heap is too small for your
render distance and shader load; if they are 5–20 ms, your stutters are not memory at
all and raising the heap will only move them later.

## If it does not load

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md). The three most common causes are a missing
Fabric API jar, a Minecraft version other than 26.1.x, and a launcher that installed
"client" and "server" sides separately.
