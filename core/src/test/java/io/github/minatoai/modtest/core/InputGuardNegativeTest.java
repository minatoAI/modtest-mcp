package io.github.minatoai.modtest.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 — the guard, measured rather than read.
 *
 * <p>Policy under test (task-52 revision): <b>default deny + explicit host allow-list</b>.
 * Read-only ops are always allowed; writing input needs the dev flag, an unexpired activation token
 * and either a single-player world or a host the operator declared — and every such allowance is
 * logged loudly. Whether an allowance happened is asserted on
 * {@link Guard.GuardedInputWriter#writesPerformed()} and on the captured audit lines, never by
 * reading the source.
 */
class InputGuardNegativeTest {

    /** Records every write that reaches the client. */
    static final class RecordingWriter implements Guard.InputWriter {
        int writes;
        Guard.InputCommand last;

        @Override
        public void write(Guard.InputCommand command) {
            writes++;
            last = command;
        }
    }

    private static final Protocol.OpSpec INPUT_OP = new Protocol.OpSpec(
            "input.set", "Drive player input", Json.object(), Json.object(),
            List.of(Protocol.Precondition.of("permitted-session")),
            List.of(Protocol.SideEffect.PLAYER_INPUT), "vanilla-client", null, "1.0");

    private static final Protocol.OpSpec STATE_OP = new Protocol.OpSpec(
            "state.query", "Read state", Json.object(), Json.object(),
            List.of(), List.of(Protocol.SideEffect.NONE), "vanilla-client", null, "1.0");

    private static final long NOW = 1_700_000_000_000L;

    private RecordingWriter writer;
    private List<String> auditLines;
    private Bridge.Clock clock;

    @BeforeEach
    void setUp() {
        writer = new RecordingWriter();
        auditLines = new ArrayList<>();
        clock = Bridge.Clock.fixed(NOW);
    }

    private Guard.GuardedInputWriter guarded(String... whitelist) {
        return new Guard.GuardedInputWriter(writer,
                new Guard.InputInjectionPolicy(Guard.HostWhitelist.of(whitelist), Guard.BuildVariant.GUARDED,
                        "unit-test", () -> "minecraft:overworld"),
                new Guard.HumanSpeedClamp(), Guard.AuditSink.to(auditLines::add));
    }

    private Guard.ActivationState armed() {
        return new Guard.ActivationState(true, new Guard.ActivationToken("secret-token-value", NOW + 60_000L));
    }

    // -- 1) non-single-player, not declared -> refused ------------------------------------------
    @Test
    void hostThatWasNotDeclaredIsRefusedAndNothingIsWritten() {
        Guard.GuardedInputWriter g = guarded();
        Guard.Decision d = g.submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.remote("play.example.net"), armed(), clock);

        assertFalse(d.allowed());
        assertEquals(Protocol.ErrorCode.E_PRECONDITION, d.code());
        assertTrue(d.reason().contains("not whitelisted"), d.reason());
        assertEquals(1, g.attempts());
        assertEquals(0, g.writesPerformed(), "NOTHING may reach the writer for an undeclared host");
        assertEquals(1, g.denied());
        assertEquals(0, writer.writes);
        assertTrue(auditLines.isEmpty(), "a refusal is not an allowance");
    }

    @Test
    void anEmptyWhitelistIsTheDefaultPosture() {
        assertTrue(Guard.HostWhitelist.empty().isEmpty());
        assertFalse(Guard.HostWhitelist.empty().permitsHost("localhost"));
        Guard.Decision d = guarded().submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.remote("localhost:25575"), armed(), clock);
        assertFalse(d.allowed(), "even localhost must be declared explicitly");
    }

    // -- 2) declared host -> allowed, and loudly logged ------------------------------------------
    @Test
    void declaredHostIsAllowedAndEveryAllowanceIsLogged() {
        Guard.GuardedInputWriter g = guarded("localhost", "127.0.0.1");
        Guard.InputCommand cmd = new Guard.InputCommand(0.1f, 0f, 1f, 0f, false, false, false);
        Guard.Decision d = g.submit(cmd, INPUT_OP, Guard.SessionState.remote("localhost:25575"), armed(), clock);

        assertTrue(d.allowed() && !d.noop());
        assertTrue(d.reason().contains("whitelisted host"), d.reason());
        assertEquals(1, g.writesPerformed());
        assertEquals(1, writer.writes);

        assertEquals(1, auditLines.size(), "one allowance, one audit line");
        String line = auditLines.get(0);
        assertTrue(line.startsWith("ALLOWED-INPUT"), line);
        assertTrue(line.contains("host=localhost:25575"), "the line must name the host: " + line);
        assertTrue(line.contains("at=" + NOW), "the line must carry the time: " + line);
        assertTrue(line.contains("executor=unit-test"), "the line must name who acted: " + line);
        assertTrue(line.contains("op=input.set"), line);
        assertEquals(1, g.allowances().size());
        assertEquals("localhost:25575", g.allowances().get(0).host());
        assertEquals(NOW, g.allowances().get(0).atMs());
    }

    @Test
    void auditLineIdentifiesTheTokenWithoutLeakingIt() {
        Guard.GuardedInputWriter g = guarded("127.0.0.1");
        g.submit(Guard.InputCommand.none(), INPUT_OP, Guard.SessionState.remote("127.0.0.1"), armed(), clock);

        String line = auditLines.get(0);
        assertFalse(line.contains("secret-token-value"), "the token value must never be logged: " + line);
        String fingerprint = new Guard.ActivationToken("secret-token-value", NOW + 1).fingerprint();
        assertTrue(line.contains("token=" + fingerprint), "expected the token fingerprint in: " + line);
        assertEquals(8, fingerprint.length());
    }

    @Test
    void whitelistMatchingIgnoresCaseAndTheDefaultPort() {
        Guard.HostWhitelist wl = Guard.HostWhitelist.of("Dev.Example.NET");
        assertTrue(wl.permitsHost("dev.example.net:25565"));
        assertTrue(wl.permitsHost("DEV.EXAMPLE.NET"));
        assertFalse(wl.permitsHost("other.example.net"));
    }

    @Test
    void singleplayerAllowanceIsAlsoAudited() {
        Guard.GuardedInputWriter g = guarded();
        Guard.Decision d = g.submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.singleplayer(), armed(), clock);
        assertTrue(d.allowed());
        assertEquals(1, writer.writes);
        assertTrue(auditLines.get(0).contains("host=singleplayer"), auditLines.get(0));
    }

    // -- 3) token expiry ------------------------------------------------------------------------
    @Test
    void expiredTokenIsRefusedEvenOnASingleplayerWorld() {
        Guard.GuardedInputWriter g = guarded();
        Guard.Decision d = g.submit(Guard.InputCommand.none(), INPUT_OP, Guard.SessionState.singleplayer(),
                new Guard.ActivationState(true, new Guard.ActivationToken("t", NOW - 1)), clock);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("expired"), d.reason());
        assertEquals(0, g.writesPerformed());
    }

    @Test
    void injectionIsOffByDefault() {
        Guard.GuardedInputWriter g = guarded("localhost");
        Guard.Decision d = g.submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.singleplayer(), Guard.ActivationState.off(), clock);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("off by default"), d.reason());
        assertEquals(0, g.writesPerformed());
    }

    @Test
    void devFlagAloneIsNotEnoughWithoutAToken() {
        Guard.Decision d = guarded("localhost").submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.singleplayer(), new Guard.ActivationState(true, null), clock);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("no activation token"), d.reason());
        assertEquals(0, writer.writes);
    }

    @Test
    void aTokenThatExpiresBetweenCallsStopsWorking() {
        long[] now = {NOW};
        Bridge.Clock moving = () -> now[0];
        Guard.GuardedInputWriter g = guarded("localhost");
        Guard.ActivationState act = new Guard.ActivationState(true, new Guard.ActivationToken("t", NOW + 1_000L));

        assertTrue(g.submit(Guard.InputCommand.none(), INPUT_OP, Guard.SessionState.remote("localhost"), act, moving)
                .allowed());
        assertEquals(1, writer.writes);
        now[0] = NOW + 1_001L;
        Guard.Decision after = g.submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.remote("localhost"), act, moving);
        assertFalse(after.allowed(), "an expired token must stop working mid-session");
        assertEquals(1, writer.writes, "still only the one write from before expiry");
    }

    // -- 4) read-only ops are never blocked by the injection policy ------------------------------
    @Test
    void readOnlyOpIsAllowedEvenOnAHostWeDoNotActOn() {
        Guard.GuardedInputWriter g = guarded();
        Guard.Decision d = g.submit(Guard.InputCommand.none(), STATE_OP,
                Guard.SessionState.remote("someone-elses-server.example"), Guard.ActivationState.off(), clock);

        assertTrue(d.allowed(), "read-only ops are layered above the injection policy");
        assertTrue(d.noop());
        assertEquals(0, g.denied(), "a read-only op must not be counted as a refusal");
        assertEquals(0, g.writesPerformed(), "nothing may be injected by a read-only op");
        assertEquals(0, writer.writes);
    }

    // -- paused / handshake are no-ops, not errors ----------------------------------------------
    @Test
    void pausedAndHandshakeAreNoOps() {
        Guard.SessionState paused = Guard.SessionState.of(true, false, true, true, false, null);
        Guard.Decision p = guarded("localhost").submit(Guard.InputCommand.none(), INPUT_OP, paused, armed(), clock);
        assertTrue(p.allowed() && p.noop());
        assertEquals("client paused", p.reason());

        Guard.SessionState handshake = Guard.SessionState.of(true, false, false, true, true, null);
        Guard.Decision h = guarded("localhost").submit(Guard.InputCommand.none(), INPUT_OP, handshake, armed(), clock);
        assertTrue(h.allowed() && h.noop());
        assertEquals("handshake in progress", h.reason());

        Guard.SessionState noWorld = Guard.SessionState.of(true, false, false, false, false, null);
        Guard.Decision n = guarded("localhost").submit(Guard.InputCommand.none(), INPUT_OP, noWorld, armed(), clock);
        assertTrue(n.allowed() && n.noop());
        assertEquals("not in a world", n.reason());
        assertEquals(0, writer.writes);
    }

    @Test
    void allowedPathClampsToHumanSpeed() {
        Guard.GuardedInputWriter g = guarded("localhost");
        Guard.Decision d = g.submit(new Guard.InputCommand(5.0f, -3.0f, 90.0f, 40.0f, true, false, true),
                INPUT_OP, Guard.SessionState.remote("localhost"), armed(), clock);

        assertTrue(d.allowed() && !d.noop());
        assertEquals(1, g.writesPerformed());
        assertEquals(4, g.clampedAxes());
        assertEquals(Guard.HumanSpeedClamp.MAX_FORWARD, writer.last.forward(), 1e-6);
        assertEquals(-Guard.HumanSpeedClamp.MAX_FORWARD, writer.last.strafe(), 1e-6);
        assertEquals(Guard.HumanSpeedClamp.MAX_YAW_DEG, writer.last.yawDelta(), 1e-6);
        assertEquals(Guard.HumanSpeedClamp.MAX_PITCH_DEG, writer.last.pitchDelta(), 1e-6);
    }

    // -- 5) the build says which variant it is ---------------------------------------------------
    @Test
    void unstampedClasspathCountsAsGuarded() {
        assertEquals(Guard.BuildVariant.GUARDED, Guard.BuildVariant.current(),
                "an artifact without the manifest stamp must be treated as guarded");
        assertTrue(BuildInfo.guarded());
        assertTrue(BuildInfo.describe().contains("guard=GUARDED"), BuildInfo.describe());
    }

    @Test
    void unguardedVariantAnnouncesItselfInEveryDecision() {
        Guard.GuardedInputWriter unguarded = new Guard.GuardedInputWriter(writer,
                new Guard.InputInjectionPolicy(Guard.HostWhitelist.empty(), Guard.BuildVariant.UNGUARDED,
                        "unit-test", () -> "minecraft:overworld"),
                new Guard.HumanSpeedClamp(), Guard.AuditSink.to(auditLines::add));

        Guard.Decision d = unguarded.submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.remote("someone-elses-server.example"), Guard.ActivationState.off(), clock);
        assertTrue(d.allowed(), "the unguarded variant bypasses the policy by design");
        assertTrue(d.reason().contains("unguarded"), "and it must say so: " + d.reason());
        assertEquals(1, writer.writes);
        assertNotNull(auditLines.get(0));
        assertTrue(auditLines.get(0).contains("unguarded"), auditLines.get(0));
    }
}
