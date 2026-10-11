package dev.blockboost.client.services;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * Garbage collection watcher built on the JDK's own GC notification MBean.
 *
 * <p>Read-only on purpose. BlockBoost never calls {@code System.gc()}: forcing a
 * collection to make a benchmark look better (or to "free memory" for the user)
 * is a stall you chose to hide rather than fix. What this class does is make the
 * stalls visible — count, total pause time, worst pause, and which collector
 * paused — so a hitch can be attributed instead of guessed at.
 *
 * <p>Reference types here are resolved lazily by the VM and everything is wrapped
 * in {@code catch (Throwable)}: on a JVM without {@code jdk.management}'s
 * com.sun.management extensions the monitor reports MXBean counters only, which
 * the report says out loud instead of pretending there were no pauses.
 */
public final class GcMonitor implements AutoCloseable {

    /** One recorded collection event. */
    public record Pause(String collector, String action, long durationMs, long atMillis, String cause) {
    }

    private static final int RECENT_CAP = 32;

    private final List<Pause> recent = new CopyOnWriteArrayList<>();
    private final List<GarbageCollectorMXBean> beans;
    private final boolean[] listenerInstalled = {false};
    private volatile long totalPauses;
    private volatile long totalPauseMs;
    private volatile long worstPauseMs;
    private volatile long lastPauseMs;
    private volatile long thresholdMs = 50;
    private volatile boolean notificationSupported;
    private NotificationListener listener;

    public GcMonitor() {
        this.beans = new ArrayList<>(ManagementFactory.getGarbageCollectorMXBeans());
        attach();
    }

    public void setReportThresholdMs(long ms) {
        this.thresholdMs = Math.max(0, ms);
    }

    private void attach() {
        try {
            listener = (n, hb) -> {
                if (!(hb instanceof CompositeData data)) {
                    return;
                }
                try {
                    com.sun.management.GarbageCollectionNotificationInfo info =
                            com.sun.management.GarbageCollectionNotificationInfo.from(data);
                    var gc = info.getGcInfo();
                    long duration = gc.getDuration();
                    totalPauses++;
                    totalPauseMs += duration;
                    if (duration > worstPauseMs) {
                        worstPauseMs = duration;
                    }
                    lastPauseMs = duration;
                    if (duration >= thresholdMs) {
                        recent.add(0, new Pause(safe(gc.getGcName()), shortAction(info.getGcAction()), duration,
                                System.currentTimeMillis(), shortCause(info.getGcCause())));
                        while (recent.size() > RECENT_CAP) {
                            recent.remove(recent.size() - 1);
                        }
                    }
                } catch (Throwable ignored) {
                    // A malformed notification must never disturb the game thread.
                }
            };
            for (GarbageCollectorMXBean bean : beans) {
                if (bean instanceof com.sun.management.GarbageCollectorMXBean ext) {
                    ext.addNotificationListener(listener, null, null);
                    notificationSupported = true;
                }
            }
            listenerInstalled[0] = notificationSupported;
        } catch (Throwable t) {
            // Not fatal, and deliberately logged once at startup instead of per frame.
            dev.blockboost.BlockBoost.LOG.info("[BlockBoost] GC notifications unavailable ({}); "
                    + "falling back to MXBean counters only", t.getClass().getSimpleName());
        }
    }

    private static String shortAction(String action) {
        if (action == null) {
            return "gc";
        }
        return action.toLowerCase(java.util.Locale.ROOT).contains("minor") ? "young"
                : action.toLowerCase(java.util.Locale.ROOT).contains("major") ? "old" : action;
    }

    private static String shortCause(String cause) {
        if (cause == null) {
            return "";
        }
        int i = cause.indexOf('(');
        return i > 0 ? cause.substring(0, i).trim() : cause;
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s;
    }

    /** Fallback counters; also useful when notifications are supported. */
    public long collectedCount() {
        long n = 0;
        for (GarbageCollectorMXBean b : beans) {
            long c = b.getCollectionCount();
            if (c > 0) {
                n += c;
            }
        }
        return n;
    }

    public long collectedTimeMs() {
        long n = 0;
        for (GarbageCollectorMXBean b : beans) {
            long c = b.getCollectionTime();
            if (c > 0) {
                n += c;
            }
        }
        return n;
    }

    public long pauseCount() {
        return notificationSupported ? totalPauses : collectedCount();
    }

    public long totalPauseMs() {
        return notificationSupported ? totalPauseMs : collectedTimeMs();
    }

    public long worstPauseMs() {
        return worstPauseMs;
    }

    public long lastPauseMs() {
        return lastPauseMs;
    }

    public boolean notificationsSupported() {
        return notificationSupported;
    }

    public List<Pause> recentPauses() {
        return List.copyOf(recent);
    }

    public void reset() {
        recent.clear();
        totalPauses = 0;
        totalPauseMs = 0;
        worstPauseMs = 0;
        lastPauseMs = 0;
    }

    public String hudLine() {
        return "gc " + pauseCount() + "x " + totalPauseMs() + "ms"
                + (worstPauseMs > 0 ? " worst " + worstPauseMs + "ms" : "")
                + (notificationSupported ? "" : " (counters only)");
    }

    public List<String> reportLines() {
        List<String> out = new ArrayList<>();
        StringBuilder names = new StringBuilder();
        for (GarbageCollectorMXBean b : beans) {
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(b.getName());
        }
        out.add("gc collectors: " + (names.isEmpty() ? "none reported" : names));
        out.add("gc events=" + pauseCount() + " totalPause=" + totalPauseMs() + "ms worst=" + worstPauseMs
                + "ms last=" + lastPauseMs + "ms notificationApi="
                + (notificationSupported ? "yes" : "no (MXBean counters only)"));
        if (!recent.isEmpty()) {
            out.add("recent pauses over " + thresholdMs + "ms:");
            int i = 0;
            for (Pause p : recent) {
                if (i++ >= 8) {
                    break;
                }
                out.add(String.format("   %4d ms  %-20s %-6s %s", p.durationMs(), p.collector(), p.action(), p.cause()));
            }
        }
        Runtime rt = Runtime.getRuntime();
        long mb = 1024 * 1024;
        out.add("heap used=" + (rt.totalMemory() - rt.freeMemory()) / mb + "MB committed=" + rt.totalMemory() / mb
                + "MB max=" + rt.maxMemory() / mb + "MB (BlockBoost never overrides -Xmx; see docs/RECOMMENDED-HEAP.md)");
        return out;
    }

    @Override
    public void close() {
        if (!listenerInstalled[0]) {
            return;
        }
        for (GarbageCollectorMXBean bean : beans) {
            if (bean instanceof com.sun.management.GarbageCollectorMXBean ext && listener != null) {
                try {
                    ext.removeNotificationListener(listener);
                } catch (Exception ignored) {
                    // Shutdown noise only.
                }
            }
        }
    }
}
