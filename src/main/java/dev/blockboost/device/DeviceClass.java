package dev.blockboost.device;

/** Coarse hardware tier used to scale worker counts, queue sizes and caches. */
public enum DeviceClass {
    LOW(1, 64, 2048),
    MID(2, 256, 8192),
    HIGH(4, 1024, 32768),
    UNKNOWN(1, 128, 4096);

    /** Threads BlockBoost is willing to keep busy outside the game thread. */
    public final int workerThreads;
    /** Queue length for background jobs. */
    public final int queueCapacity;
    /** Entries available to the visibility caches. */
    public final int cacheEntries;

    DeviceClass(int workerThreads, int queueCapacity, int cacheEntries) {
        this.workerThreads = workerThreads;
        this.queueCapacity = queueCapacity;
        this.cacheEntries = cacheEntries;
    }

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static DeviceClass fromId(String id) {
        if (id == null) {
            return UNKNOWN;
        }
        return switch (id.toLowerCase(java.util.Locale.ROOT)) {
            case "low" -> LOW;
            case "mid" -> MID;
            case "high" -> HIGH;
            default -> UNKNOWN;
        };
    }

    /**
     * Derives a tier from what the JVM really reports. Deliberately pessimistic:
     * a phone that reports eight cores is not automatically a high-end device,
     * because half of them are efficiency cores and thermal headroom is unknown.
     */
    public static DeviceClass infer(int cores, long maxHeapBytes, boolean mobileArch) {
        long mb = maxHeapBytes / (1024 * 1024);
        if (cores <= 0) {
            return UNKNOWN;
        }
        int score = 0;
        score += cores >= 8 ? 2 : cores >= 6 ? 1 : 0;
        score += mb >= 6144 ? 2 : mb >= 3072 ? 1 : 0;
        if (mobileArch) {
            score -= 1; // big.LITTLE + thermals
        }
        if (score >= 3) {
            return HIGH;
        }
        if (score >= 1) {
            return MID;
        }
        return LOW;
    }
}
