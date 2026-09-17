package io.github.minatoai.modtest.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one place that decides how long an injected input command keeps being written.
 *
 * <p>This lives in core (not in the Forge adapter) because the defect it fixes was only ever visible
 * on a real client: a {@code ticks:20} request kept injecting across several later tickets — measured
 * attribution totals of 106 and ~62 audit lines for a 20-tick request, and ~8 blocks of movement
 * lasting &gt; 11.8 s for a request that should have lasted ~1 s.
 *
 * <p>The old design had two independent atomics plus a writer callback that re-armed the pending
 * command on <b>every</b> write, so a hold could perpetuate itself and a later ticket's install could
 * extend a running hold. Here a hold is a single immutable value:
 *
 * <ul>
 *   <li>{@link #install} <b>replaces</b> any previous hold — a new ticket never extends an old one;</li>
 *   <li>{@link #nextWrite} consumes <b>exactly one</b> tick atomically and clears the hold at expiry,
 *       so a ticket can never produce more writes than it asked for;</li>
 *   <li>{@link #clear} is called explicitly on the other three paths — refusal, no-op, and install —
 *       so no tick budget can survive into a later ticket;</li>
 *   <li>{@link #attributedWrites} records writes per ticket, which makes "one {@code ticks:N} request
 *       produces exactly N writes" an assertable fact rather than a hope.</li>
 * </ul>
 */
public final class InputHold {

    /** One ticket's hold. Immutable, so a decrement is a replace and cannot be observed half-done. */
    public record Hold(String ticketId, Guard.InputCommand command, int remainingTicks) {
    }

    private final AtomicReference<Hold> current = new AtomicReference<>();
    private final Map<String, Integer> attributed = new ConcurrentHashMap<>();

    /** Install a new hold, replacing (never extending) whatever was there. */
    public void install(String ticketId, Guard.InputCommand command, int ticks) {
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("a hold must be attributed to a ticket id");
        }
        if (command == null) {
            throw new IllegalArgumentException("a hold must carry a command");
        }
        if (ticks < 1) {
            throw new IllegalArgumentException("a hold must last at least one tick, got " + ticks);
        }
        current.set(new Hold(ticketId, command, ticks));
    }

    /**
     * Consume exactly one tick's worth of the hold.
     *
     * @return the command to write this tick, or {@code null} when nothing is owed (already expired)
     */
    public Guard.InputCommand nextWrite() {
        Hold taken = current.getAndUpdate(h -> {
            if (h == null) {
                return null;
            }
            int left = h.remainingTicks() - 1;
            return left <= 0 ? null : new Hold(h.ticketId(), h.command(), left);
        });
        if (taken == null) {
            return null;
        }
        attributed.merge(taken.ticketId(), 1, Integer::sum);
        return taken.command();
    }

    /** Explicitly drop the hold: on expiry, on refusal, on a no-op, and on a new install. */
    public void clear() {
        current.set(null);
    }

    /** Ticks still owed (0 when nothing is held). */
    public int remaining() {
        Hold h = current.get();
        return h == null ? 0 : h.remainingTicks();
    }

    /** The ticket the current hold belongs to, or {@code null}. */
    public String ticketId() {
        Hold h = current.get();
        return h == null ? null : h.ticketId();
    }

    /** Writes actually attributed to a ticket — the number that must equal that ticket's ticks. */
    public int attributedWrites(String ticketId) {
        return attributed.getOrDefault(ticketId, 0);
    }
}
