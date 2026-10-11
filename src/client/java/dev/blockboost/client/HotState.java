package dev.blockboost.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import net.minecraft.world.phys.AABB;

import dev.blockboost.BlockBoost;
import dev.blockboost.client.culling.AsyncVisibilityEngine;
import dev.blockboost.client.culling.LevelProbe;
import dev.blockboost.client.services.ChunkPipelineStats;
import dev.blockboost.client.services.FrameSampler;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.SettingKey;
import dev.blockboost.config.Settings;
import dev.blockboost.core.FeatureId;
import dev.blockboost.core.FeatureRegistry;

/**
 * The render hot path's view of the config.
 *
 * <p>Mixins read plain static fields here instead of calling into config objects:
 * a check that runs once per entity per frame must be a field load, not a map
 * lookup, and it must be the <em>same value</em> for the whole frame — a config
 * change landing halfway through an entity loop would produce a half-culled frame.
 * So {@link #refresh()} is the only writer, and it runs between frames.
 *
 * <p>The whitelist logic lives here too, so that entity culling and block entity
 * culling refuse to touch the same set of things and the rules can be read in one
 * place. Everything in it is "render it anyway", never the reverse.
 */
public final class HotState {

    // ── culling ───────────────────────────────────────────────────────────────
    public static volatile boolean entityCulling;
    public static volatile boolean entityOcclusion;
    public static volatile boolean entityDebug;
    public static volatile boolean keepNamed;
    public static volatile boolean keepNametags;
    public static volatile int entityMaxDistance;
    public static volatile boolean blockEntityCulling;
    public static volatile boolean blockEntityOcclusion;
    public static volatile int blockEntityCacheTicks;
    /** Protects item frames from the cull (labels, maps and clocks people read). */
    public static volatile boolean protectItemFrames;

    // ── particles ─────────────────────────────────────────────────────────────
    public static volatile int particleBudget;
    public static volatile int particleFarLimit;
    public static volatile boolean particleKeepGameplay;
    public static volatile int particlesLeftThisFrame;

    // ── chunk pipeline ────────────────────────────────────────────────────────
    public static volatile boolean chunkPipeline;
    public static volatile int uploadBudget;
    public static volatile boolean coalesceRebuilds;
    public static volatile int coalesceTicks;
    public static volatile boolean backlogGuard;

    // ── cpu / memory ──────────────────────────────────────────────────────────
    public static volatile boolean scratchReuse;
    public static volatile boolean stableLookups;
    public static volatile boolean releaseOnExit;

    // ── rendering ─────────────────────────────────────────────────────────────
    public static volatile boolean framePacing;
    public static volatile boolean idleThrottle;
    public static volatile int idleFrameCap;
    public static volatile int frameCap;
    public static volatile boolean videoOverrides;

    // ── diagnostics ───────────────────────────────────────────────────────────
    public static volatile boolean hud;
    public static volatile boolean hudAdvanced;

    // ── services ──────────────────────────────────────────────────────────────
    public static volatile AsyncVisibilityEngine visibility;
    public static volatile FrameSampler frames;
    public static volatile ChunkPipelineStats chunkStats;

    public static volatile long frameIndex;
    public static volatile long culledEntities;
    public static volatile long culledBlockEntities;
    public static volatile long suppressedParticles;
    public static volatile long deferredUploads;
    public static volatile long coalescedRebuilds;

    private HotState() {
    }

    /** Re-reads every field above from the settings + registry. Client thread. */
    public static void refresh() {
        ClientServices services = BlockBoostClient.services();
        if (services == null) {
            return;
        }
        Settings s = services.settings();
        FeatureRegistry registry = dev.blockboost.BlockBoostRuntime.get().registry();
        boolean safe = s.bool(ConfigSchema.COMPAT_SAFE_MODE);

        entityCulling = registry.isActive(FeatureId.ENTITY_CULLING)
                && s.bool(ConfigSchema.CULL_ENTITY)
                && s.is(ConfigSchema.CULL_ENTITY_MODE, "frustum_occlusion");
        entityDebug = s.bool(ConfigSchema.CULL_ENTITY_DEBUG);
        keepNamed = s.bool(ConfigSchema.CULL_ENTITY_KEEP_NAMED);
        keepNametags = s.bool(ConfigSchema.CULL_ENTITY_KEEP_NAMETAGS);
        entityMaxDistance = s.integer(ConfigSchema.CULL_ENTITY_MAX_DISTANCE);
        blockEntityCulling = registry.isActive(FeatureId.BLOCK_ENTITY_CULLING) && s.bool(ConfigSchema.CULL_BLOCK_ENTITY)
                && !safe;
        blockEntityOcclusion = s.bool(ConfigSchema.CULL_BLOCK_ENTITY_OCCLUSION);
        blockEntityCacheTicks = s.integer(ConfigSchema.CULL_BLOCK_ENTITY_CACHE_TICKS);
        protectItemFrames = s.bool(ConfigSchema.CULL_ITEM_FRAMES);

        particleBudget = registry.isActive(FeatureId.PARTICLE_BUDGET) && !safe
                ? s.integer(ConfigSchema.PARTICLE_MAX_PER_TICK)
                : Integer.MAX_VALUE;
        particleFarLimit = s.integer(ConfigSchema.PARTICLE_FAR_LIMIT);
        particleKeepGameplay = s.bool(ConfigSchema.PARTICLE_KEEP_GAMEPLAY);

        chunkPipeline = registry.isActive(FeatureId.CHUNK_PIPELINE) && !safe;
        uploadBudget = s.integer(ConfigSchema.CHUNK_UPLOAD_BUDGET);
        coalesceRebuilds = s.bool(ConfigSchema.CHUNK_REBUILD_COALESCING);
        coalesceTicks = s.integer(ConfigSchema.CHUNK_COALESCE_TICKS);
        backlogGuard = s.bool(ConfigSchema.CHUNK_BACKLOG_GUARD);

        scratchReuse = s.bool(ConfigSchema.CPU_SCRATCH_REUSE);
        stableLookups = s.bool(ConfigSchema.CPU_STABLE_LOOKUP_CACHE);
        releaseOnExit = s.bool(ConfigSchema.MEM_RELEASE_ON_EXIT);

        framePacing = registry.isActive(FeatureId.FRAME_PACING) && s.bool(ConfigSchema.RENDER_SMOOTH_PACING);
        idleThrottle = s.bool(ConfigSchema.RENDER_IDLE_THROTTLE);
        idleFrameCap = s.integer(ConfigSchema.RENDER_IDLE_FRAME_CAP);
        frameCap = s.integer(ConfigSchema.RENDER_FRAME_CAP);
        videoOverrides = registry.isActive(FeatureId.VIDEO_OVERRIDES) && s.bool(ConfigSchema.RENDER_VIDEO_OVERRIDES);

        hud = registry.isActive(FeatureId.HUD) && !s.is(ConfigSchema.DIAG_HUD, "off");
        hudAdvanced = s.is(ConfigSchema.DIAG_HUD, "advanced") || s.bool(ConfigSchema.ADV_DIAG_DETAILED);

        visibility = services.visibility();
        frames = services.frames();
        chunkStats = services.chunkStats();
    }

    public static void newFrame() {
        frameIndex++;
        particlesLeftThisFrame = particleBudget;
    }

    // ── decisions used by mixins ──────────────────────────────────────────────
    public static boolean active(FeatureId id) {
        FeatureRegistry registry = FeatureRegistry.instance();
        return registry != null && registry.isActive(id);
    }

    /**
     * True when this entity may be skipped this frame. Every path that is not
     * certain returns false.
     */
    public static boolean skipEntity(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        if (!entityCulling || client == null || client.level == null || entity == null) {
            return false;
        }
        if (entity == client.player || alwaysRender(entity)) {
            return false;
        }
        if (entity.getRootVehicle() != entity) {
            // A rider: culling it separately from its mount produces a floating
            // saddle, so the mount's decision governs.
            return false;
        }
        AsyncVisibilityEngine engine = visibility;
        if (engine == null) {
            return false;
        }
        AABB box = entity.getBoundingBox().inflate(1.0D, 0.5D, 1.0D);
        AsyncVisibilityEngine.Request request = new AsyncVisibilityEngine.Request(entity.getId(),
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        byte decision = engine.evaluate(request, probe(client), entityOcclusion);
        if (decision == AsyncVisibilityEngine.HIDDEN) {
            culledEntities++;
            return true;
        }
        return false;
    }

    /** Entities whose rendering carries information a player may be relying on. */
    public static boolean alwaysRender(Entity entity) {
        if (entity instanceof Player || entity instanceof EnderDragon || entity instanceof WitherBoss) {
            return true;
        }
        if (protectItemFrames && entity instanceof ItemFrame) {
            return true;
        }
        if (entity instanceof AbstractMinecart || entity instanceof Display) {
            // Moving rides and display entities are read as information (minecart
            // rails, item frames on a wall, a server's scoreboard display), and
            // their motion is exactly when a stale verdict looks worst.
            return true;
        }
        // A named entity is somebody's marker: nametags, armor stands with a
        // label, a pet with a name. Rendering those is cheap, losing them is not.
        return keepNamed && entity.getCustomName() != null;
    }

    public static boolean skipBlockEntity(BlockEntity blockEntity) {
        Minecraft client = Minecraft.getInstance();
        if (!blockEntityCulling || blockEntity == null || client == null || client.level == null) {
            return false;
        }
        if (alwaysRenderBlockEntity(blockEntity)) {
            return false;
        }
        AsyncVisibilityEngine engine = visibility;
        if (engine == null) {
            return false;
        }
        var pos = blockEntity.getBlockPos();
        long key = dev.blockboost.util.LongByteCache.packPosition(pos.getX(), pos.getY(), pos.getZ());
        AABB box = new AABB(pos).inflate(0.75D);
        AsyncVisibilityEngine.Request request = new AsyncVisibilityEngine.Request(key,
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        byte decision = engine.evaluate(request, probe(client), blockEntityOcclusion);
        if (decision == AsyncVisibilityEngine.HIDDEN) {
            culledBlockEntities++;
            return true;
        }
        return false;
    }

    /** Beams and light sources are visible far beyond their block: never cull them. */
    public static boolean alwaysRenderBlockEntity(BlockEntity blockEntity) {
        return blockEntity instanceof BeaconBlockEntity || blockEntity instanceof ConduitBlockEntity
                || !blockEntity.getBlockState().isValidModel();
    }

    /** A reload or a dimension change invalidates every cached verdict. */
    public static void invalidate(String why) {
        AsyncVisibilityEngine engine = visibility;
        if (engine != null) {
            engine.invalidateAll(why);
        }
    }

    private static LevelProbe probe(Minecraft client) {
        dev.blockboost.client.ClientServices services = BlockBoostClient.services();
        if (services == null) {
            return LevelProbe.NULL;
        }
        LevelProbe probe = services.levelProbe();
        return probe == null ? LevelProbe.NULL : probe;
    }

    /** @return true when this particle may be created (budget permitting). */
    public static boolean takeParticleSlot() {
        if (particleBudget <= 0) {
            return true; // feature off: vanilla behaviour, no accounting
        }
        if (particlesLeftThisFrame > 0) {
            particlesLeftThisFrame--;
            return true;
        }
        suppressedParticles++;
        return false;
    }

    public static int uploadBudgetThisFrame() {
        if (!chunkPipeline || uploadBudget <= 0) {
            return Integer.MAX_VALUE;
        }
        if (backlogGuard && chunkStats != null && chunkStats.queueDepth() > 64) {
            // Backed up: push what is already built before building more.
            return Math.max(uploadBudget, 32);
        }
        return uploadBudget;
    }

    public static String describeCulling() {
        AsyncVisibilityEngine engine = visibility;
        return "entities culled=" + culledEntities + " block entities culled=" + culledBlockEntities
                + (engine == null ? "" : " " + engine.describe());
    }

    public static void logConfigIssue(SettingKey key, Throwable t) {
        BlockBoost.LOG.warn("[BlockBoost] setting {} could not be applied", key.path(), t);
    }
}
