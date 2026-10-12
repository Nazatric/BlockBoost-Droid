# Compatibility and capability model

BlockBoost's rule: **detect, then scale heuristics; never patch the renderer.** The
renderer profile in the settings exists so the mod can decide how aggressive to be, not
so it can reach into someone else's GL context. That matters most on Android, where the
GL stack is a translation layer whose behaviour differs per driver build, and it matters
for the next Minecraft version too: 26.2 replaces the GL renderer path with Vulkan, and
a mod built on raw GL state hacks dies with it. Nothing in this mod does that.

## Renderer profiles

| Profile | How it is detected | What changes |
| --- | --- | --- |
| `mobileglues` (GL4ES) | `java.library.path`/`os.name`/`os.arch` hints plus the **read-only** GL strings (`GL_VERSION`, `GL_VENDOR`, `GL_RENDERER`) fetched through the game's own context on the render thread, once, at the first frame | The most conservative non-unknown default: fewer state-dependent tricks, tighter worker ceilings, larger hysteresis on the adaptive controller. |
| `ltw` (LWJGL-to-GLES wrappers) | GL renderer string / system property sniffing | Same conservative bias; the culling and CPU work stays on, quality-side overrides are the parts that hold back. |
| `zink` | GL vendor string contains `zink`/`Vulkan` | Treats driver-internal batching as unavailable: fewer per-frame changes, more caching. |
| `desktop-gl` | anything reporting a desktop GL vendor | Full feature set available. |
| `unknown` | no signal, or the GL query failed or was unavailable | **Conservative.** Culling and bounded pools stay on (they cannot change what is visible); anything that alters quality or timing is left to you. |

Manual override: `android.renderer_profile` forces a profile, and the diagnostics report
prints which profile was resolved *and why* (`profileSource()`), so a wrong guess is
visible rather than silent.

GL is only ever **read** — one `glGetString`-equivalent through `GL`/`GL11` inside a
try/catch on the render thread, once per session. There is no `glEnable`, no
`glVertexAttrib*`, no state caching or restoration anywhere in this mod: an
"optimization" that leaves the GL state machine in a different shape than it found it is
how you get a black screen in someone else's mod.

## Device class

The automatic profile is a pure function of the numbers the JVM will actually answer with
(cores, heap ceiling, arch, Android hints, GL vendor/renderer, whether the GL path is a
translator), evaluated once on the first frame and cached for the session. `LOW` is chosen for 4 cores or fewer
or a heap under about 1.3 GB (and again at ≤ 6 cores under 2 GB); `HIGH` requires ≥ 8 cores,
≥ 5 GB **and** a non-mobile arch; unreadable numbers mean `UNKNOWN`, which means one worker and
no asynchronous culling. There is one classifier in the code — the one the profile applies — and
the status page reports its answer, never a second opinion. A flagship GPU name in a 2 GB
heap is still `LOW` — the GPU string only makes the profile *more* conservative, never less, and
the patch it writes can never contain a frame cap, a vsync state, or any `render.vanilla_*` video
option (`DeviceProfileAdvisorTest` asserts that over the whole grid, not on one device).

Detection is cached and never re-run per frame; nothing is shown as active in the settings screen
unless its `Feature` initialized, and a subsystem that another mod already owns reports
`handled by another mod` as the reason rather than silently doing double work.

`android.device_class` is `low | mid | high | unknown`, and `unknown` is the default. It
scales three things only: the per-frame culling test budget, the worker-thread ceiling,
and which particle tier the budget maps to. It never changes what is visible.

There is **no hardcoded SoC list** and no attempt to identify your chip. Device tables go
stale, and a mod that guesses "you have a Dimensity 720, so I will do X" is a mod that
behaves arbitrarily on the six billion phones it did not think of. If BlockBoost needs to
know something, it asks the JVM or the driver, and if it cannot ask, it stays
conservative and says so in the report.

## Other mods

| Mod | Behaviour |
| --- | --- |
| **Sodium** | Detected. Where Sodium already covers a subsystem (chunk rendering, its own culling and batching), BlockBoost switches that subsystem off and shows `handled by another mod` as the reason in the settings screen and the report. The CPU-side systems (visibility cache, pools, diagnostics, adaptive control) keep running, because Sodium does not do that work. |
| **Entity Culling / More Culling** | Detected; the matching BlockBoost culling subsystem stands down. Two mods deciding "is this visible" for the same entity is a fight whose loser is a mob that renders half the time. |
| **Lithium** | No overlap: Lithium is game logic, this is render-side. Both stay on. |
| **OptiFabric / Iris** | Not asserted either way. Iris shader packs change which draws are cheap, so the `shaders` preset exists, but BlockBoost does not claim shader-pack compatibility it has not measured. |
| **Mod Menu** | Optional. If present, BlockBoost appears with its own config screen. If not, the keybind works. |

Every one of those detections is a *dependency check*, not a class-path probe into
another mod's internals: BlockBoost never calls a Sodium or Iris API.

## Untested — stated plainly

What CI verifies: compilation against the real 26.1.2 jar, the unit tests, the mixin
targets against `javap` output of that jar, and a headless client launch (the game boots,
chunks load, the tick loop runs with the mod installed) under a virtual framebuffer.

What has **not** been measured, and where anyone reading this should treat the numbers as
yours to take, not ours to give:

* FPS on a physical Mali GPU under MobileGlues v2.0.0
* behaviour under LTW or Zink on Android
* thermal/battery effect over long sessions (the machinery to do this honestly — sustained
  runs with per-window data — is exactly what `docs/BENCHMARKS.md` describes, and it has
  not been run on real hardware by us)
* any shader pack
* dedicated-server behaviour beyond "the mod loads and does nothing"

## Known limits in 1.0.0

* `chunks.upload_budget`, `chunks.rebuild_coalescing`, `chunks.coalesce_ticks`,
  `chunks.priority_bias` are **not wired to anything yet.** 26.1.2 renamed the chunk
  render classes (`ChunkRenderDispatcher` no longer exists), so the queue-level work needs
  its own version-verified hook, and until it exists those keys are shown as reserved.
  `chunks.backlog_guard` and `chunks.worker_limit` *are* real (they clamp render/simulation
  distance through vanilla options, and cap BlockBoost's own pools).
* The particle ceiling caps `ParticleEngine.add` per frame and picks vanilla's density tier;
  `particles.far_limit` and `particles.group_aware` are likewise reserved for now.
* The settings screen pages instead of searching, because the text field's constructor is
  not version-verified here. Sections are one tap apart, and every row explains itself.
