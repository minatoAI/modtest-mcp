package io.github.minatoai.modtest.core;

/**
 * A stop request that may have to wait for a <b>safe point</b>, driven one client tick at a time.
 *
 * <p>The two tiers exist because "stop" means different things to a caller. <b>Immediate</b> cancels the
 * injection now and accepts that the player may be left mid-air or against a wall. <b>Safe</b> keeps the
 * injection alive until the player stands on ground outside a wall, and only then cancels — bounded by
 * {@code maxTicks}, because a stop must never be able to wait forever.
 *
 * <p>This lives in core, and is fed one tick at a time, for two reasons:
 * <ul>
 *   <li>the adapter must <b>not block</b> — the relay runs on the client thread, so sleeping there would
 *       freeze the very ticks that change the player's groundedness. The tick loop that already consumes
 *       the hold drives this instead;</li>
 *   <li>the tier rules then become a plain state machine that can be tested without a client: exactly when
 *       a safe stop stops, that it always stops at the bound, and that a newer input command makes the
 *       request <b>superseded</b> rather than stopping a player someone else now owns.</li>
 * </ul>
 *
 * <p>Deliberately holds no reference to {@link InputHold}: cancelling is the caller's action, so "who
 * cancelled what" stays in one place.
 */
public final class StopRequest {

    /** Which promise this request makes. */
    public enum Tier {
        /** Cancel now; the player may end up mid-air or in a wall. */
        IMMEDIATE,
        /** Cancel at the first safe point (on ground, outside a wall), bounded by {@code maxTicks}. */
        SAFE
    }

    private final Tier tier;
    private final int maxTicks;
    private final String reason;
    private int ticksUsed;
    private Boolean atSafePoint;
    private boolean stopped;
    private boolean superseded;
    private boolean boundReached;

    public StopRequest(Tier tier, int maxTicks, String reason) {
        if (tier == null) {
            throw new IllegalArgumentException("a stop request needs a tier");
        }
        this.tier = tier;
        this.maxTicks = Math.max(0, maxTicks);
        this.reason = reason == null || reason.isBlank() ? "input.stop" : reason;
    }

    public Tier tier() {
        return tier;
    }

    public String reason() {
        return reason;
    }

    public int ticksUsed() {
        return ticksUsed;
    }

    /** Whether the player was observed at a safe point: {@code true}/{@code false}, or {@code null}. */
    public Boolean atSafePoint() {
        return atSafePoint;
    }

    /** Whether the injection must be cancelled after this tick. */
    public boolean stopped() {
        return stopped;
    }

    /** Whether another input command took the player while this request was waiting. */
    public boolean superseded() {
        return superseded;
    }

    /**
     * Whether the bound ran out before a safe point was reached. The stop still happens — the receipt has
     * to say it was not at a safe point, rather than quietly pretending the tier was honoured.
     */
    public boolean boundReached() {
        return boundReached;
    }

    public boolean done() {
        return stopped || superseded;
    }

    /** Another input command took the player: this request no longer applies and must not cancel it. */
    public void supersede() {
        this.superseded = true;
    }

    /**
     * One client tick of waiting.
     *
     * @param safeNow {@code true}/{@code false} when this client can judge the player's footing,
     *                {@code null} when it cannot (no player/world) — {@code null} is <b>not</b> safe, and
     *                never ends the wait early on a guess
     * @return whether the injection must be cancelled now
     */
    public boolean tick(Boolean safeNow) {
        if (done()) {
            return false;
        }
        if (safeNow != null) {
            this.atSafePoint = safeNow;
        }
        if (tier == Tier.IMMEDIATE) {
            stopped = true;
            return true;
        }
        ticksUsed++;
        if (Boolean.TRUE.equals(safeNow)) {
            stopped = true;
            return true;
        }
        if (ticksUsed >= maxTicks) {
            // Bounded: the stop always happens. It just does not get to claim it waited for a safe point.
            boundReached = true;
            stopped = true;
            return true;
        }
        return false;
    }
}
