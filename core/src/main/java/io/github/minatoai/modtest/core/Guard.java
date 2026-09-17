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
            return new Protocol.ProtocolException(
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
            throw new Protocol.ProtocolException(
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
            return format("ALLOWED-INPUT");
        }

        /**
         * The same line with an explicit prefix, so every write-op class is greppable on its own
         * ({@code ALLOWED-INPUT}, {@code ALLOWED-MUTATION}, …) while the fields stay identical.
         */
        public String format(String prefix) {
            return prefix + " executor=" + executorId + " host=" + (host == null ? "singleplayer" : host)
                    + " dimension=" + dimension + " at=" + atMs + " token=" + tokenFingerprint
                    + " op=" + op + " cmd=[" + command + "] reason=" + reason;
        }
    }

    /**
     * Where allowances go: one <b>already formatted</b> line per allowance. The writer that made the
     * decision owns the prefix, so a sink cannot re-label it ({@code ALLOWED-INPUT} for input,
     * {@code ALLOWED-MUTATION} for an inventory/use write).
     */
    public interface AuditSink {
        void line(String line);

        /** A sink that wants the structured record too can override this. */
        default void allowance(Allowance allowance) {
            line(allowance.format());
        }

        static AuditSink to(java.util.function.Consumer<String> sink) {
            return sink::accept;
        }
    }
    /**
     * The one encoding point for the <b>guard of a non-input write op</b> ({@code inv.click},
     * {@code inv.toss}, {@code use.item}): decide, audit every allowance exactly once, throw on a
     * refusal — and leave <b>no trace at all</b> for a refusal.
     *
     * <p>Why this exists next to {@link GuardedInputWriter}: that writer is built to carry an
     * {@link InputCommand}, so it treats an op without {@code PLAYER_INPUT} as "nothing to inject"
     * and never reaches the policy. Inventory/use ops therefore need their own path, but they must
     * not get their own <i>rules</i>: the tier order, the token rule, the host allow-list and the
     * audit format all come from {@link InputInjectionPolicy} and {@link Allowance} unchanged.
     *
     * <p><b>Deny-by-default:</b> {@link #failClosed} is what an unwired executor gets. It allows
     * nothing and names the missing wiring, so a build that forgot to construct the guard refuses
     * these ops instead of executing them unaudited.
     */
    public static final class MutationGuard {
        private final InputInjectionPolicy policy;
        private final SessionState session;
        private final ActivationState activation;
        private final Bridge.Clock clock;
        private final AuditSink audit;
        private final java.util.List<Allowance> allowances = new ArrayList<>();
        private final java.util.List<String> lines = new ArrayList<>();
        private final String auditPrefix;
        private final String unwiredReason;

        /**
         * The only constructor: the audit sink is mandatory, for the same reason it is on
         * {@link GuardedInputWriter} — an allowance that goes unlogged is a safety defect, so it
         * must not be possible to construct this class without somewhere to write it.
         */
        public MutationGuard(InputInjectionPolicy policy, SessionState session, ActivationState activation,
                             Bridge.Clock clock, AuditSink audit) {
            this(policy, session, activation, clock, audit, "ALLOWED-MUTATION", null);
        }

        /** As above, with an explicit audit line prefix (one greppable family per write-op class). */
        public MutationGuard(InputInjectionPolicy policy, SessionState session, ActivationState activation,
                             Bridge.Clock clock, AuditSink audit, String auditPrefix) {
            this(policy, session, activation, clock, audit, auditPrefix, null);
        }

        private MutationGuard(InputInjectionPolicy policy, SessionState session, ActivationState activation,
                              Bridge.Clock clock, AuditSink audit, String auditPrefix, String unwiredReason) {
            if (audit == null) {
                throw new IllegalArgumentException(
                        "an audit sink is mandatory: an allowance must never go unlogged");
            }
            this.policy = policy;
            this.session = session;
            this.activation = activation;
            this.clock = clock;
            this.audit = audit;
            this.auditPrefix = auditPrefix == null ? "ALLOWED-MUTATION" : auditPrefix;
            this.unwiredReason = unwiredReason;
        }

        /**
         * The state an executor gets when it never constructed a {@link MutationGuard}: nothing is
         * permitted, and the refusal names the missing connection. Never silently "allow" — an
         * unwired adapter would otherwise be the one build that skips the guard unnoticed.
         */
        public static MutationGuard failClosed(String why) {
            return new MutationGuard(
                    new InputInjectionPolicy(HostWhitelist.empty(), BuildVariant.GUARDED, "unwired",
                            () -> "unknown"),
                    SessionState.of(false, true, false, true, false, "unwired"),
                    ActivationState.off(), () -> 0L, Guard.MutationGuard::discardLine, "ALLOWED-MUTATION",
                    "the write-op guard is not wired for this executor: " + why);
        }

        /**
         * The sink of the fail-closed placeholder. It can never be reached: the placeholder refuses
         * before any allowance is built, and a test asserts that it stays empty.
         */
        static void discardLine(String line) {
            throw new IllegalStateException("the fail-closed guard must never allow anything");
        }

        /**
         * Decides, audits on the allowed path, and throws {@code E_PRECONDITION} on a refusal.
         *
         * @param what short human description of the pending write, recorded in the audit line
         * @return the allowed (possibly no-op) decision; a refusal never returns
         */
        public Decision requireAllowed(Protocol.OpSpec op, String opName, String what) {
            Decision decision = submit(op, opName, what);
            Guard.requireAllowed(decision, opName);
            return decision;
        }

        /** The policy decision plus, on the allowed path only, exactly one audit line. */
        public Decision submit(Protocol.OpSpec op, String opName, String what) {
            if (unwiredReason != null) {
                return Decision.deny(unwiredReason);
            }
            Decision decision = policy.decide(op, session, activation, clock);
            if (!decision.allowed()) {
                // A refusal is not an allowance: recording it would bury a real allowance in noise.
                return decision;
            }
            if (decision.noop()) {
                // Allowed, but nothing is going to be written (paused / handshake / not in a world).
                // There is no write to be accountable for, so there is no allowance to record.
                return decision;
            }
            ActivationToken token = activation == null ? null : activation.token();
            Allowance allowance = new Allowance(policy.owner(), session == null ? null : session.serverAddress(),
                    "unknown", clock.nowMs(), token == null ? "none" : token.fingerprint(),
                    op == null ? opName : op.name(), what == null ? "none" : what, decision.reason());
            allowances.add(allowance);
            String line = allowance.format(auditPrefix);
            lines.add(line);
            // Exactly one call: the line carries the guard's own prefix, and the structured record is
            // kept in this guard. Calling allowance() as well would label the same allowance with the
            // input prefix and emit the "one allowance" as two lines.
            audit.line(line);
            return decision;
        }

        public java.util.List<Allowance> allowances() {
            return java.util.List.copyOf(allowances);
        }

        /** The audit lines this guard emitted, for the tests that assert "exactly one". */
        public java.util.List<String> lines() {
            return java.util.List.copyOf(lines);
        }

        public InputInjectionPolicy policy() {
            return policy;
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

        /** Who this executor is, as recorded in every audit line ("who acted"). */
        public String owner() {
            return executorId;
        }

        public BuildVariant variant() {
            return variant;
        }

        /**
         * What tier 1 means: an op that <b>does not change the player</b>.
         *
         * <p>The rule used to be "anything without {@code PLAYER_INPUT} is read-only", which was true
         * while {@code input.set} was the only op that reached this decision. It stopped being true
         * the moment {@code inv.click} / {@code inv.toss} / {@code use.item} were implemented: they
         * mutate the player without touching an input axis, so a bare {@code !contains(PLAYER_INPUT)}
         * test would have waved them straight past the injection gate — the exact three-tier policy
         * §7.2 requires them to pass.
         *
         * <p>This is an <b>allow-list of the read-only effects, with everything else denied</b>, not
         * the mirror image. The first task-70 revision listed the <i>mutating</i> effects and allowed
         * anything absent from that list, which failed <i>open</i>: a side-effect value added to the
         * vocabulary later would not have been in the list and would have been waved through tier 1,
         * ungated, with nothing failing. Naming the read-only effects instead makes an unclassified
         * value fail closed, which is the posture the rest of this policy already uses (deny by
         * default). {@code render.pipeline} stays read-only: it is local rendering and cannot reach
         * another machine's world.
         */
        static final java.util.Set<Protocol.SideEffect> READ_ONLY_EFFECTS = java.util.Set.of(
                Protocol.SideEffect.NONE, Protocol.SideEffect.TELEMETRY_RECORDING,
                Protocol.SideEffect.RENDER_PIPELINE);

        /**
         * True when the op can change this player or their world, and must be gated as a write.
         *
         * <p>Decided by {@link #READ_ONLY_EFFECTS}: a value this class has never seen is treated as a
         * write. {@code SideEffectClassificationTest} pins the allow-list by name and walks every value
         * of the vocabulary, so extending the enum without classifying the new value cannot pass
         * unnoticed.
         */
        public static boolean mutatesThePlayer(Protocol.OpSpec op) {
            if (op == null || op.sideEffects() == null) {
                return false;
            }
            for (Protocol.SideEffect s : op.sideEffects()) {
                if (!READ_ONLY_EFFECTS.contains(s)) {
                    return true;
                }
            }
            return false;
        }

        public Decision decide(Protocol.OpSpec op, SessionState session, ActivationState activation,
                               Bridge.Clock clock) {
            if (session == null) {
                return Decision.deny("refused: the session state is unavailable, so ownership cannot be "
                        + "established; refusing instead of assuming it is allowed");
            }
            if (session.handshakeInProgress()) {
                return Decision.noop("handshake in progress");
            }
            if (!session.inWorld()) {
                return Decision.noop("not in a world");
            }
            if (session.paused()) {
                return Decision.noop("client paused");
            }
            // Tier 1: ops that change nothing are never gated by the injection policy.
            if (!mutatesThePlayer(op)) {
                return Decision.allow("read-only op (no player-mutating side effect)");
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
            // This writer only ever carries input. An op that does not write player input is allowed
            // by the policy but has nothing to inject, so it must not reach the client. "Read-only"
            // is decided by the mutating vocabulary, not by "is it input" — an inventory op is a
            // write, but it is not this writer's write.
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
