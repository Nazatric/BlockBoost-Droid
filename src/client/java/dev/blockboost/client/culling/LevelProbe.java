package dev.blockboost.client.culling;

/**
 * The only world access shape the visibility code knows about.
 *
 * <p>Keeping it to one method means the ray caster and the occupancy field can be
 * unit tested with a hand-written box, and it makes the threading rule explicit in
 * the type: a {@code LevelProbe} reads live world data and therefore may only be
 * used on the client thread. Worker threads never see one — they only ever see an
 * {@link OccupancyField} snapshot, which is immutable once published.
 */
@FunctionalInterface
public interface LevelProbe {
    /** Reads nothing: everything is non-solid, so nothing gets culled. Used while a
     *  level is unbound or a snapshot is the only data a worker may see. */
    LevelProbe NULL = (x, y, z) -> false;

    /** True when the block at these coordinates is a full opaque cube. */
    boolean isOpaqueCube(int x, int y, int z);
}
