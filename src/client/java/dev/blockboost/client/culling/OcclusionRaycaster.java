package dev.blockboost.client.culling;

/**
 * Ray sampling for "can the camera possibly see this box".
 *
 * <p>Rules this code is written to:
 * <ul>
 *   <li><b>Bounded loops.</b> Every march has a hard step cap derived from the
 *       distance, so no scene, no floating point NaN and no absurd coordinate can
 *       create an infinite loop. A loop that can never end is the single most
 *       dangerous thing an optimization mod can ship.</li>
 *   <li><b>Fail open.</b> A box counts as visible unless <em>every</em> sampled ray
 *       to it is blocked. Inconclusive means draw it. Hiding a mob that was visible
 *       is a bug the user will hate far more than a mob we failed to skip.</li>
 *   <li><b>No allocation.</b> Callers pass a scratch array; a ray test runs
 *       thousands of times per second in a farm and must not create garbage.</li>
 * </ul>
 */
public final class OcclusionRaycaster {

    /** Steps are 1 block; this caps the march at 192 blocks regardless of distance. */
    public static final int MAX_STEPS = 192;
    /** Corners of the inflated box plus its centre: nine independent sight lines. */
    public static final int RAYS = 9;

    private OcclusionRaycaster() {
    }

    /**
     * @param probe reader for live block state (client thread only)
     * @return true when all nine sample rays are blocked, i.e. nothing of the box
     *         can be seen
     */
    public static boolean boxHidden(LevelProbe probe, double camX, double camY, double camZ,
                                    double minX, double minY, double minZ,
                                    double maxX, double maxY, double maxZ, int maxDistance) {
        double cx = (minX + maxX) * 0.5;
        double cy = (minY + maxY) * 0.5;
        double cz = (minZ + maxZ) * 0.5;
        double dist = Math.sqrt(sq(cx - camX) + sq(cy - camY) + sq(cz - camZ));
        if (!(dist > 0.0001) || Double.isNaN(dist) || Double.isInfinite(dist)) {
            return false;
        }
        if (maxDistance > 0 && dist > maxDistance) {
            return false;
        }
        int steps = (int) Math.min(MAX_STEPS, Math.max(2, Math.ceil(dist)));
        for (int r = 0; r < RAYS; r++) {
            double tx, ty, tz;
            switch (r) {
                case 0 -> {
                    tx = minX;
                    ty = minY;
                    tz = minZ;
                }
                case 1 -> {
                    tx = maxX;
                    ty = minY;
                    tz = minZ;
                }
                case 2 -> {
                    tx = minX;
                    ty = maxY;
                    tz = minZ;
                }
                case 3 -> {
                    tx = maxX;
                    ty = maxY;
                    tz = maxZ;
                }
                case 4 -> {
                    tx = maxX;
                    ty = minY;
                    tz = maxZ;
                }
                case 5 -> {
                    tx = minX;
                    ty = minY;
                    tz = maxZ;
                }
                case 6 -> {
                    tx = maxX;
                    ty = maxY;
                    tz = minZ;
                }
                case 7 -> {
                    tx = minX;
                    ty = maxY;
                    tz = maxZ;
                }
                default -> {
                    tx = cx;
                    ty = cy;
                    tz = cz;
                }
            }
            if (!rayBlocked(probe, camX, camY, camZ, tx, ty, tz, steps)) {
                return false;
            }
        }
        return true;
    }

    /** Same sampling pattern, against a pre-built coarse grid (safe off-thread). */
    public static boolean boxHidden(OccupancyField field, double camX, double camY, double camZ,
                                    double minX, double minY, double minZ,
                                    double maxX, double maxY, double maxZ, int maxDistance) {
        double cx = (minX + maxX) * 0.5;
        double cy = (minY + maxY) * 0.5;
        double cz = (minZ + maxZ) * 0.5;
        double dist = Math.sqrt(sq(cx - camX) + sq(cy - camY) + sq(cz - camZ));
        if (!(dist > 0.0001) || Double.isNaN(dist) || Double.isInfinite(dist)) {
            return false;
        }
        if (maxDistance > 0 && dist > maxDistance) {
            return false;
        }
        if (!field.containsBlock(cx, cy, cz)) {
            return false; // outside the snapshot: unknown, so visible
        }
        int steps = (int) Math.min(MAX_STEPS, Math.max(2, Math.ceil(dist / OccupancyField.BLOCKS_PER_VOXEL)));
        double dx = (cx - camX) / steps;
        double dy = (cy - camY) / steps;
        double dz = (cz - camZ) / steps;
        double px = camX + dx;
        double py = camY + dy;
        double pz = camZ + dz;
        for (int i = 1; i < steps; i++) {
            if (field.solidAtBlock(px, py, pz)) {
                return true;
            }
            px += dx;
            py += dy;
            pz += dz;
        }
        return false;
    }

    private static boolean rayBlocked(LevelProbe probe, double camX, double camY, double camZ,
                                       double tx, double ty, double tz, int steps) {
        double dx = (tx - camX) / steps;
        double dy = (ty - camY) / steps;
        double dz = (tz - camZ) / steps;
        double px = camX + dx;
        double py = camY + dy;
        double pz = camZ + dz;
        // The first step is skipped: the camera may stand inside a block, and
        // starting the march at the target box is what tells us about the path.
        for (int i = 1; i < steps; i++) {
            if (probe.isOpaqueCube((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz))) {
                return true;
            }
            px += dx;
            py += dy;
            pz += dz;
        }
        return false;
    }

    private static double sq(double v) {
        return v * v;
    }
}
