package io.github.minatoai.modtest.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * M3 ticket-executor: op catalog, precondition/side-effect gates, {@code expect} assertions and
 * receipt assembly with strict alignment to the ticket.
 */
public final class Executor {
    private Executor() {
    }

    /** An op implementation. Handlers are pure with respect to the bridge: they return JSON. */
    public interface OpHandler {
        JsonObject handle(Protocol.Ticket.Op op, ExecContext ctx);
    }

    /** Everything a handler may need. Nothing here can write player input directly. */
    public static final class ExecContext {
        private final Bridge.BridgeConfig config;
        private final Guard.SessionState session;
        private final Guard.ActivationState activation;
        private final Bridge.Clock clock;
        private final Map<String, Boolean> flags;
        private final Map<String, JsonObject> priorResults;
        private final ClientModel client;
        private final Guard.MutationGuard mutationGuard;

        public ExecContext(Bridge.BridgeConfig config, Guard.SessionState session,
                           Guard.ActivationState activation, Bridge.Clock clock,
                           Map<String, Boolean> flags, ClientModel client) {
            this(config, session, activation, clock, flags, client, null);
        }

        /**
         * As above, plus the guard that non-input write ops ({@code inv.click}, {@code inv.toss},
         * {@code use.item}) must pass through.
         *
         * <p>A {@code null} guard is <b>not</b> "no guard": {@link #mutationGuard()} substitutes a
         * fail-closed one, so an executor that was never wired refuses those ops (with a message
         * saying the guard is missing) instead of performing an unaudited write.
         */
        public ExecContext(Bridge.BridgeConfig config, Guard.SessionState session,
                           Guard.ActivationState activation, Bridge.Clock clock,
                           Map<String, Boolean> flags, ClientModel client,
                           Guard.MutationGuard mutationGuard) {
            this.config = config;
            this.session = session;
            this.activation = activation;
            this.clock = clock;
            this.flags = flags == null ? Map.of() : flags;
            this.priorResults = new LinkedHashMap<>();
            this.client = client;
            this.mutationGuard = mutationGuard;
        }

        public Bridge.BridgeConfig config() {
            return config;
        }

        public Guard.SessionState session() {
            return session;
        }

        public Guard.ActivationState activation() {
            return activation;
        }

        public Bridge.Clock clock() {
            return clock;
        }

        public ClientModel client() {
            return client;
        }

        /**
         * The guard for non-input write ops. Never {@code null}: an unwired context reports a
         * fail-closed guard whose refusal names the missing wiring.
         */
        public Guard.MutationGuard mutationGuard() {
            return mutationGuard != null ? mutationGuard
                    : Guard.MutationGuard.failClosed("ExecContext was built without a MutationGuard");
        }

        /** True when a real guard was wired; tests use it to assert the unwired path refuses. */
        public boolean mutationGuardWired() {
            return mutationGuard != null;
        }

        public boolean flag(String name) {
            return flags.getOrDefault(name, false);
        }

        public void recordResult(String opId, JsonObject result) {
            priorResults.put(opId, result);
        }

        public Map<String, JsonObject> priorResults() {
            return priorResults;
        }

        public ExecContext withFlag(String name, boolean value) {
            Map<String, Boolean> copy = new LinkedHashMap<>(flags);
            copy.put(name, value);
            return new ExecContext(config, session, activation, clock, copy, client, mutationGuard);
        }
    }

    /** Registry of op descriptions plus their handlers. This is the extension point. */
    public static final class OpCatalog {
        private final Map<String, Protocol.OpSpec> specs = new LinkedHashMap<>();
        private final Map<String, OpHandler> handlers = new LinkedHashMap<>();
        private final String executorId;
        private final String executorVersion;
        private final String impl;

        public OpCatalog(String executorId, String executorVersion, String impl) {
            this.executorId = executorId;
            this.executorVersion = executorVersion;
            this.impl = impl;
        }

        public OpCatalog register(Protocol.OpSpec spec, OpHandler handler) {
            specs.put(spec.name(), spec);
            handlers.put(spec.name(), handler);
            return this;
        }

        public Protocol.OpSpec lookup(String name) {
            return specs.get(name);
        }

        /**
         * The spec of a registered op, for handlers that must hand it to the guard (the guard decides
         * on {@code sideEffects}). {@code null} when the op is not registered.
         */
        public Protocol.OpSpec specFor(String name) {
            return specs.get(name);
        }

        public OpHandler handler(String name) {
            return handlers.get(name);
        }

        public List<String> names() {
            return new ArrayList<>(specs.keySet());
        }

        public List<Protocol.OpSpec> specs() {
            return new ArrayList<>(specs.values());
        }

        public JsonObject catalogJson() {
            JsonObject root = Json.object();
            root.addProperty("protocol", Protocol.ID);
            JsonArray protocols = Json.array();
            protocols.add(Protocol.ID);
            root.add("protocols", protocols);
            root.addProperty("catalog_version", executorVersion);
            JsonObject ex = Json.object();
            ex.addProperty("id", executorId);
            ex.addProperty("version", executorVersion);
            ex.addProperty("impl", impl);
            root.add("executor", ex);
            JsonArray ops = Json.array();
            for (Protocol.OpSpec spec : specs.values()) {
                ops.add(spec.toJson());
            }
            root.add("ops", ops);
            return root;
        }

        public String catalogJsonText() {
            return Json.pretty(catalogJson());
        }
    }

    /** §6.3 precondition evaluation. */
    public static final class PreconditionEvaluator {

        public Protocol.ProtocolException check(List<Protocol.Precondition> preconditions, ExecContext ctx) {
            for (Protocol.Precondition p : preconditions) {
                String kind = p.kind();
                switch (kind) {
                    case "singleplayer" -> {
                        if (!ctx.session().hasIntegratedServer() || ctx.session().connectedToRemoteServer()) {
                            return new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                                    "requires a strictly single-player session");
                        }
                    }
                    case "permitted-session" -> {
                        // Default deny, plus the hosts the operator explicitly declared as theirs.
                        if (!ctx.config().allowedHosts().permits(ctx.session())) {
                            String host = ctx.session().serverAddress();
                            return new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                                    "requires a single-player world or a whitelisted host (host '"
                                            + (host == null ? "unknown" : host) + "' is not whitelisted)");
                        }
                    }
                    case "flag" -> {
                        String name = p.payload() == null ? null : Json.str(p.payload(), "name", null);
                        if (name == null || !ctx.flag(name)) {
                            return new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                                    "requires flag '" + name + "'");
                        }
                    }
                    case "op" -> {
                        String name = p.payload() == null ? null : Json.str(p.payload(), "name", null);
                        if (name == null || !ctx.priorResults().containsKey(name)) {
                            return new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                                    "requires op result '" + name + "'");
                        }
                    }
                    case "permission" -> throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                            "permission '" + (p.payload() == null ? "?" : Json.str(p.payload(), "node", "?"))
                                    + "' not held");
                    case "dimension" -> {
                        String want = p.payload() == null ? null : Json.str(p.payload(), "id", null);
                        if (want == null || !want.equals(ctx.client().dimension())) {
                            return new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                                    "requires dimension " + want);
                        }
                    }
                    case "item" -> {
                        String want = p.payload() == null ? null : Json.str(p.payload(), "id", null);
                        if (want == null || !want.equals(ctx.client().heldItemId())) {
                            return new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                                    "requires holding " + want);
                        }
                    }
                    default -> throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                            "unknown precondition kind: " + kind);
                }
            }
            return null;
        }
    }

    /** §4.3 {@code expect} assertions (nine comparison operators). */
    public static final class ExpectEngine {
        public static final List<String> OPERATORS =
                List.of("eq", "ne", "gt", "gte", "lt", "lte", "exists", "matches", "in");

        /** Throws {@code E_ASSERT} on the first failing assertion. */
        public void evaluate(JsonObject result, JsonObject expect) {
            if (expect == null || expect.size() == 0) {   // size(), not isEmpty(): Gson 2.10 needs it
                return;
            }
            for (Map.Entry<String, JsonElement> e : expect.entrySet()) {
                String path = e.getKey();
                JsonObject rule = e.getValue().getAsJsonObject();
                String op = Json.str(rule, "op", "eq");
                JsonElement want = rule.get("value");
                JsonElement actual = Json.path(result == null ? Json.object() : result, path);
                if (!apply(op, actual, want)) {
                    throw new Protocol.ProtocolException(Protocol.ErrorCode.E_ASSERT,
                            "assertion failed: " + path + " " + op + " " + want)
                            .with("failed", path);
                }
            }
        }

        boolean apply(String op, JsonElement actual, JsonElement want) {
            switch (op) {
                case "exists": {
                    // value defaults to true; value:false asserts absence.
                    boolean wanted = want == null || want.isJsonNull() || want.getAsBoolean();
                    boolean present = actual != null && !actual.isJsonNull();
                    return wanted == present;
                }
                case "eq":
                    return actual != null && actual.equals(want);
                case "ne":
                    return actual != null && !actual.equals(want);
                case "gt":
                case "gte":
                case "lt":
                case "lte": {
                    if (actual == null || want == null || !actual.isJsonPrimitive() || !want.isJsonPrimitive()) {
                        return false;
                    }
                    double a = actual.getAsDouble();
                    double b = want.getAsDouble();
                    return switch (op) {
                        case "gt" -> a > b;
                        case "gte" -> a >= b;
                        case "lt" -> a < b;
                        default -> a <= b;
                    };
                }
                case "matches":
                    return actual != null && actual.isJsonPrimitive() && want != null
                            && actual.getAsString().matches(want.getAsString());
                case "in": {
                    if (actual == null || want == null || !want.isJsonArray()) {
                        return false;
                    }
                    for (JsonElement item : want.getAsJsonArray()) {
                        if (item.equals(actual)) {
                            return true;
                        }
                    }
                    return false;
                }
                default:
                    throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                            "unknown expect operator: " + op);
            }
        }
    }

    /** Runs a validated ticket and produces an aligned receipt. */
    public static final class TicketExecutor {
        private final Bridge.BridgeConfig config;
        private final OpCatalog catalog;
        private final VanillaOps.OpHooks hooks;
        private final PreconditionEvaluator preconditions = new PreconditionEvaluator();
        private final ExpectEngine expect = new ExpectEngine();

        public TicketExecutor(Bridge.BridgeConfig config, OpCatalog catalog) {
            this(config, catalog, VanillaOps.defaultHooks());
        }

        public TicketExecutor(Bridge.BridgeConfig config, OpCatalog catalog, VanillaOps.OpHooks hooks) {
            this.config = config;
            this.catalog = catalog;
            this.hooks = hooks;
        }

        public Protocol.Receipt execute(Protocol.Ticket ticket, Relay.OpLog log) {
            return execute(ticket, log, null);
        }

        /** Executes with a caller-supplied context (used by tests to inject a session/client). */
        public Protocol.Receipt execute(Protocol.Ticket ticket, Relay.OpLog log, ExecContext ctx) {
            long started = ctx == null ? System.currentTimeMillis() : ctx.clock().nowMs();
            List<Protocol.Receipt.OpResult> results = new ArrayList<>();
            Protocol.Receipt.Error receiptError = null;
            boolean aborted = false;
            for (Protocol.Ticket.Op op : ticket.ops()) {
                if (aborted) {
                    results.add(Protocol.Receipt.OpResult.skipped(op.id(), op.op()));
                    continue;
                }
                Protocol.OpSpec spec = catalog.lookup(op.op());
                if (spec == null) {
                    results.add(Protocol.Receipt.OpResult.failed(op.id(), op.op(),
                            Protocol.Receipt.Error.of(Protocol.ErrorCode.E_UNKNOWN_OP, "unknown op: " + op.op()), 0L));
                    if ("abort".equals(ticket.onErrorFor(op))) {
                        aborted = true;
                    }
                    continue;
                }
                long opStart = ctx == null ? System.currentTimeMillis() : ctx.clock().nowMs();
                Protocol.Receipt.OpResult result = runOp(spec, op, ctx, log);
                results.add(result);
                if (result.ok() && result.result() != null && ctx != null) {
                    ctx.recordResult(op.id(), result.result());
                }
                if (!result.ok() && "abort".equals(ticket.onErrorFor(op))) {
                    aborted = true;
                    receiptError = result.error();
                }
            }
            long finished = ctx == null ? System.currentTimeMillis() : ctx.clock().nowMs();
            boolean ok = results.stream().allMatch(Protocol.Receipt.OpResult::ok);
            return new Protocol.Receipt(Protocol.ID, ticket.ticket(), ticket.trial(), ok,
                    new Protocol.Receipt.ExecutorInfo(config.executorId(), config.executorVersion(), null),
                    null, null, Math.max(0L, finished - started), receiptError, List.copyOf(results));
        }

        private Protocol.Receipt.OpResult runOp(Protocol.OpSpec spec, Protocol.Ticket.Op op,
                                                ExecContext ctx, Relay.OpLog log) {
            long start = ctx == null ? System.currentTimeMillis() : ctx.clock().nowMs();
            if (ctx == null) {
                return Protocol.Receipt.OpResult.failed(op.id(), op.op(),
                        Protocol.Receipt.Error.of(Protocol.ErrorCode.E_EXEC, "no execution context"), 0L);
            }
            try {
                Protocol.ProtocolException pre = preconditions.check(spec.preconditions(), ctx);
                if (pre != null) {
                    return log(log, op, Protocol.Receipt.OpResult.failed(op.id(), op.op(), pre.toError(), 0L));
                }
                if (spec.mutating()) {
                    if (!config.allowMutate()) {
                        return log(log, op, Protocol.Receipt.OpResult.failed(op.id(), op.op(),
                                Protocol.Receipt.Error.of(Protocol.ErrorCode.E_PRECONDITION,
                                        "mutating op requires allow-mutate"), 0L));
                    }
                    if (!config.allowedHosts().permits(ctx.session())) {
                        String host = ctx.session().serverAddress();
                        return log(log, op, Protocol.Receipt.OpResult.failed(op.id(), op.op(),
                                Protocol.Receipt.Error.of(Protocol.ErrorCode.E_PRECONDITION,
                                        "mutating op refused: host '" + (host == null ? "unknown" : host)
                                                + "' is not whitelisted and this is not a single-player world"),
                                0L));
                    }
                }
                OpHandler handler = catalog.handler(op.op());
                if (handler == null) {
                    return log(log, op, Protocol.Receipt.OpResult.failed(op.id(), op.op(),
                            Protocol.Receipt.Error.of(Protocol.ErrorCode.E_UNSUPPORTED, "no handler"), 0L));
                }
                // Table-driven before-op hooks run BEFORE the handler (e.g. releasing a using
                // item before the inventory is mutated); notes are attached to the result.
                java.util.List<String> notes = hooks.run(op.op(), op, ctx);
                JsonObject result = handler.handle(op, ctx);
                if (!notes.isEmpty()) {
                    JsonArray arr = Json.array();
                    notes.forEach(arr::add);
                    result.add("before_op", arr);
                }
                long dur = ctx.clock().nowMs() - start;
                Integer budget = op.timeoutMs() != null ? op.timeoutMs() : null;
                if (budget != null && dur > budget) {
                    return log(log, op, Protocol.Receipt.OpResult.failed(op.id(), op.op(),
                            Protocol.Receipt.Error.of(Protocol.ErrorCode.E_TIMEOUT, "op budget exceeded"), dur));
                }
                expect.evaluate(result, op.expect());
                return log(log, op, Protocol.Receipt.OpResult.ok(op.id(), op.op(), result, dur));
            } catch (Protocol.ProtocolException e) {
                long dur = ctx.clock().nowMs() - start;
                // Backstop with no teeth in the happy path, loud when it matters: the five ops
                // implemented in task-70 must never again come back as "unsupported" merely because
                // the game-side adapter was not wired up yet. Such a receipt is technically a
                // failure (ok:false), but it reads like a protocol gap. Name the defect explicitly.
                if (e.code() == Protocol.ErrorCode.E_UNSUPPORTED && VanillaOps.WIRED_IN_CORE.contains(op.op())) {
                    String line = "NOT-WIRED-DEFECT op=" + op.op()
                            + ": the core implementation exists (task-70), but the client adapter threw "
                            + "E_UNSUPPORTED for it: " + e.getMessage();
                    if (log != null) {
                        log.accept(line);
                    }
                    System.getLogger("modtest-mcp").log(System.Logger.Level.WARNING, line);
                }
                return log(log, op, Protocol.Receipt.OpResult.failed(op.id(), op.op(), e.toError(), dur));
            } catch (RuntimeException e) {
                long dur = ctx.clock().nowMs() - start;
                return log(log, op, Protocol.Receipt.OpResult.failed(op.id(), op.op(),
                        Protocol.Receipt.Error.of(Protocol.ErrorCode.E_EXEC,
                                e.getClass().getSimpleName() + ": " + e.getMessage()), dur));
            }
        }

        private Protocol.Receipt.OpResult log(Relay.OpLog log, Protocol.Ticket.Op op,
                                             Protocol.Receipt.OpResult result) {
            if (log != null) {
                log.accept("op=" + op.op() + " opId=" + op.id() + " result=" + (result.ok() ? "ok" : "fail")
                        + (result.error() == null ? "" : " code=" + result.error().code())
                        + " durMs=" + result.durationMs());
            }
            return result;
        }
    }

    /** Consumer that swallows log lines; handy in tests that only assert on receipts. */
    public static Relay.OpLog quietLog() {
        return line -> {
        };
    }
}
