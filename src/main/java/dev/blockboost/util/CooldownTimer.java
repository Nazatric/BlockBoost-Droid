package dev.blockboost.util;

/**
 * Time-gated gate used by the adaptive controller and the thermal guard.
 *
 * <p>Two separate windows matter: a *cooldown* after acting (no further change
 * for N intervals, which is what stops oscillation) and a *confirmation* window
 * (the condition must hold across N consecutive checks before acting, so a
 * single hitch from a chunk burst never changes settings).
 */
public final class CooldownTimer {

    private long lastAction = Long.MIN_VALUE;
    private int streak;
    private int requiredStreak = 3;
    private long cooldown = 200;

    public CooldownTimer(long cooldownIntervals, int requiredStreak) {
        this.cooldown = Math.max(1, cooldownIntervals);
        this.requiredStreak = Math.max(1, requiredStreak);
    }

    public void configure(long cooldownIntervals, int requiredStreak) {
        this.cooldown = Math.max(1, cooldownIntervals);
        this.requiredStreak = Math.max(1, requiredStreak);
    }

    /**
     * @param now      monotonic tick counter
     * @param condition true while the trigger condition holds
     * @param direction +1 or -1; a direction change resets the streak so an
     *                  "increase" cannot inherit evidence gathered for "decrease"
     */
    public boolean shouldAct(long now, boolean condition, int direction) {
        if (!condition) {
            streak = 0;
            return false;
        }
        if (lastAction != Long.MIN_VALUE && now - lastAction < cooldown) {
            return false;
        }
        streak++;
        if (streak >= requiredStreak) {
            streak = 0;
            lastAction = now - direction; // remembered by callers via lastActedTick
            lastAction = now;
            return true;
        }
        return false;
    }

    /** Manual reset, e.g. after the user takes over control. */
    public void reset() {
        streak = 0;
        lastAction = Long.MIN_VALUE;
    }

    public long lastActedTick() {
        return lastAction;
    }

    public int streak() {
        return streak;
    }

    public long cooldown() {
        return cooldown;
    }
}
