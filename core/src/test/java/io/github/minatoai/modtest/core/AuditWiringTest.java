package io.github.minatoai.modtest.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 regression guard: a production allowance must always leave a trace.
 *
 * <p>The defect this pins down was not "a missing log line" but "the tested path was not the shipped
 * path": production constructed the writer through an overload whose audit sink defaulted to a no-op,
 * so a real client recorded zero {@code ALLOWED-INPUT} lines while every unit test — which passed a
 * sink explicitly — stayed green. The same family produced the Gson NoSuchMethodError (tests compiled
 * against a different library version than the one that ran).
 *
 * <p>Therefore these tests go through {@link Guard.GuardedInputWriter#audited} — the exact factory the
 * Forge entry point uses — and they also assert the class offers no simplified shortcut to drift back
 * to.
 */
class AuditWiringTest {

    static final class RecordingWriter implements Guard.InputWriter {
        int writes;

        @Override
        public void write(Guard.InputCommand command) {
            writes++;
        }
    }

    private static final Protocol.OpSpec INPUT_OP = new Protocol.OpSpec(
            "input.set", "Drive player input", Json.object(), Json.object(),
            List.of(Protocol.Precondition.of("permitted-session")),
            List.of(Protocol.SideEffect.PLAYER_INPUT), "vanilla-client", null, "1.0");

    private static final long NOW = 1_700_000_000_000L;

    /** Exactly how production assembles it: the audited factory, a real policy, a log consumer. */
    private Guard.GuardedInputWriter productionWriter(RecordingWriter writer, List<String> log) {
        Guard.InputInjectionPolicy policy = new Guard.InputInjectionPolicy(
                Guard.HostWhitelist.of("127.0.0.1"), Guard.BuildVariant.GUARDED, "forge-client",
                () -> "minecraft:overworld");
        return Guard.GuardedInputWriter.audited(writer, policy, new Guard.HumanSpeedClamp(), log::add);
    }

    private static Guard.ActivationState armed() {
        return new Guard.ActivationState(true, new Guard.ActivationToken("s3cr3t", NOW + 60_000L));
    }

    // 1) an allowed write ALWAYS produces an audit line ---------------------------------------
    @Test
    void anAllowedWriteAlwaysEmitsExactlyOneAuditLineThroughTheProductionPath() {
        RecordingWriter writer = new RecordingWriter();
        List<String> log = new ArrayList<>();
        Guard.GuardedInputWriter g = productionWriter(writer, log);

        Guard.Decision d = g.submit(Guard.InputCommand.fromParams(
                        com.google.gson.JsonParser.parseString("{\"forward\":0.2}").getAsJsonObject()),
                INPUT_OP, Guard.SessionState.singleplayer(), armed(), Bridge.Clock.fixed(NOW));

        assertTrue(d.allowed() && !d.noop());
        assertEquals(1, writer.writes, "the write itself must still happen");
        assertEquals(1, log.size(), "an allowance MUST leave exactly one audit line");
        String line = log.get(0);
        assertTrue(line.startsWith("ALLOWED-INPUT"), line);
        assertTrue(line.contains("executor=forge-client"), "who: " + line);
        assertTrue(line.contains("host=singleplayer"), "where: " + line);
        assertTrue(line.contains("at=" + NOW), "when: " + line);
        assertTrue(line.contains("op=input.set"), "what: " + line);
        assertTrue(line.contains("cmd=[forward=0.200"), "the command actually written: " + line);
        assertEquals(8, new Guard.ActivationToken("s3cr3t", 0L).fingerprint().length());
        assertTrue(line.contains("token=" + new Guard.ActivationToken("s3cr3t", 0L).fingerprint()),
                "token fingerprint: " + line);
        assertFalse(line.contains("s3cr3t"), "the token VALUE must never be logged: " + line);
        assertEquals(1, g.allowances().size());
    }

    @Test
    void anAllowanceOnADeclaredHostIsAuditedToo() {
        RecordingWriter writer = new RecordingWriter();
        List<String> log = new ArrayList<>();
        Guard.Decision d = productionWriter(writer, log).submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.remote("127.0.0.1:25575"), armed(), Bridge.Clock.fixed(NOW));
        assertTrue(d.allowed());
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("host=127.0.0.1:25575"), log.get(0));
    }

    // 2) refusals and no-ops produce NOTHING --------------------------------------------------
    @Test
    void everyRefusalPathProducesNoAuditLine() {
        List<String> log = new ArrayList<>();
        RecordingWriter writer = new RecordingWriter();
        Guard.GuardedInputWriter g = productionWriter(writer, log);

        // undeclared host
        assertFalse(g.submit(Guard.InputCommand.none(), INPUT_OP, Guard.SessionState.remote("elsewhere.net"),
                armed(), Bridge.Clock.fixed(NOW)).allowed());
        // not armed
        assertFalse(g.submit(Guard.InputCommand.none(), INPUT_OP, Guard.SessionState.singleplayer(),
                Guard.ActivationState.off(), Bridge.Clock.fixed(NOW)).allowed());
        // expired token
        assertFalse(g.submit(Guard.InputCommand.none(), INPUT_OP, Guard.SessionState.singleplayer(),
                new Guard.ActivationState(true, new Guard.ActivationToken("s3cr3t", NOW - 1)),
                Bridge.Clock.fixed(NOW)).allowed());
        // paused / handshake are no-ops, not allowances
        assertTrue(g.submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.of(true, false, true, true, false, null), armed(), Bridge.Clock.fixed(NOW)).noop());
        assertTrue(g.submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.of(true, false, false, true, true, null), armed(), Bridge.Clock.fixed(NOW)).noop());
        // read-only op: allowed by policy but nothing is injected
        Guard.Decision readOnly = g.submit(Guard.InputCommand.none(), new Protocol.OpSpec(
                        "state.query", "read", Json.object(), Json.object(), List.of(),
                        List.of(Protocol.SideEffect.NONE), "vanilla-client", null, "1.0"),
                Guard.SessionState.singleplayer(), armed(), Bridge.Clock.fixed(NOW));
        assertTrue(readOnly.allowed() && readOnly.noop());

        assertEquals(0, g.writesPerformed(), "nothing may reach the client");
        assertEquals(0, writer.writes);
        assertTrue(log.isEmpty(), "a refusal/no-op is not an allowance: " + log);
        assertTrue(g.allowances().isEmpty());
    }

    // 3) there is no silent shortcut left ------------------------------------------------------
    @Test
    void theWriterExposesExactlyOneConstructorSoASilentSinkCannotReturn() {
        Constructor<?>[] ctors = Guard.GuardedInputWriter.class.getDeclaredConstructors();
        assertEquals(1, ctors.length,
                "GuardedInputWriter must have exactly ONE constructor: adding a convenience overload "
                        + "that defaults the audit sink to a no-op is exactly how production silently "
                        + "lost its audit trail (P1)");
        assertEquals(4, ctors[0].getParameterCount());
        assertEquals(Guard.AuditSink.class, ctors[0].getParameterTypes()[3]);
    }

    @Test
    void aNullSinkIsRejectedInsteadOfSilentlyDiscardingAllowances() {
        RecordingWriter writer = new RecordingWriter();
        Guard.InputInjectionPolicy policy = new Guard.InputInjectionPolicy(Guard.HostWhitelist.empty(),
                Guard.BuildVariant.GUARDED, "forge-client", () -> "minecraft:overworld");
        assertThrows(IllegalArgumentException.class, () -> new Guard.GuardedInputWriter(
                writer, policy, new Guard.HumanSpeedClamp(), null));
        assertThrows(IllegalArgumentException.class, () -> Guard.GuardedInputWriter.audited(
                writer, policy, new Guard.HumanSpeedClamp(), null));
    }
}
