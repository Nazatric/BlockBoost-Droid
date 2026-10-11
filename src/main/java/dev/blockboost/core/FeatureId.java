package dev.blockboost.core;

/**
 * Every independently switchable subsystem. The ordinals index the fast path
 * read by the render code ({@link FeatureRegistry#isActive}), so the order of
 * the constants is part of the contract: only append.
 */
public enum FeatureId {
    ENTITY_CULLING("culling", "Entity visibility culling"),
    BLOCK_ENTITY_CULLING("culling", "Block entity visibility culling"),
    ITEM_FRAME_CACHE("culling", "Item frame caching"),
    PARTICLE_BUDGET("particles", "Particle budget"),
    CHUNK_PIPELINE("chunks", "Chunk scheduling"),
    FRAME_PACING("rendering", "Frame pacing and cap"),
    VIDEO_OVERRIDES("rendering", "Vanilla video overrides"),
    WORKER_TUNING("cpu", "Background worker tuning"),
    MEMORY_HYGIENE("memory", "Memory hygiene"),
    ADAPTIVE("adaptive", "Adaptive performance controller"),
    DIAGNOSTICS("diagnostics", "Diagnostics"),
    HUD("diagnostics", "Performance overlay"),
    COMPAT("compat", "Compatibility reporting");

    private final String group;
    private final String label;

    FeatureId(String group, String label) {
        this.group = group;
        this.label = label;
    }

    public String group() {
        return group;
    }

    public String label() {
        return label;
    }

    public String translationKey() {
        return "blockboost.feature." + name().toLowerCase();
    }
}
