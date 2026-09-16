package io.github.minatoai.modtest.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code input.set} parameter plumbing.
 *
 * <p>Regression guard for the P0-class defect "the op ignores its parameters": the ticket's params
 * must reach the writer, an empty params object must stay a no-op command (old behaviour), the
 * three-tier guard must still fail closed, and clamping must still apply.
 */
class InputCommandParamsTest {

    static final class RecordingWriter implements Guard.InputWriter {
        int writes;
        final List<Guard.InputCommand> commands = new ArrayList<>();

        @Override
        public void write(Guard.InputCommand command) {
            writes++;
            commands.add(command);
        }
    }

    private static final Protocol.OpSpec INPUT_OP = new Protocol.OpSpec(
            "input.set", "Drive player input", Json.object(), Json.object(),
            List.of(Protocol.Precondition.of("permitted-session")),
            List.of(Protocol.SideEffect.PLAYER_INPUT), "vanilla-client", null, "1.0");

    private static final long NOW = 1_700_000_000_000L;

    private RecordingWriter writer;
    private List<String> audit;
    private Bridge.Clock clock;

    @BeforeEach
    void setUp() {
        writer = new RecordingWriter();
        audit = new ArrayList<>();
        clock = Bridge.Clock.fixed(NOW);
    }

    private static JsonObject params(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private Guard.GuardedInputWriter guarded(String... hosts) {
        return new Guard.GuardedInputWriter(writer,
                new Guard.InputInjectionPolicy(Guard.HostWhitelist.of(hosts), Guard.BuildVariant.GUARDED,
                        "unit-test", () -> "minecraft:overworld"),
                new Guard.HumanSpeedClamp(), Guard.AuditSink.to(audit::add));
    }

    private Guard.ActivationState armed() {
        return new Guard.ActivationState(true, new Guard.ActivationToken("tok", NOW + 60_000L));
    }

    // 1) params -> InputCommand ---------------------------------------------------------------
    @Test
    void paramsBecomeTheCommandThatIsWritten() {
        Guard.InputCommand cmd = Guard.InputCommand.fromParams(params(
                "{\"forward\":0.15,\"strafe\":-0.05,\"yawDelta\":2.5,\"pitchDelta\":-1.25,"
                        + "\"jump\":true,\"sneak\":false,\"sprint\":true}"));

        assertEquals(0.15f, cmd.forward(), 1e-6);
        assertEquals(-0.05f, cmd.strafe(), 1e-6);
        assertEquals(2.5f, cmd.yawDelta(), 1e-6);
        assertEquals(-1.25f, cmd.pitchDelta(), 1e-6);
        assertTrue(cmd.jump());
        assertFalse(cmd.sneak());
        assertTrue(cmd.sprint());

        Guard.GuardedInputWriter g = guarded("127.0.0.1");
        Guard.Decision d = g.submit(cmd, INPUT_OP, Guard.SessionState.remote("127.0.0.1"), armed(), clock);

        assertTrue(d.allowed() && !d.noop());
        assertEquals(1, writer.writes);
        assertEquals(0.15f, writer.commands.get(0).forward(), 1e-6, "the ticket's value must reach the client");
        assertEquals(2.5f, writer.commands.get(0).yawDelta(), 1e-6);
        assertTrue(writer.commands.get(0).jump());
    }

    @Test
    void ticksIsAcceptedButIsNotPartOfTheCommandValue() {
        assertEquals(Guard.InputCommand.none(), Guard.InputCommand.fromParams(params("{\"ticks\":40}")));
        assertEquals(Guard.InputCommand.none(), Guard.InputCommand.fromParams(params("{\"ticks\":40,\"forward\":0.0}")));
    }

    // 2) defaults == old behaviour ------------------------------------------------------------
    @Test
    void missingFieldsDefaultToZeroAndEmptyParamsIsByteForByteTheOldNoop() {
        Guard.InputCommand none = Guard.InputCommand.none();
        assertEquals(none, Guard.InputCommand.fromParams(null));
        assertEquals(none, Guard.InputCommand.fromParams(Json.object()));
        assertEquals(none, Guard.InputCommand.fromParams(params("{}")));
        assertEquals(none, Guard.InputCommand.fromParams(params("{\"forward\":0.0,\"jump\":false}")));

        Guard.GuardedInputWriter g = guarded("127.0.0.1");
        assertTrue(g.submit(Guard.InputCommand.fromParams(params("{}")), INPUT_OP,
                Guard.SessionState.remote("127.0.0.1"), armed(), clock).allowed());
        assertEquals(1, writer.writes);
        assertEquals(0f, writer.commands.get(0).forward(), 1e-6);
        assertEquals(0f, writer.commands.get(0).strafe(), 1e-6);
        assertEquals(0f, writer.commands.get(0).yawDelta(), 1e-6);
    }

    // 3) fail-closed is unchanged -------------------------------------------------------------
    @Test
    void unarmedStillRefusesBeforeAnyWriteEvenWithRealParams() {
        Guard.InputCommand cmd = Guard.InputCommand.fromParams(params("{\"forward\":1.0}"));

        Guard.GuardedInputWriter off = guarded("127.0.0.1");
        Guard.Decision d1 = off.submit(cmd, INPUT_OP, Guard.SessionState.singleplayer(),
                Guard.ActivationState.off(), clock);
        assertFalse(d1.allowed());
        assertEquals(0, off.writesPerformed());

        Guard.GuardedInputWriter expired = guarded("127.0.0.1");
        Guard.Decision d2 = expired.submit(cmd, INPUT_OP, Guard.SessionState.remote("127.0.0.1"),
                new Guard.ActivationState(true, new Guard.ActivationToken("tok", NOW - 1)), clock);
        assertFalse(d2.allowed());
        assertEquals(0, expired.writesPerformed());

        Guard.GuardedInputWriter undeclared = guarded();
        Guard.Decision d3 = undeclared.submit(cmd, INPUT_OP, Guard.SessionState.remote("play.example.net"),
                armed(), clock);
        assertFalse(d3.allowed());
        assertEquals(0, undeclared.writesPerformed());
        assertEquals(0, writer.writes);
        assertTrue(audit.isEmpty());
    }

    // 4) clamping still applies to the ticket's values ----------------------------------------
    @Test
    void oversizedParamsAreClampedBeforeTheyReachTheClient() {
        Guard.InputCommand cmd = Guard.InputCommand.fromParams(params(
                "{\"forward\":9.0,\"strafe\":-9.0,\"yawDelta\":180.0,\"pitchDelta\":90.0}"));
        Guard.GuardedInputWriter g = guarded("127.0.0.1");
        assertTrue(g.submit(cmd, INPUT_OP, Guard.SessionState.remote("127.0.0.1"), armed(), clock).allowed());

        assertEquals(4, g.clampedAxes());
        Guard.InputCommand written = writer.commands.get(0);
        assertEquals(Guard.HumanSpeedClamp.MAX_FORWARD, written.forward(), 1e-6);
        assertEquals(-Guard.HumanSpeedClamp.MAX_FORWARD, written.strafe(), 1e-6);
        assertEquals(Guard.HumanSpeedClamp.MAX_YAW_DEG, written.yawDelta(), 1e-6);
        assertEquals(Guard.HumanSpeedClamp.MAX_PITCH_DEG, written.pitchDelta(), 1e-6);
    }

    // 5) the audit line names the command actually written ------------------------------------
    @Test
    void auditRecordsTheClampedCommandButNeverTheTokenValue() {
        guardArmWithCommand();
        assertEquals(1, audit.size());
        String line = audit.get(0);
        assertTrue(line.contains("cmd=["), line);
        assertTrue(line.contains("forward=0.150"), line);
        assertTrue(line.contains("yaw=7.500"), "audit must show the clamped value: " + line);
        assertFalse(line.contains("token=tok"), "never the raw token value: " + line);
        assertTrue(line.contains("token=" + new Guard.ActivationToken("tok", 0L).fingerprint()),
                "the line carries only the fingerprint: " + line);
    }

    private void guardArmWithCommand() {
        Guard.InputCommand cmd = Guard.InputCommand.fromParams(params("{\"forward\":0.15,\"yawDelta\":90.0}"));
        guarded("127.0.0.1").submit(cmd, INPUT_OP, Guard.SessionState.remote("127.0.0.1"), armed(), clock);
    }

    @Test
    void unknownOrIllTypedParamsAreRejected() {
        for (String bad : new String[]{"{\"foward\":1.0}", "{\"forward\":\"fast\"}", "{\"jump\":1}"}) {
            Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                    () -> Guard.InputCommand.fromParams(params(bad)), "should reject: " + bad);
            assertEquals(Protocol.ErrorCode.E_BAD_PARAMS, e.code());
        }
    }
}
