package dev.blockboost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.blockboost.culling.LevelProbe;
import dev.blockboost.culling.OccupancyField;
import dev.blockboost.culling.OcclusionRaycaster;
import dev.blockboost.util.LongByteCache;
import dev.blockboost.util.RollingStats;
import dev.blockboost.util.TaskPools;

/**
 * Bounds, eviction and thread behaviour. These are the properties that decide
 * whether a long Android session stays flat or creeps until the launcher kills it,
 * and they are the properties that are easiest to get subtly wrong, so they are
 * pinned here rather than eyeballed in the game.
 */
public class UtilTest {

    @Test
    void longByteCacheNeverExceedsItsCapacity() {
        LongByteCache cache = new LongByteCache(64);
        assertTrue(cache.capacity() >= 64, "capacity must be honoured");
        for (int i = 0; i < 10_000; i++) {
            cache.put(i * 31L, (byte) (i & 0x7F));
            assertTrue(cache.size() <= cache.capacity(),
                    "a cache that grows with the entity count is the bug this class exists to avoid");
        }
        assertEquals(cache.capacity(), cache.size(), "a full fixed table should fill, not stall");
    }

    @Test
    void longByteCacheRoundTripsAndInvalidates() {
        LongByteCache cache = new LongByteCache(16);
        long key = LongByteCache.packPosition(10, 64, -3);
        cache.put(key, (byte) 1);
        assertEquals(1, cache.get(key, -1));
        assertEquals(-1, cache.get(key + 12345, -1), "an absent key must report the miss value");
        cache.invalidate();
        assertEquals(0, cache.size());
        assertEquals(-1, cache.get(key, -1), "invalidate must really drop the entries");
    }

    @Test
    void rollingStatsReportsMonotonicPercentilesAndBoundedWindow() {
        RollingStats stats = new RollingStats(100, 0);
        for (int i = 1; i <= 1000; i++) {
            stats.sample(i);
        }
        assertTrue(stats.samples() <= stats.capacity(),
                "the window must stay bounded: " + stats.samples() + "/" + stats.capacity());
        double p50 = stats.percentileMs(50);
        double p95 = stats.percentileMs(95);
        double p99 = stats.percentileMs(99);
        assertTrue(p50 <= p95 && p95 <= p99, "percentiles must not cross: " + p50 + "," + p95 + "," + p99);
        assertTrue(stats.maxMs() >= stats.meanMs(), "max is at least the mean");
        assertTrue(stats.meanMs() >= stats.minMs(), "mean is at least the min");
        assertTrue(stats.totalSamples() >= 1000, "lifetime count must not be windowed");
    }

    @Test
    void taskPoolDropsInsteadOfGrowingAndSurvivesThrowingTasks() throws Exception {
        TaskPools pools = new TaskPools();
        TaskPools.Pool pool = pools.create("test", 1, 8);
        AtomicInteger ran = new AtomicInteger();
        CountDownLatch blocker = new CountDownLatch(1);
        pool.submit(() -> {
            try {
                blocker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ran.incrementAndGet();
        });
        for (int i = 0; i < 500; i++) {
            pool.submit(() -> ran.incrementAndGet());
        }
        assertTrue(pool.queueLength() <= pool.maxQueueCapacity() + 1,
                "queue must be bounded, was " + pool.queueLength());
        assertTrue(pool.dropped() > 0, "overflow must be counted, not hidden");
        blocker.countDown();
        pool.submit(() -> {
            throw new IllegalStateException("boom");
        });
        CountDownLatch after = new CountDownLatch(1);
        // The throwing task must not kill the worker: this one still runs.
        for (int i = 0; i < 200 && pool.activeThreads() == 0; i++) {
            Thread.sleep(5);
        }
        pool.submit(after::countDown);
        assertTrue(after.await(5, TimeUnit.SECONDS), "a throwing task must not take the pool down");
        pools.close();
        // Not activeThreads(): that is a ThreadPoolExecutor estimate and it lags the real
        // exit of a worker, which made this assertion fire on a loaded CI runner only.
        assertTrue(pool.awaitTermination(3_000), "close must retire the worker threads");
    }

    @Test
    void occupancyFieldIsConservativeAndFailOpen() {
        LevelProbe solid = (x, y, z) -> true;
        LevelProbe empty = (x, y, z) -> false;
        OccupancyField field = new OccupancyField();
        field.recenter(0, 0, 0);
        assertFalse(field.solidAtBlock(0, 0, 0), "an unbuilt field must read as non-solid");

        while (!field.refreshNextSlice(1 << 20, solid)) {
            // bounded by the field itself; no loop here can be infinite
        }
        field.publish();
        assertTrue(field.isComplete());
        assertTrue(field.publishedSolidVoxels() > 0, "an all-solid world must fill the grid");
        assertTrue(field.solidAtBlock(0, 0, 0));
        assertFalse(field.solidAtBlock(1_000_000, 0, 0), "outside the field is unknown, i.e. not solid");

        OccupancyField second = new OccupancyField();
        second.recenter(0, 0, 0);
        while (!second.refreshNextSlice(1 << 20, empty)) {
            // same
        }
        second.publish();
        assertFalse(second.solidAtBlock(0, 0, 0), "an air-only world must stay non-solid");
    }

    @Test
    void raycasterOnlyHidesWhenEveryRayIsBlocked() {
        int wallX = 5;
        // A wall with a top: opaque only in its own band of height, so "a gap above it"
        // is a case the test can actually express.
        LevelProbe wall = (x, y, z) -> x >= wallX - 1 && x <= wallX + 1 && y >= 60 && y <= 70;
        double camX = 0, camY = 65, camZ = 0;

        // Fully behind a thick wall, both corners and the centre blocked.
        assertTrue(OcclusionRaycaster.boxHidden(wall, camX, camY, camZ,
                        wallX + 6, 64.5, -0.4, wallX + 7, 65.5, 0.4, 256),
                "a box sealed behind thick terrain must count as hidden");

        // Same distance, but the box strays above the wall: a gap means it is visible.
        assertFalse(OcclusionRaycaster.boxHidden(wall, camX, camY, camZ,
                        wallX + 6, 200.5, -0.4, wallX + 7, 201.5, 0.4, 256),
                "one unblocked ray must be enough to render");

        // Beyond the configured distance we stop asking: visible, always.
        assertFalse(OcclusionRaycaster.boxHidden(wall, camX, camY, camZ,
                        400, 65, 0, 401, 66, 1, 64), "max distance must short-circuit the test");

        // Degenerate boxes must not hang or throw.
        assertFalse(OcclusionRaycaster.boxHidden(wall, camX, camY, camZ, camX, camY, camZ, camX, camY, camZ, 64));
        assertFalse(OcclusionRaycaster.boxHidden(wall, Double.NaN, 0, 0, 1, 1, 1, 2, 2, 2, 64));
    }

    @Test
    void raycasterTerminatesOnAbsurdGeometry() {
        LevelProbe solid = (x, y, z) -> true;
        long start = System.nanoTime();
        boolean hidden = OcclusionRaycaster.boxHidden(solid, 0, 0, 0, 1e9, 1e9, 1e9, 1e9 + 1, 1e9 + 1, 1e9 + 1, 0);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        // The value itself is meaningless here (any answer is a legal answer to absurd
        // geometry); reaching this line at all is the assertion.
        assertTrue(elapsedMs >= 0, "returned: hidden=" + hidden);
        assertTrue(elapsedMs < 500, "a ray test must be bounded by a step cap, took " + elapsedMs + " ms");
    }
}
