package io.github.minatoai.modtest.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 — the guard, measured rather than read.
 *
 * <p>Every test here asserts on {@link Guard.GuardedInputWriter#writesPerformed()}: the number of
 * injection writes that actually reached the client. A refusal that still called the delegate would
 * fail these tests, so "the guard runs before any write" is an executed fact, not a code review.
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
            List.of(new Protocol.Precondition("singleplayer", null)),
            List.of(Protocol.SideEffect.PLAYER_INPUT), "vanilla-client", null, "1.0");

    private static final Protocol.OpSpec STATE_OP = new Protocol.OpSpec(
            "state.query", "Read state", Json.object(), Json.object(),
            List.of(new Protocol.Precondition("singleplayer", null)),
            List.of(Protocol.SideEffect.NONE), "vanilla-client", null, "1.0");

    private static final long NOW = 1_700_000_000_000L;

    private RecordingWriter writer;
    private Guard.GuardedInputWriter guarded;
    private Bridge.Clock clock;

    @BeforeEach
    void setUp() {
        writer = new RecordingWriter();
        guarded = new Guard.GuardedInputWriter(writer, new Guard.InputInjectionPolicy(),
                new Guard.HumanSpeedClamp());
        clock = Bridge.Clock.fixed(NOW);
    }

    private Guard.ActivationState armedButExpired(long expiry) {
        return new Guard.ActivationState(true, new Guard.ActivationToken("token-1", expiry));
    }

    private Guard.ActivationState armed() {
        return armedButExpired(NOW + 60_000L);
    }

    @Test
    void remoteMultiplayerSessionIsRefusedEvenWhenFullyActivated() {
        Guard.Decision d = guarded.submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.remote("play.example.net"), armed(), clock);

        assertFalse(d.allowed(), "a remote session must be refused");
        assertEquals(Protocol.ErrorCode.E_PRECONDITION, d.code());
        assertTrue(d.reason().contains("remote"), d.reason());
        assertEquals(1, guarded.attempts());
        assertEquals(0, guarded.writesPerformed(), "NOTHING may reach the writer on a remote session");
        assertEquals(1, guarded.denied());
        assertEquals(0, writer.writes);
    }

    @Test
    void remoteRefusalIsIndependentOfActivationState() {
        for (Guard.ActivationState act : List.of(Guard.ActivationState.off(), armed(),
                armedButExpired(NOW - 1))) {
            RecordingWriter w = new RecordingWriter();
            Guard.GuardedInputWriter g = new Guard.GuardedInputWriter(w, new Guard.InputInjectionPolicy(),
                    new Guard.HumanSpeedClamp());
            Guard.Decision d = g.submit(Guard.InputCommand.none(), INPUT_OP,
                    Guard.SessionState.remote("play.example.net"), act, clock);
            assertFalse(d.allowed());
            assertEquals(0, w.writes, "no activation state can unlock a remote session");
        }
    }

    @Test
    void injectionIsOffByDefault() {
        Guard.Decision d = guarded.submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.singleplayer(), Guard.ActivationState.off(), clock);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("off by default"), d.reason());
        assertEquals(0, guarded.writesPerformed());
    }

    @Test
    void devFlagAloneIsNotEnoughWithoutAToken() {
        Guard.Decision d = guarded.submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.singleplayer(), new Guard.ActivationState(true, null), clock);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("no activation token"), d.reason());
        assertEquals(0, guarded.writesPerformed());
    }

    @Test
    void expiredTokenIsRefused() {
        Guard.Decision d = guarded.submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.singleplayer(), armedButExpired(NOW - 1), clock);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("expired"), d.reason());
        assertEquals(0, guarded.writesPerformed());
    }

    @Test
    void tokenThatExpiresBetweenCallsStopsWorking() {
        long[] now = {NOW};
        Bridge.Clock moving = () -> now[0];
        Guard.GuardedInputWriter g = new Guard.GuardedInputWriter(writer, new Guard.InputInjectionPolicy(),
                new Guard.HumanSpeedClamp());
        Guard.ActivationState act = new Guard.ActivationState(true,
                new Guard.ActivationToken("t", NOW + 1_000L));

        assertTrue(g.submit(Guard.InputCommand.none(), INPUT_OP, Guard.SessionState.singleplayer(), act, moving)
                .allowed());
        assertEquals(1, writer.writes);
        now[0] = NOW + 1_001L;
        Guard.Decision after = g.submit(Guard.InputCommand.none(), INPUT_OP,
                Guard.SessionState.singleplayer(), act, moving);
        assertFalse(after.allowed(), "an expired token must stop working mid-session");
        assertEquals(1, writer.writes, "still only the one write from before expiry");
    }

    @Test
    void pausedClientIsANoOpNotAnError() {
        Guard.SessionState paused = new Guard.SessionState() {
            public boolean hasIntegratedServer() {
                return true;
            }

            public boolean connectedToRemoteServer() {
                return false;
            }

            public boolean paused() {
                return true;
            }

            public boolean inWorld() {
                return true;
            }

            public boolean handshakeInProgress() {
                return false;
            }
        };
        Guard.Decision d = guarded.submit(Guard.InputCommand.none(), INPUT_OP, paused, armed(), clock);
        assertTrue(d.allowed() && d.noop(), "paused is a no-op, not a refusal");
        assertEquals(0, guarded.writesPerformed());
        assertEquals(1, guarded.noops());
        assertEquals(0, writer.writes, "a paused client must not receive a write");
    }

    @Test
    void handshakeAndOutOfWorldAreNoOps() {
        Guard.SessionState handshake = new Guard.SessionState() {
            public boolean hasIntegratedServer() {
                return true;
            }

            public boolean connectedToRemoteServer() {
                return false;
            }

            public boolean paused() {
                return false;
            }

            public boolean inWorld() {
                return true;
            }

            public boolean handshakeInProgress() {
                return true;
            }
        };
        assertEquals("handshake in progress",
                guarded.submit(Guard.InputCommand.none(), INPUT_OP, handshake, armed(), clock).reason());

        Guard.SessionState noWorld = new Guard.SessionState() {
            public boolean hasIntegratedServer() {
                return true;
            }

            public boolean connectedToRemoteServer() {
                return false;
            }

            public boolean paused() {
                return false;
            }

            public boolean inWorld() {
                return false;
            }

            public boolean handshakeInProgress() {
                return false;
            }
        };
        assertEquals("not in a world",
                guarded.submit(Guard.InputCommand.none(), INPUT_OP, noWorld, armed(), clock).reason());
        assertEquals(0, writer.writes);
    }

    @Test
    void opMustDeclarePlayerInputSideEffect() {
        Guard.Decision d = guarded.submit(Guard.InputCommand.none(), STATE_OP,
                Guard.SessionState.singleplayer(), armed(), clock);
        assertFalse(d.allowed(), "a read-only op must not be able to inject input");
        assertTrue(d.reason().contains("PLAYER_INPUT"), d.reason());
        assertEquals(0, writer.writes);
    }

    @Test
    void allowedPathWritesExactlyOnceAndClampsToHumanSpeed() {
        Guard.InputCommand tooFast = new Guard.InputCommand(5.0f, -3.0f, 90.0f, 40.0f, true, false, true);
        Guard.Decision d = guarded.submit(tooFast, INPUT_OP, Guard.SessionState.singleplayer(), armed(), clock);

        assertTrue(d.allowed() && !d.noop());
        assertEquals(1, guarded.writesPerformed(), "the allowed path does reach the writer");
        assertEquals(1, writer.writes);
        assertEquals(4, guarded.clampedAxes(), "forward, strafe, yaw and pitch are all out of envelope");
        assertEquals(Guard.HumanSpeedClamp.MAX_FORWARD, writer.last.forward(), 1e-6);
        assertEquals(-Guard.HumanSpeedClamp.MAX_FORWARD, writer.last.strafe(), 1e-6);
        assertEquals(Guard.HumanSpeedClamp.MAX_YAW_DEG, writer.last.yawDelta(), 1e-6);
        assertEquals(Guard.HumanSpeedClamp.MAX_PITCH_DEG, writer.last.pitchDelta(), 1e-6);
    }
}
