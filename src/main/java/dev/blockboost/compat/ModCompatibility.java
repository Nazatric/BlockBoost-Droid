package dev.blockboost.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.api.FabricLoader;

import dev.blockboost.core.FeatureId;

/**
 * Detects other optimization mods and decides which BlockBoost subsystems stand
 * down.
 *
 * <p>The rule this implements is from the project brief and from experience:
 * running two systems over the same data is worse than either one alone. If a
 * neighbour already owns a hook, BlockBoost reports it as "handled by another
 * mod" instead of racing it. Detection is by mod id only — presence is not a
 * promise that the other mod works, and BlockBoost never calls into it.
 */
public final class ModCompatibility {

    /** A mod we know something about. */
    public record Known(String modId, String displayName, List<FeatureId> suppresses, String note) {
    }

    public static final List<Known> KNOWN = List.of(
            new Known("sodium", "Sodium", List.of(FeatureId.CHUNK_PIPELINE, FeatureId.ENTITY_CULLING),
                    "Sodium owns the chunk renderer and does its own entity visibility test; BlockBoost keeps to CPU-side work it cannot duplicate."),
            new Known("rendertypeforge", "Render Type Forge (in development)", List.of(), ""),
            new Known("entityculling", "EntityCulling", List.of(FeatureId.ENTITY_CULLING, FeatureId.BLOCK_ENTITY_CULLING),
                    "EntityCulling already path-traces entities and block entities; two ray budgets over one scene is a net loss."),
            new Known("moreculling", "More Culling", List.of(FeatureId.BLOCK_ENTITY_CULLING, FeatureId.ITEM_FRAME_CACHE),
                    "More Culling owns block/face visibility, including item frames and chest models."),
            new Known("immediatelyfast", "ImmediatelyFast", List.of(FeatureId.PARTICLE_BUDGET),
                    "ImmediatelyFast batches immediate-mode draws; BlockBoost keeps its particle ceiling but does not duplicate buffer work."),
            new Known("iris", "Iris", List.of(FeatureId.VIDEO_OVERRIDES),
                    "A shader pack defines its own lighting and quality knobs, so BlockBoost will not overwrite them."),
            new Known("optifabric", "OptiFabric / OptiFine", List.of(FeatureId.ENTITY_CULLING, FeatureId.BLOCK_ENTITY_CULLING,
                    FeatureId.CHUNK_PIPELINE, FeatureId.VIDEO_OVERRIDES),
                    "OptiFine patches the same render methods BlockBoost hooks. Prefer removing one of them if you need both."),
            new Known("lithium", "Lithium", List.of(),
                    "No overlap: Lithium optimizes game logic, BlockBoost optimizes the client render path."),
            new Known("c2me", "C2ME", List.of(),
                    "No overlap on the client: C2ME is world generation and server chunk IO. Do not expect FPS from it."),
            new Known("noisium", "Noisium", List.of(),
                    "No overlap: world generation noise only."),
            new Known("ferritecore", "FerriteCore", List.of(),
                    "Complementary: it shrinks idle heap, BlockBoost reduces per-frame garbage."),
            new Known("modernfix", "ModernFix", List.of(),
                    "Complementary, though its resource-pack model caching and BlockBoost's model caches do not talk to each other."),
            new Known("modmenu", "Mod Menu", List.of(),
                    "Optional integration: BlockBoost contributes its settings screen to the Mods list when Mod Menu is installed."),
            new Known("cloth-config", "Cloth Config API", List.of(),
                    "Not required. BlockBoost ships its own settings screen on purpose."),
            new Known("yacl", "YetAnotherConfigLib", List.of(),
                    "Not required. BlockBoost ships its own settings screen on purpose."));

    private final Map<String, Known> present = new LinkedHashMap<>();
    private final Map<FeatureId, String> suppressions = new LinkedHashMap<>();

    public void scan() {
        present.clear();
        suppressions.clear();
        FabricLoader loader = FabricLoader.getInstance();
        for (Known k : KNOWN) {
            if (loader.isModLoaded(k.modId())) {
                present.put(k.modId(), k);
            }
        }
    }

    public void applySuppressions(Map<FeatureId, Boolean> allowOverride) {
        suppressions.clear();
        for (Known k : present.values()) {
            for (FeatureId id : k.suppresses()) {
                if (Boolean.TRUE.equals(allowOverride.get(id))) {
                    continue;
                }
                suppressions.putIfAbsent(id, "suppressed: " + k.displayName() + " already handles this");
            }
        }
    }

    public boolean present(String modId) {
        return present.containsKey(modId);
    }

    public int detectedCount() {
        return present.size();
    }

    public List<Known> detected() {
        return List.copyOf(present.values());
    }

    public String suppressionReason(FeatureId id) {
        return suppressions.get(id);
    }

    public List<String> reportLines() {
        List<String> out = new ArrayList<>();
        if (present.isEmpty()) {
            out.add("no other optimization mods detected - BlockBoost runs standalone");
            return out;
        }
        for (Known k : present.values()) {
            out.add(k.modId() + " (" + k.displayName() + ")" + (k.note().isBlank() ? "" : ": " + k.note()));
        }
        for (Map.Entry<FeatureId, String> e : suppressions.entrySet()) {
            out.add("  -> " + e.getKey().name() + " " + e.getValue());
        }
        return out;
    }

    /** Convenience for the settings screen: does anything overlap at all? */
    public boolean anyOverlap() {
        return !suppressions.isEmpty();
    }
}
