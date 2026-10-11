# Notices and attribution

BlockBoost is original code, written for this repository. It is **not** a
repackaging, fork or merge of any existing optimization mod, and it does not
bundle another mod's classes, mixins, assets or generated code.

## Ideas and prior art that shaped the design

The following projects were read as *public source* to understand how a
rendering-optimizing Fabric mod should be shaped for Minecraft 26.1.x — what the
mixin targets are called in this version, where render-state extraction happens,
which things must never be culled. None of their code is included, and their
licenses therefore do not apply to this jar; the entry for More Culling records a
deliberate decision, not a use.

| Project | License | How it was used |
| --- | --- | --- |
| [Sodium](https://github.com/CaffeineMC/sodium) (26.1.2 branch) | PolyForm Shield 1.0 | Read only, to confirm 26.1.x render pipeline names and mixin shapes. No code taken — its license does not permit merging into an MIT project. |
| [Entity Culling](https://github.com/tr7zw/Entity-Culling) | MIT | Read only, to confirm 26.1 method signatures (`extractEntity`, `extractVisibleEntities`, `tryExtractRenderState`) and the "return an empty render state" shape. Concept credit: skipping *state extraction* rather than drawing is their approach as well as ours. |
| [More Culling](https://github.com/Clienthax/MoreCulling) | restrictive (non-permissive for merging) | **Not used.** Explicitly excluded as a code source because its license forbids what this repository would need to do with it. The block-face and model-prep guards here are written from vanilla behaviour instead. |
| [Lithium](https://github.com/CaffeineMC/lithium-fabric) | LGPL-3.0 | Not used for code. Read for the general principle that gameplay/logic fixes and render fixes should stay separately switchable. |
| [C2ME](https://github.com/Earthcomputer/C2ME-fabric) | MIT | Not used. Its generation-side work is deliberately out of scope for this client-only jar, and never presented as a client FPS win. |
| [Fabric API](https://github.com/FabricMC/fabric) | Apache-2.0 / MIT (dual) | **Required runtime dependency** for the HUD layer, keybind registration and screen/tick events. Used through its public API only. |
| [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues-release) | proprietary (GL4ES-based translator) | Not a code dependency. It is the runtime this mod targets on Android; it is only *detected*, never patched, and BlockBoost runs without it. |
| [Zalith Launcher](https://github.com/ZalithLauncher/Zlapi) | GPL-3.0 | Not a dependency. Documented as the launcher this mod is tuned and tested against. |

Minecraft, its names and assets are © Mojang / Microsoft. This project is not
affiliated with or endorsed by Mojang Studios.

## Why there is no bundled "optimization suite"

A single jar that re-ships other mods' classes looks convenient and is a support
disaster: the licenses clash (MIT + PolyForm + LGPL in one artifact), the version
matrix goes stale within weeks, and every bug report arrives here for code nobody
wrote. So BlockBoost ships its own code only, and instead *cooperates*: it detects
Sodium, Sodium-likes and other culling mods and switches its overlapping systems
off with a reason shown in the settings screen and the diagnostics report, rather
than fighting another mod for the same injection point.
