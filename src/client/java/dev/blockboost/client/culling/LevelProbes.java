package dev.blockboost.client.culling;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import dev.blockboost.BlockBoost;
import dev.blockboost.culling.LevelProbe;

/**
 * Builds the one world query the culling code needs: "is this position a full opaque
 * cube?".
 *
 * <p>The predicate is looked up by name from a short list of candidates rather than
 * called directly. That is not cleverness for its own sake: the method that answers
 * this question has been renamed in most Minecraft versions since it existed
 * ({@code isSolidRender}, {@code canOcclude}, {@code isOpaque} have each been current at
 * some point), and a mod that hardcodes one of them either fails to compile on the next
 * update or, worse, ships a culling path that quietly never works. Here the lookup
 * happens once, at level bind time, and the result is reported:
 *
 * <ul>
 *   <li>found → occlusion culling is available;</li>
 *   <li>not found → occlusion is <b>disabled</b>, the subsystem falls back to vanilla's
 *       frustum test only, and the reason appears in the settings screen and the report.</li>
 * </ul>
 *
 * <p>The failure direction is deliberate. A missing method costs some skipped
 * optimization; a wrong assumption about block opacity would cost visible entities.
 */
public final class LevelProbes {

    /** Candidate predicates, in order of preference: name and total parameter count. */
    private static final String[][] CANDIDATES = {
            {"isSolidRender", "3"},
            {"canOcclude", "1"},
            {"isOpaque", "1"},
    };

    public record Probe(LevelProbe probe, String description, boolean available) {
    }

    private static final Probe UNAVAILABLE = new Probe((x, y, z) -> false,
            "no known block-opacity query in this build; occlusion disabled, frustum only", false);

    private LevelProbes() {
    }

    /**
     * Builds the probe for one level. Not called per frame: {@code ClientServices}
     * rebinds only when the level object changes, and the returned lambda closes over
     * that level.
     */
    public static Probe forLevel(ClientLevel level) {
        if (level == null) {
            return UNAVAILABLE;
        }
        Found found = findOpacityPredicate();
        if (found == null) {
            return UNAVAILABLE;
        }
        MethodHandle handle = found.handle();
        String name = found.name();
        final ClientLevel target = level;
        // One mutable position for the whole session: the block-state query only reads
        // its x/y/z, and a per-probe BlockPos would be the single largest source of
        // garbage in the culling path (a few hundred thousand objects a second in a
        // dense scene, which is precisely the stutter this subsystem must not add).
        final net.minecraft.core.BlockPos.MutableBlockPos scratch =
                new net.minecraft.core.BlockPos.MutableBlockPos();
        LevelProbe probe = (x, y, z) -> {
            try {
                BlockPos pos = scratch.set((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                BlockState state = target.getBlockState(pos);
                Object result = state == null ? Boolean.FALSE : handle.invoke(state, target, pos);
                return result instanceof Boolean b && b;
            } catch (Throwable t) {
                // Any doubt at all reads as "not solid", which means "do not cull".
                return false;
            }
        };
        return new Probe(probe, "BlockState#" + name + "()", true);
    }

    private record Found(MethodHandle handle, String name) {
    }

    private static Found findOpacityPredicate() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            for (Method method : BlockState.class.getMethods()) {
                for (String[] candidate : CANDIDATES) {
                    if (!method.getName().equals(candidate[0])) {
                        continue;
                    }
                    if (method.getParameterCount() + 1 != Integer.parseInt(candidate[1])) {
                        continue;
                    }
                    if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) {
                        continue;
                    }
                    Class<?>[] params = method.getParameterTypes();
                    // Accept either the (state) form or the (state, level, pos) form; the
                    // call site passes all three and dropArguments fixes it up.
                    MethodHandle handle = lookup.unreflect(method);
                    if (params.length == 0) {
                        handle = MethodHandles.dropArguments(handle, 1, Object.class, Object.class);
                    } else if (params.length == 1) {
                        handle = MethodHandles.dropArguments(handle, 2, Object.class);
                    }
                    BlockBoost.logInfo("[BlockBoost] block opacity query: " + method.getName()
                            + "(" + params.length + " args)");
                    return new Found(handle, method.getName());
                }
            }
        } catch (Throwable t) {
            BlockBoost.LOG.debug("[BlockBoost] opacity predicate lookup failed", t);
        }
        return null;
    }
}
