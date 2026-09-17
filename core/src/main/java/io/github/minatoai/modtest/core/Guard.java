package io.github.minatoai.modtest.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * M2 input-injection: the safety surface.
 *
 * <p>The rule is <b>default deny, plus an explicit allow-list of hosts you own</b>:
 * <ol>
 *   <li><b>Read-only ops are always allowed.</b> Injection is the only thing being gated.</li>
 *   <li><b>Injection is off by default</b>: it needs an explicit dev flag <i>and</i> an unexpired
 *       activation token. A ticket can never activate anything.</li>
 *   <li>With injection armed, writing input is allowed in a <b>single-player</b> world, or against
 *       a host that the operator listed explicitly (e.g. {@code localhost}, {@code 127.0.0.1}, a
 *       self-hosted dev server). <b>Every such allowance is logged loudly</b> — who, which host,
 *       when, and which token (fingerprint, never the token value).</li>
 *   <li>Any other host is <b>refused</b>. Default safety does not move: a host nobody declared is
 *       a host we do not act on.</li>
 * </ol>
 * The guard runs <b>before any write</b>: {@link GuardedInputWriter#submit} evaluates the policy
 * first and never touches the delegate when the decision is a refusal.
 *
 * <p>Two build variants share this one source tree; see {@link BuildVariant}.
 */
public final class Guard {
    private Guard() {
    }

    /** Which build produced this artifact; stamped into the jar manifest at build time. */
    public enum BuildVariant {
        /** Ships in releases. The policy below is enforced. */
        GUARDED,
        /** Self-compiled with {@code -Punguarded}; the policy is bypassed, loudly. */
        UNGUARDED;

        public static final String MANIFEST_KEY = "Modtest-Guard-Variant";

        /** Reads the variant from this artifact's manifest; defaults to GUARDED when unknown. */
        public static BuildVariant current() {
            try (java.io.InputStream in = Guard.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
                if (in != null) {
                    java.util.jar.Manifest mf = new java.util.jar.Manifest(in);
                    String v = mf.getMainAttributes().getValue(MANIFEST_KEY);
                    if (v != null) {
                        return valueOf(v.trim().toUpperCase(Locale.ROOT));
                    }
                }
            } catch (Exception ignored) {
                // fall through: an unstamped class path is treated as guarded
            }
            return GUARDED;
        }
    }

    /** What the client currently is. Injected, so tests can construct any environment. */
    public interface SessionState {
        boolean hasIntegratedServer();

        boolean connectedToRemoteServer();

        boolean paused();

        boolean inWorld();

        boolean handshakeInProgress();

        /** {@code host[:port]} of the current connection, or {@code null} in single-player. */
        default String serverAddress() {
            return null;
        }

        /** Convenience: a single-player world. */
        static SessionState singleplayer() {
            return of(true, false, false, true, false, null);
        }

        /** A remote/multiplayer session. */
        static SessionState remote(String host) {
            return of(false, true, false, true, false, host);
        }

        static SessionState of(boolean integrated, boolean remote, boolean paused, boolean inWorld,
                               boolean handshake, String address) {
            return new SessionState() {
                public boolean hasIntegratedServer() {
                    return integrated;
                }

                public boolean connectedToRemoteServer() {
                    return remote;
                }

                public boolean paused() {
                    return paused;
                }

                public boolean inWorld() {
                    return inWorld;
                }

                public boolean handshakeInProgress() {
                    return handshake;
                }

                public String serverAddress() {
                    return address;
                }
            };
        }
    }

    /** Hosts the operator explicitly declared as theirs. Empty by default: deny everything remote. */
    public static final class HostWhitelist {
        private final Set<String> hosts;

        private HostWhitelist(Set<String> hosts) {
            this.hosts = hosts;
        }

        public static HostWhitelist empty() {
            return new HostWhitelist(Set.of());
        }

        /** Accepts {@code host} or {@code host:port}; matching ignores case and a trailing default port. */
        public static HostWhitelist of(String... entries) {
            Set<String> set = new LinkedHashSet<>();
            for (String e : entries) {
                if (e != null && !e.isBlank()) {
                    set.add(normalize(e));
                }
            }
            return new HostWhitelist(set);
        }

        public static HostWhitelist parse(String csv) {
            return csv == null || csv.isBlank() ? empty() : of(csv.split(","));
        }

        static String normalize(String raw) {
            String h = raw.trim().toLowerCase(Locale.ROOT);
            if (h.endsWith(":25565")) {
                h = h.substring(0, h.length() - 6);
            }
            if (h.startsWith("[") && h.contains("]")) {
                h = h.substring(1, h.indexOf(']'));
            }
            int colon = h.lastIndexOf(':');
            if (colon > 0 && h.indexOf(':') == colon) {
                h = h.substring(0, colon);
            }
            return h;
        }

        public Set<String> hosts() {
            return Set.copyOf(hosts);
        }

        public boolean isEmpty() {
            return hosts.isEmpty();
        }

        public boolean permitsHost(String host) {
            return host != null && hosts.contains(normalize(host));
        }

        /** Single-player is always permitted; otherwise the address must be declared. */
        public boolean permits(SessionState session) {
            if (session.hasIntegratedServer() && !session.connectedToRemoteServer()) {
                return true;
            }
            return permitsHost(session.serverAddress());
        }
    }

    /** A one-shot activation token with an expiry, issued outside the ticket (never by a ticket). */
    public record ActivationToken(String value, long expiresAtMs) {
        public boolean isActive(Bridge.Clock clock) {
            return value != null && !value.isBlank() && clock.nowMs() < expiresAtMs;
        }

        /** Stable short fingerprint so logs can name the token without ever recording its value. */
        public String fingerprint() {
            if (value == null) {
                return "none";
            }
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(value.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 4; i++) {
                    sb.append(String.format("%02x", d[i]));
                }
                return sb.toString();
            } catch (Exception e) {
                return "unknown";
            }
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
        /**
         * Parameter names accepted by {@code input.set}. {@code ticks} is a hold duration consumed
         * by the executor, not part of the command value.
         */
        public static final java.util.Set<String> PARAMS = java.util.Set.of(
                "forward", "strafe", "yawDelta", "pitchDelta", "jump", "sneak", "sprint", "ticks");

        public static InputCommand none() {
            return new InputCommand(0f, 0f, 0f, 0f, false, false, false);
        }

        /**
         * Builds a command from {@code input.set} params. Missing fields default to zero, so an
         * empty {@code params} object is byte-for-byte the old behaviour (a no-op command).
         */
        public static InputCommand fromParams(com.google.gson.JsonObject params) {
            // `size() == 0`, never `isEmpty()`: JsonObject.isEmpty() only exists from Gson 2.10.1,
            // while Minecraft 1.20.1 ships Gson 2.10 — there it is a NoSuchMethodError at runtime,
            // which unit tests compiled against 2.10.1 can never catch. See :core:verifyGsonApiSurface.
            if (params == null || params.size() == 0) {
                return none();
            }
            for (String key : params.keySet()) {
                if (!PARAMS.contains(key)) {
                    throw badParam(key, "unknown input param");
                }
            }
            return new InputCommand(num(params, "forward"), num(params, "strafe"), num(params, "yawDelta"),
                    num(params, "pitchDelta"), flag(params, "jump"), flag(params, "sneak"), flag(params, "sprint"));
        }

        /** Human-readable command summary for the audit line. */
        public String summary() {
            return String.format(java.util.Locale.ROOT,
                    "forward=%.3f strafe=%.3f yaw=%.3f pitch=%.3f jump=%s sneak=%s sprint=%s",
                    forward, strafe, yawDelta, pitchDelta, jump, sneak, sprint);
        }

        private static float num(com.google.gson.JsonObject p, String key) {
            com.google.gson.JsonElement e = p.get(key);
            if (e == null || e.isJsonNull()) {
                return 0f;
            }
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                throw badParam(key, "param must be a number");
            }
            return e.getAsFloat();
        }

        private static boolean flag(com.google.gson.JsonObject p, String key) {
            com.google.gson.JsonElement e = p.get(key);
            if (e == null || e.isJsonNull()) {
                return false;
            }
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
                throw badParam(key, "param must be a boolean");
            }
            return e.getAsBoolean();
        }

        private static Protocol.ProtocolException badParam(String key, String why) {
            return (Protocol.ProtocolException) new Protocol.ProtocolException(
                    Protocol.ErrorCode.E_BAD_PARAMS, why + ": " + key).with("path", "params." + key);
        }
    }

    /**
     * The executor contract for a guard decision: <b>a refusal means the op did not execute</b>, so
     * callers must surface it as an op with {@code ok:false} and a stable error code — never as a
     * successful op that merely reports {@code allowed:false}. Otherwise the natural reading
     * ("all ops ok ⇒ the ticket succeeded") would treat a guarded injection as having happened.
     *
     * <p>Lives in core (not in the Forge adapter) so the encoding is unit-tested without Minecraft.
     * {@code E_PRECONDITION} is reused deliberately: the guard's conditions (dev flag, unexpired
     * token, single-player or declared host) are preconditions, so no new wire code and no protocol
     * version change are needed. A no-op decision (paused / handshake) is <i>not</i> a refusal: the
     * op did execute and decided there was nothing to do, and keeps {@code ok:true} + {@code noop:true}.
     */
    public static void requireAllowed(Decision decision, String opName) {
        if (!decision.allowed()) {
            throw (Protocol.ProtocolException) new Protocol.ProtocolException(
                    Protocol.ErrorCode.E_PRECONDITION,
                    opName + " refused by the guard (allowed=false, queued=false, noop=false): "
                            + decision.reason());
        }
    }

    /** The injection port. The only thing allowed to touch player input. */
    public interface InputWriter {
        void write(InputCommand command);
    }

    /** A loud, auditable record of one allowance, including the command that was actually written. */
    public record Allowance(String executorId, String host, String dimension, long atMs,
                            String tokenFingerprint, String op, String command, String reason) {
        /** One line, deliberately greppable and deliberately not containing the token value. */
        public String format() {
            return "ALLOWED-INPUT executor=" + executorId + " host=" + (host == null ? "singleplayer" : host)
                    + " dimension=" + dimension + " at=" + atMs + " token=" + tokenFingerprint
                    + " op=" + op + " cmd=[" + command + "] reason=" + reason;
        }
    }

    /** Where allowances go. Implementations should write them somewhere durable. */
    public interface AuditSink {
        void allowance(Allowance allowance);

        static AuditSink to(java.util.function.Consumer<String> sink) {
            return allowance -> sink.accept(allowance.format());
        }
    }

    /** Policy decision. {@code noop} means "allowed but nothing to do" (paused/handshake). */
    public record Decision(boolean allowed, boolean noop, Protocol.ErrorCode code, String reason) {
        public static Decision allow(String reason) {
            return new Decision(true, false, null, reason);
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
        private final HostWhitelist whitelist;
        private final BuildVariant variant;
        private final String executorId;
        private final java.util.function.Supplier<String> dimension;

        public InputInjectionPolicy(HostWhitelist whitelist, BuildVariant variant, String executorId,
                                    java.util.function.Supplier<String> dimension) {
            this.whitelist = whitelist == null ? HostWhitelist.empty() : whitelist;
            this.variant = variant == null ? BuildVariant.GUARDED : variant;
            this.executorId = executorId;
            this.dimension = dimension == null ? () -> "unknown" : dimension;
        }

        public InputInjectionPolicy() {
            this(HostWhitelist.empty(), BuildVariant.current(), "unknown", null);
        }

        public HostWhitelist whitelist() {
            return whitelist;
        }

        public BuildVariant variant() {
            return variant;
        }

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
            // Tier 1: read-only ops are never gated by the injection policy.
            if (op != null && op.sideEffects() != null
                    && !op.sideEffects().contains(Protocol.SideEffect.PLAYER_INPUT)) {
                return Decision.allow("read-only op (no PLAYER_INPUT side effect)");
            }
            if (variant == BuildVariant.UNGUARDED) {
                return Decision.allow("unguarded build: policy bypassed (self-compiled variant)");
            }
            // Tier 2: armed injection needs the dev flag plus an unexpired token.
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
            // Tier 3: single-player, or a host the operator explicitly declared.
            if (session.hasIntegratedServer() && !session.connectedToRemoteServer()) {
                return Decision.allow("single-player world");
            }
            String host = session.serverAddress();
            if (host == null || host.isBlank() || host.equalsIgnoreCase("unknown")) {
                // No address means ownership cannot be checked. Refuse explicitly (and say why) rather
                // than letting a null quietly miss the whitelist and be reported as a miss.
                return Decision.deny("refused: host address unavailable (session reports '"
                        + (host == null ? "null" : host) + "') — cannot verify ownership; refusing "
                        + "instead of assuming it is allowed");
            }
            if (whitelist.permitsHost(host)) {
                return Decision.allow("explicitly whitelisted host: " + host);
            }
            return Decision.deny("refused: host '" + (host == null ? "unknown" : host)
                    + "' is not whitelisted (default deny)");
        }

        /** Builds the audit record for an allowance, including the command that will be written. */
        public Allowance allowanceFor(Protocol.OpSpec op, SessionState session, ActivationState activation,
                                      Bridge.Clock clock, InputCommand command, String reason) {
            ActivationToken token = activation == null ? null : activation.token();
            return new Allowance(executorId, session.serverAddress(), dimension.get(), clock.nowMs(),
                    token == null ? "none" : token.fingerprint(), op == null ? "?" : op.name(),
                    command == null ? "none" : command.summary(), reason);
        }
    }

    /** Human-speed envelope; clamping is a safety limit, not a feature. */
    public static final class HumanSpeedClamp {
        public static final float MAX_FORWARD = 0.215f;   // blocks/tick, ~4.3 m/s
        public static final float MAX_YAW_DEG = 7.5f;     // deg/tick, ~150 deg/s
        public static final float MAX_PITCH_DEG = 7.5f;

        public record Result(InputCommand command, List<String> clamped) {
        }

        public Result apply(InputCommand in) {
            List<String> clamped = new ArrayList<>();
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
        private final AuditSink audit;
        private int attempts;
        private int performed;
        private int denied;
        private int noops;
        private int clampedAxes;
        private final List<Allowance> allowances = new ArrayList<>();
        private InputCommand lastCommand;

        /**
         * The only constructor: the audit sink is mandatory.
         *
         * <p>A convenience overload that defaulted the sink to a no-op used to exist and production
         * silently took it, so "loud by design" allowances were invisible on a real client while
         * every unit test (which passed a sink) stayed green. Same family as compiling against a
         * different library version than the one that runs: the tested path was not the shipped path.
         * {@code AuditWiringTest} asserts this class exposes exactly one constructor, so the silent
         * shortcut cannot return unnoticed.
         */
        public GuardedInputWriter(InputWriter delegate, InputInjectionPolicy policy, HumanSpeedClamp clamp,
                                  AuditSink audit) {
            if (audit == null) {
                throw new IllegalArgumentException(
                        "an audit sink is mandatory: an allowance must never go unlogged");
            }
            this.delegate = delegate;
            this.policy = policy;
            this.clamp = clamp;
            this.audit = audit;
        }

        /**
         * Production entry point — the same construction path the tests exercise. Every allowance
         * goes to {@code log} (who, which host, when, token fingerprint, which op, which command).
         * The token <i>value</i> never appears; only its fingerprint does.
         */
        public static GuardedInputWriter audited(InputWriter delegate, InputInjectionPolicy policy,
                                                 HumanSpeedClamp clamp,
                                                 java.util.function.Consumer<String> log) {
            if (log == null) {
                throw new IllegalArgumentException(
                        "a log sink is mandatory: an allowance must never go unlogged");
            }
            return new GuardedInputWriter(delegate, policy, clamp, AuditSink.to(log));
        }

        public Decision submit(InputCommand command, Protocol.OpSpec op, SessionState session,
                               ActivationState activation, Bridge.Clock clock) {
            attempts++;
            // This writer only ever carries input. A read-only op is allowed by the policy but has
            // nothing to write, so it must not reach the client.
            if (op != null && op.sideEffects() != null
                    && !op.sideEffects().contains(Protocol.SideEffect.PLAYER_INPUT)) {
                noops++;
                return Decision.noop("read-only op: nothing to inject");
            }
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
            if (op != null && op.sideEffects() != null
                    && op.sideEffects().contains(Protocol.SideEffect.PLAYER_INPUT)) {
                // Audited after clamping: the line records the values actually handed to the client.
                Allowance allowance = policy.allowanceFor(op, session, activation, clock, clamped.command(),
                        decision.reason());
                allowances.add(allowance);
                audit.allowance(allowance);   // loud by design: who, which host, when, which token, what
            }
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

        public List<Allowance> allowances() {
            return List.copyOf(allowances);
        }

        public InputCommand lastCommand() {
            return lastCommand;
        }
    }
}
