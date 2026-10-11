package dev.blockboost.client.services;

import java.util.ArrayList;
import java.util.List;

/**
 * Counters for the client chunk pipeline.
 *
 * <p>Written from the render thread and from the mesh workers; every field is a
 * plain {@code long} (monotonic) or a volatile gauge, so an increment costs one
 * memory write and there is never a lock in a hot path. Slight inaccuracy in a
 * diagnostic counter is acceptable and is exactly why this is not atomic-heavy.
 *
 * <p>Why these numbers exist: on a phone, "the game feels slow while loading" is
 * almost always a queue problem, not a triangle problem. Without queue depth and
 * build latency nobody can tell the difference, including me when I read a report.
 */
public final class ChunkPipelineStats {

    // Monotonic totals.
    private volatile long sectionsBuilt;
    private volatile long sectionsUploaded;
    private volatile long requestsCoalesced;
    private volatile long requestsDeferred;
    private volatile long buildNanosTotal;
    private volatile long uploadNanosTotal;
    private volatile long rebuildsAvoided;
    private volatile long staleJobCancelled;

    // Gauges.
    private volatile int queueDepth;
    private volatile int uploadQueueDepth;
    private volatile int activeBuilders;
    private volatile int workerLimit;
    private volatile int sectionsPerFrameLast;
    private volatile double lastBuildMs;
    private volatile double worstBuildMs;

    /** Called by the mesh task hook; must be allocation free. */
    public void noteBuild(long nanos, boolean deduplicated) {
        sectionsBuilt++;
        buildNanosTotal += nanos;
        lastBuildMs = nanos / 1_000_000.0;
        if (lastBuildMs > worstBuildMs) {
            worstBuildMs = lastBuildMs;
        }
        if (deduplicated) {
            requestsCoalesced++;
        }
    }

    public void noteUpload(long nanos) {
        sectionsUploaded++;
        uploadNanosTotal += nanos;
    }

    public void noteDeferred() {
        requestsDeferred++;
    }

    public void noteRebuildAvoided() {
        rebuildsAvoided++;
    }

    public void noteCancelledStale() {
        staleJobCancelled++;
    }

    public void setGauges(int queue, int uploadQueue, int builders, int workerLimit) {
        this.queueDepth = queue;
        this.uploadQueueDepth = uploadQueue;
        this.activeBuilders = builders;
        this.workerLimit = workerLimit;
    }

    public void noteSectionsUploadedThisFrame(int n) {
        this.sectionsPerFrameLast = n;
    }

    public long sectionsBuilt() {
        return sectionsBuilt;
    }

    public long sectionsUploaded() {
        return sectionsUploaded;
    }

    public long requestsCoalesced() {
        return requestsCoalesced;
    }

    public long requestsDeferred() {
        return requestsDeferred;
    }

    public long rebuildsAvoided() {
        return rebuildsAvoided;
    }

    public long cancelledStale() {
        return staleJobCancelled;
    }

    public int queueDepth() {
        return queueDepth;
    }

    public int uploadQueueDepth() {
        return uploadQueueDepth;
    }

    public int workerLimit() {
        return workerLimit;
    }

    public int activeBuilders() {
        return activeBuilders;
    }

    public double averageBuildMs() {
        long n = sectionsBuilt;
        return n == 0 ? 0 : buildNanosTotal / (double) n / 1_000_000.0;
    }

    public double averageUploadMs() {
        long n = sectionsUploaded;
        return n == 0 ? 0 : uploadNanosTotal / (double) n / 1_000_000.0;
    }

    public double worstBuildMs() {
        return worstBuildMs;
    }

    public int sectionsPerFrameLast() {
        return sectionsPerFrameLast;
    }

    public String hudLine() {
        if (queueDepth == 0 && sectionsBuilt == 0) {
            return null; // nothing to say
        }
        return String.format(java.util.Locale.ROOT, "chunks q=%d up=%d built=%d coalesced=%d",
                queueDepth, uploadQueueDepth, sectionsBuilt, requestsCoalesced);
    }

    public List<String> reportLines() {
        List<String> out = new ArrayList<>();
        out.add(String.format(java.util.Locale.ROOT, "chunk sections built=%d uploaded=%d avgBuild=%.2fms worstBuild=%.2fms avgUpload=%.3fms",
                sectionsBuilt, sectionsUploaded, averageBuildMs(), worstBuildMs, averageUploadMs()));
        out.add(String.format(java.util.Locale.ROOT, "chunk queues: build=%d upload=%d workers=%d/%d lastFrameUploads=%d",
                queueDepth, uploadQueueDepth, activeBuilders, workerLimit, sectionsPerFrameLast));
        out.add(String.format(java.util.Locale.ROOT, "chunk requests coalesced=%d deferred=%d rebuildsAvoided=%d staleCancelled=%d",
                requestsCoalesced, requestsDeferred, rebuildsAvoided, staleJobCancelled));
        return out;
    }

    public void reset() {
        sectionsBuilt = 0;
        sectionsUploaded = 0;
        requestsCoalesced = 0;
        requestsDeferred = 0;
        buildNanosTotal = 0;
        uploadNanosTotal = 0;
        rebuildsAvoided = 0;
        staleJobCancelled = 0;
        queueDepth = 0;
        uploadQueueDepth = 0;
        lastBuildMs = 0;
        worstBuildMs = 0;
        sectionsPerFrameLast = 0;
    }
}
