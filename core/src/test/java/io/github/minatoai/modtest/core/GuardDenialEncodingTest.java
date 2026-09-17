package io.github.minatoai.modtest.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-b regression guard: a guard refusal must be encoded as a failed op, not as a successful one.
 *
 * <p>Before this, the two refusal paths disagreed — one produced {@code ok:false} +
 * {@code E_PRECONDITION} while the guard path produced {@code ok:true} with {@code allowed:false}.
 * Read the natural way ("every op ok ⇒ the ticket succeeded"), a refused injection looked like a
 * successful one. {@code Guard.requireAllowed} is the single encoding site and is unit-tested here.
 */
class GuardDenialEncodingTest {

    @Test
    void aRefusalThrowsSoTheOpCannotBeReportedAsSuccessful() {
        Guard.Decision denied = Guard.Decision.deny("injection is off by default (dev flag not set)");

        Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                () -> Guard.requireAllowed(denied, "input.set"));

        assertEquals(Protocol.ErrorCode.E_PRECONDITION, e.code(),
                "a guard refusal stays inside the existing stable code vocabulary");
        String msg = e.getMessage();
        assertTrue(msg.contains("input.set refused by the guard"), msg);
        assertTrue(msg.contains("allowed=false"), "the detail must survive in the receipt: " + msg);
        assertTrue(msg.contains("queued=false"), msg);
        assertTrue(msg.contains("injection is off by default"), msg);
    }

    @Test
    void anAllowedDecisionDoesNotThrow() {
        assertDoesNotThrow(() -> Guard.requireAllowed(
                Guard.Decision.allow("single-player world"), "input.set"));
    }

    @Test
    void aNoopIsNotARefusalBecauseTheOpDidExecute() {
        // paused / handshake: the op ran and decided there was nothing to do — ok:true + noop:true.
        assertDoesNotThrow(() -> Guard.requireAllowed(
                Guard.Decision.noop("client paused"), "input.set"));
    }

    @Test
    void theEncodingIsStableForEveryRefusalReasonThePolicyCanProduce() {
        Guard.InputInjectionPolicy policy = new Guard.InputInjectionPolicy(Guard.HostWhitelist.empty(),
                Guard.BuildVariant.GUARDED, "forge-client", () -> "minecraft:overworld");
        Bridge.Clock clock = Bridge.Clock.fixed(1_700_000_000_000L);
        Protocol.OpSpec input = new Protocol.OpSpec("input.set", "in", Json.object(), Json.object(),
                java.util.List.of(), java.util.List.of(Protocol.SideEffect.PLAYER_INPUT),
                "forge-client", null, "1.0");

        // not armed
        Guard.Decision d1 = policy.decide(input, Guard.SessionState.singleplayer(),
                Guard.ActivationState.off(), clock);
        // expired token
        Guard.Decision d2 = policy.decide(input, Guard.SessionState.singleplayer(),
                new Guard.ActivationState(true, new Guard.ActivationToken("t", clock.nowMs() - 1)), clock);
        // undeclared host
        Guard.Decision d3 = policy.decide(input, Guard.SessionState.remote("elsewhere.net"),
                new Guard.ActivationState(true, new Guard.ActivationToken("t", clock.nowMs() + 1000)), clock);

        for (Guard.Decision d : java.util.List.of(d1, d2, d3)) {
            Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                    () -> Guard.requireAllowed(d, "input.set"), d.reason());
            assertEquals(Protocol.ErrorCode.E_PRECONDITION, e.code());
            assertTrue(e.getMessage().contains(d.reason()), e.getMessage());
        }
    }
}
