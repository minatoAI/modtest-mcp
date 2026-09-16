package io.github.minatoai.modtest.core;

import java.util.List;

/**
 * M2 input-injection: the safety surface.
 *
 * <p>Two invariants, both enforced here rather than by convention:
 * <ol>
 *   <li>Injection is <b>off by default</b>: it needs an explicit dev flag <i>and</i> an unexpired
 *       activation token.</li>
 *   <li>The guard runs <b>before any write</b>: {@link GuardedInputWriter#submit} evaluates the
 *       policy first and never touches the delegate when the decision is a refusal.</li>
 * </ol>
 * The remote/multiplayer refusal is part of the policy (rule 4), so a session that is connected to
 * a remote server can never reach the writer, whatever the ticket asks for.
 */
public final class Guard {
    private Guard() {
    }

    /** What the client currently is. Injected, so tests can construct any environment. */
    public interface SessionState {
        boolean hasIntegratedServer();

        boolean connectedToRemoteServer();

        boolean paused();

        boolean inWorld();

        boolean handshakeInProgress();

        /** Convenience: a single-player world that is safe for mutating ops. */
        static SessionState singleplayer() {
            return new SessionState() {
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
                    return false;
                }
            };
        }

        /** A remote/multiplayer session: the case that MUST always be refused. */
        static SessionState remote(String server) {
            return new SessionState() {
                public boolean hasIntegratedServer() {
                    return false;
                }

                public boolean connectedToRemoteServer() {
                    return true;
                }

                public boolean paused() {
                    return false;
                }

                public boolean inWorld() {
                    return true;
                }

                public boolean handshakeInProgress() {
                    return false;
                }
            };
        }
    }

    /** A one-shot activation token with an expiry, issued outside the ticket (never by a ticket). */
    public record ActivationToken(String value, long expiresAtMs) {
        public boolean isActive(Bridge.Clock clock) {
            return value != null && !value.isBlank() && clock.nowMs() < expiresAtMs;
        }
    }

    /** Activation state: the dev flag plus the token. Both are required. */
    public static final class ActivationState {
        private final boolean devFlag;
        private final ActivationToken token;

        public ActivationState(boolean devFlag, ActivationToken token) {
            this.devFlag = devFlag;
            this.token = token;
        }

        /** The default: everything off. */
        public static ActivationState off() {
            return new ActivationState(false, null);
        }

        public boolean devFlag() {
            return devFlag;
        }

        public ActivationToken token() {
            return token;
        }

        public boolean active(Bridge.Clock clock) {
            return devFlag && token != null && token.isActive(clock);
        }
    }

    /** One injection request. Values are player-input axes, never world mutations. */
    public record InputCommand(float forward, float strafe, float yawDelta, float pitchDelta,
                               boolean jump, boolean sneak, boolean sprint) {
        public static InputCommand none() {
            return new InputCommand(0f, 0f, 0f, 0f, false, false, false);
        }
    }

    /** The injection port. The only thing allowed to touch player input. */
    public interface InputWriter {
        void write(InputCommand command);
    }

    /** Policy decision. {@code noop} means "allowed but nothing to do" (paused/handshake). */
    public record Decision(boolean allowed, boolean noop, Protocol.ErrorCode code, String reason) {
        public static Decision allow() {
            return new Decision(true, false, null, "allowed");
        }

        public static Decision noop(String reason) {
            return new Decision(true, true, null, reason);
        }

        public static Decision deny(String reason) {
            return new Decision(false, false, Protocol.ErrorCode.E_PRECONDITION, reason);
        }
    }

    /** The single place where injection permission is decided. Order is normative. */
    public static final class InputInjectionPolicy {

        public Decision decide(Protocol.OpSpec op, SessionState session, ActivationState activation,
                               Bridge.Clock clock) {
            if (session.handshakeInProgress()) {
                return Decision.noop("handshake in progress");
            }
            if (!session.inWorld()) {
                return Decision.noop("not in a world");
            }
            if (session.paused()) {
                return Decision.noop("client paused");
            }
            // Rule 4 — remote/multiplayer refusal, checked before activation so that no
            // activation token can ever unlock a remote session.
            if (session.connectedToRemoteServer() || !session.hasIntegratedServer()) {
                return Decision.deny("refused: session is remote/multiplayer");
            }
            if (op != null && op.sideEffects() != null
                    && !op.sideEffects().contains(Protocol.SideEffect.PLAYER_INPUT)) {
                return Decision.deny("op does not declare sideEffects [PLAYER_INPUT]");
            }
            if (!activation.devFlag()) {
                return Decision.deny("injection is off by default (dev flag not set)");
            }
            ActivationToken token = activation.token();
            if (token == null) {
                return Decision.deny("no activation token");
            }
            if (!token.isActive(clock)) {
                return Decision.deny("activation token expired");
            }
            return Decision.allow();
        }
    }

    /** Human-speed envelope; clamping is a safety limit, not a feature (§M2 item 4). */
    public static final class HumanSpeedClamp {
        public static final float MAX_FORWARD = 0.215f;   // blocks/tick, ~4.3 m/s
        public static final float MAX_YAW_DEG = 7.5f;     // deg/tick, ~150 deg/s
        public static final float MAX_PITCH_DEG = 7.5f;

        public record Result(InputCommand command, List<String> clamped) {
        }

        public Result apply(InputCommand in) {
            List<String> clamped = new java.util.ArrayList<>();
            float forward = clampAxis(in.forward(), MAX_FORWARD, "forward", clamped);
            float strafe = clampAxis(in.strafe(), MAX_FORWARD, "strafe", clamped);
            float yaw = clampAxis(in.yawDelta(), MAX_YAW_DEG, "yaw", clamped);
            float pitch = clampAxis(in.pitchDelta(), MAX_PITCH_DEG, "pitch", clamped);
            return new Result(new InputCommand(forward, strafe, yaw, pitch, in.jump(), in.sneak(), in.sprint()),
                    List.copyOf(clamped));
        }

        private float clampAxis(float value, float max, String label, List<String> clamped) {
            if (Math.abs(value) > max) {
                clamped.add(label);
                return Math.copySign(max, value);
            }
            return value;
        }
    }

    /** Guarded writer: policy first, delegate second. Counters exist to make refusals measurable. */
    public static final class GuardedInputWriter {
        private final InputWriter delegate;
        private final InputInjectionPolicy policy;
        private final HumanSpeedClamp clamp;
        private int attempts;
        private int performed;
        private int denied;
        private int noops;
        private int clampedAxes;
        private InputCommand lastCommand;

        public GuardedInputWriter(InputWriter delegate, InputInjectionPolicy policy, HumanSpeedClamp clamp) {
            this.delegate = delegate;
            this.policy = policy;
            this.clamp = clamp;
        }

        public Decision submit(InputCommand command, Protocol.OpSpec op, SessionState session,
                               ActivationState activation, Bridge.Clock clock) {
            attempts++;
            Decision decision = policy.decide(op, session, activation, clock);
            if (!decision.allowed()) {
                denied++;
                return decision;
            }
            if (decision.noop()) {
                noops++;
                return decision;
            }
            HumanSpeedClamp.Result clamped = clamp.apply(command);
            clampedAxes += clamped.clamped().size();
            lastCommand = clamped.command();
            delegate.write(clamped.command());
            performed++;
            return decision;
        }

        public int attempts() {
            return attempts;
        }

        /** The number the negative tests assert on: writes that actually reached the client. */
        public int writesPerformed() {
            return performed;
        }

        public int denied() {
            return denied;
        }

        public int noops() {
            return noops;
        }

        public int clampedAxes() {
            return clampedAxes;
        }

        public InputCommand lastCommand() {
            return lastCommand;
        }
    }
}
