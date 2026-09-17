package io.github.minatoai.modtest.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * task-66 P7: one {@code ticks:N} request must produce exactly N writes, and no budget may survive
 * into a later ticket. Real-machine evidence: 106 and ~62 audit lines for a 20-tick request, with
 * movement lasting &gt; 11.8 s for a ~1 s request.
 *
 * <p>{@link #theOldSelfRearmingDesignExceedsItsBudget()} reproduces the old behaviour as a test
 * double, so "attribution total &gt; N" is a red test on the old design and a green one here — instead
 * of a defect only a real client could reveal.
 */
class InputHoldTest {

    private static Guard.InputCommand command() {
        return new Guard.InputCommand(1.0f, 0.0f, 0.0f, 0.0f, false, false, false);
    }

    @Test
    void writesExactlyTheRequestedNumberOfTicks() {
        InputHold hold = new InputHold();
        hold.install("t1", command(), 20);

        int writes = 0;
        for (int tick = 0; tick < 30; tick++) {
            if (hold.nextWrite() != null) {
                writes++;
            }
        }
        assertEquals(20, writes, "a ticks:20 request must write exactly 20 times");
        assertEquals(20, hold.attributedWrites("t1"));
        assertEquals(0, hold.remaining());
        assertNull(hold.ticketId(), "an expired hold must leave nothing behind");
    }

    @Test
    void aNewTicketReplacesTheHoldInsteadOfExtendingIt() {
        InputHold hold = new InputHold();
        hold.install("t1", command(), 20);
        for (int i = 0; i < 5; i++) {
            hold.nextWrite();
        }

        hold.install("t2", command(), 3);   // a new ticket must not inherit or extend t1's budget
        int writes = 0;
        for (int i = 0; i < 10; i++) {
            if (hold.nextWrite() != null) {
                writes++;
            }
        }
        assertEquals(3, writes);
        assertEquals(3, hold.attributedWrites("t2"));
        assertEquals(5, hold.attributedWrites("t1"), "t1 keeps exactly what it wrote, no more");
    }

    @Test
    void aRefusalOrNoopEndsTheHoldWithoutLeakingBudget() {
        InputHold hold = new InputHold();
        hold.install("t1", command(), 20);
        for (int i = 0; i < 5; i++) {
            hold.nextWrite();
        }
        hold.clear();                        // the tick was refused / was a no-op
        for (int i = 0; i < 10; i++) {
            assertNull(hold.nextWrite(), "no write may happen after a refusal cleared the hold");
        }
        assertEquals(5, hold.attributedWrites("t1"));

        hold.install("t2", command(), 3);    // a later ticket starts from its OWN budget
        int writes = 0;
        for (int i = 0; i < 3; i++) {
            if (hold.nextWrite() != null) {
                writes++;
            }
        }
        assertEquals(3, writes);
        assertEquals(3, hold.attributedWrites("t2"));
    }

    @Test
    void clearingAnEmptyHoldIsHarmless() {
        InputHold hold = new InputHold();
        hold.clear();
        hold.clear();
        assertNull(hold.nextWrite());
        assertEquals(0, hold.remaining());
    }

    @Test
    void aHoldMustBeAttributedAndBounded() {
        InputHold hold = new InputHold();
        assertThrows(IllegalArgumentException.class, () -> hold.install("", command(), 5));
        assertThrows(IllegalArgumentException.class, () -> hold.install("t", command(), 0));
        assertThrows(IllegalArgumentException.class, () -> hold.install("t", null, 5));
    }

    // ---- the old design, as a test double -----------------------------------------------------
    /**
     * The previous shape: two independent atomics plus a writer callback that re-armed the pending
     * command on every write. A later ticket's install only overwrote the tick counter, so the
     * re-armed command kept being picked up and the budget was repeatedly topped up.
     */
    static final class LegacyHold {
        private final AtomicReference<Guard.InputCommand> pending = new AtomicReference<>();
        private final AtomicInteger ticks = new AtomicInteger();

        /** {@code queueInput}: arm the command and set the budget. */
        void install(int n) {
            pending.set(command());
            ticks.set(n - 1);
        }

        /** The writer delegate: every single write re-arms the pending command. */
        private void onWrite(Guard.InputCommand c) {
            pending.set(c);
        }

        /** {@code onAiStepAfterInput}: take, submit (which re-arms via the delegate), maybe re-arm. */
        boolean tick() {
            Guard.InputCommand taken = pending.getAndSet(null);
            if (taken == null) {
                return false;
            }
            onWrite(taken);                       // the delegate re-arms it — the root cause
            int remaining = ticks.getAndSet(0);
            if (remaining > 0) {
                ticks.set(remaining - 1);
            }
            return true;
        }
    }

    @Test
    void theOldSelfRearmingDesignExceedsItsBudget() {
        LegacyHold legacy = new LegacyHold();
        legacy.install(20);
        int writes = 0;
        for (int i = 0; i < 120; i++) {           // 120 ticks of "later tickets' windows"
            if (legacy.tick()) {
                writes++;
            }
        }
        // This is the defect: the request was 20 ticks, but writes keep happening because every
        // write re-arms the command. The test asserts the OLD design misbehaves, so the fixed
        // design's "exactly N" assertion above is meaningful.
        org.junit.jupiter.api.Assertions.assertTrue(writes > 20,
                "the legacy design is expected to exceed its budget; it wrote " + writes);

        InputHold fixed = new InputHold();
        fixed.install("t1", command(), 20);
        int fixedWrites = 0;
        for (int i = 0; i < 120; i++) {
            if (fixed.nextWrite() != null) {
                fixedWrites++;
            }
        }
        assertEquals(20, fixedWrites, "the fixed design must stop exactly at its budget");
        assertEquals(20, fixed.attributedWrites("t1"));
    }
}
