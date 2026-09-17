package io.github.minatoai.modtest.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3 op set: the vanilla-level primitives, registered as data + handlers.
 *
 * <p>Two things here are deliberately load-bearing for the safety story:
 * <ul>
 *   <li>{@link OpHooks} — the before-op hooks are <b>table driven</b> (an op-prefix maps to a list
 *       of hooks) instead of a hardcoded branch, so a provider can add its own hook;</li>
 *   <li>the trap checks in {@link UseOps} / {@link WorldOps} return {@code E_PRECONDITION} /
 *       {@code E_EXEC} instead of silently doing something surprising — each one has a negative
 *       test in {@code TrapTest}.</li>
 * </ul>
 */
public final class VanillaOps {
    private VanillaOps() {
    }

    /**
     * The ops whose <b>core</b> implementation exists but whose client adapter may not be wired yet.
     *
     * <p>Used by the executor to turn a forgetful adapter into a loud, named defect instead of a
     * receipt that reads like a protocol gap. Before task-70 all five were permanent
     * {@code E_UNSUPPORTED} stubs.
     */
    public static final java.util.Set<String> WIRED_IN_CORE =
            java.util.Set.of("inv.click", "inv.toss", "use.item", "shot.capture", "bench.read");

    /** Click modes accepted by {@code inv.click}; the wire vocabulary is closed. */
    public static final java.util.Set<String> CLICK_MODES =
            java.util.Set.of("pickup", "quick_move", "swap", "clone", "throw", "quick_craft", "pickup_all");

    /** How many frame samples one {@code bench.read} may ask for. */
    public static final int MAX_SAMPLE_FRAMES = 600;
    /** Upper bound on {@code warmup_frames + sample_frames}: keeps one op within a bounded budget. */
    public static final int MAX_TOTAL_FRAMES = 900;

    /**
     * A one-shot "the adapter refused this" error carried out of a {@link ClientModel} call.
     *
     * <p>Handlers catch it and re-encode it as the receipt's own code, so a {@link ClientModel} that
     * is not implemented yet produces a structured {@code E_UNSUPPORTED} failure (never a stack
     * trace, never a fabricated result).
     */
    public static final class AdapterRefusedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Protocol.ErrorCode code;
        private final String detail;

        public AdapterRefusedException(Protocol.ErrorCode code, String message) {
            super(message);
            this.code = code;
            this.detail = message;
        }

        public Protocol.ErrorCode code() {
            return code;
        }

        public String detail() {
            return detail;
        }

        /**
         * Runs an adapter call, converting "this adapter does not implement it (yet)" into a
         * structured refusal.
         *
         * <p>Only the <i>absence</i> of an implementation is re-labelled: an adapter that reports a
         * precondition it discovered itself ({@code E_PRECONDITION}) or a timeout it measured
         * ({@code E_TIMEOUT}) is passed through untouched — the adapter knows the game, the core only
         * knows the contract. Anything else (a genuine runtime failure) also passes through, so the
         * executor still turns it into {@code E_EXEC}.
         */
        public static void call(Runnable body) {
            try {
                body.run();
            } catch (Protocol.ProtocolException e) {
                if (e.code() == Protocol.ErrorCode.E_UNSUPPORTED || e.code() == null) {
                    throw new AdapterRefusedException(e.code(), e.getMessage());
                }
                throw e;
            } catch (UnsupportedOperationException e) {
                throw new AdapterRefusedException(Protocol.ErrorCode.E_UNSUPPORTED,
                        e.getMessage() == null ? "not implemented by this client adapter" : e.getMessage());
            }
        }

        /** The structured protocol error a handler rethrows once the guard has allowed the write. */
        public Protocol.ProtocolException toProtocolException() {
            return new Protocol.ProtocolException(code == null ? Protocol.ErrorCode.E_UNSUPPORTED : code,
                    detail == null ? "refused by the client adapter" : detail);
        }
    }

    /** A hook that runs once before an op executes; results are appended to a trace. */
    public interface BeforeOpHook {
        /** @return a short note (for the receipt) or {@code null} if nothing was done */
        String before(Protocol.Ticket.Op op, Executor.ExecContext ctx);
    }

    /** Table-driven hook registry: {@code inv.} matches {@code inv.select}, {@code inv.click}, … */
    public static final class OpHooks {
        private final Map<String, List<BeforeOpHook>> byPrefix = new LinkedHashMap<>();

        public OpHooks on(String prefix, BeforeOpHook hook) {
            byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(hook);
            return this;
        }

        public List<String> run(String opName, Protocol.Ticket.Op op, Executor.ExecContext ctx) {
            List<String> notes = new ArrayList<>();
            for (Map.Entry<String, List<BeforeOpHook>> e : byPrefix.entrySet()) {
                if (opName.startsWith(e.getKey())) {
                    for (BeforeOpHook hook : e.getValue()) {
                        String note = hook.before(op, ctx);
                        if (note != null) {
                            notes.add(note);
                        }
                    }
                }
            }
            return notes;
        }
    }

    /** The default hook table: mutating the inventory while using an item releases it first. */
    public static OpHooks defaultHooks() {
        return new OpHooks().on("inv.", (op, ctx) -> {
            ClientModel client = ctx.client();
            if (client.usingItem()) {
                client.releaseUsingItem();
                return "using-released-before-" + op.op();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ specs
    private static Protocol.OpSpec spec(String name, String title, JsonObject params, JsonObject result,
                                        List<Protocol.Precondition> pre, List<Protocol.SideEffect> effects) {
        return new Protocol.OpSpec(name, title, params, result, pre, effects, "vanilla-client", null, "1.0");
    }

    /** A closed object schema whose listed keys are all required (the pre-task-70 shape). */
    private static JsonObject schema(String... keys) {
        JsonObject props = Json.object();
        JsonArray required = Json.array();
        for (String key : keys) {
            props.add(key, Json.object());
            required.add(key);
        }
        JsonObject s = Json.object();
        s.addProperty("type", "object");
        s.add("properties", props);
        s.add("required", required);
        s.addProperty("additionalProperties", false);
        return s;
    }

    private static JsonObject prop(String name, String type) {
        JsonObject p = Json.object();
        p.addProperty("name", name);
        p.addProperty("type", type);
        return p;
    }

    private static JsonObject prop(String name, String type, java.util.Set<String> allowed) {
        JsonObject p = prop(name, type);
        JsonArray values = Json.array();
        for (String v : new java.util.TreeSet<>(allowed)) {
            values.add(v);
        }
        p.add("enum", values);
        return p;
    }

    /** A closed object schema whose properties are optional ("absent means default"). */
    private static JsonObject objectSchema(JsonObject... props) {
        JsonObject properties = Json.object();
        for (JsonObject p : props) {
            String name = p.get("name").getAsString();
            JsonObject clean = p.deepCopy();
            clean.remove("name");
            properties.add(name, clean);
        }
        JsonObject s = Json.object();
        s.addProperty("type", "object");
        s.add("properties", properties);
        s.addProperty("additionalProperties", false);
        return s;
    }

    /**
     * A result schema: the listed properties are declared and {@code ok:true} receipts always carry
     * them, but {@code additionalProperties} stays open because a refusal returns no result at all
     * and a future field must not be a version bump.
     */
    private static JsonObject resultSchema(JsonObject properties, String... alwaysPresent) {
        JsonObject s = Json.object();
        s.addProperty("type", "object");
        s.add("properties", properties);
        JsonArray required = Json.array();
        for (String r : alwaysPresent) {
            required.add(r);
        }
        s.add("required", required);
        s.addProperty("additionalProperties", true);
        return s;
    }

    private static JsonObject objectOf(JsonObject... props) {
        JsonObject properties = Json.object();
        for (JsonObject p : props) {
            String name = p.get("name").getAsString();
            JsonObject clean = p.deepCopy();
            clean.remove("name");
            properties.add(name, clean);
        }
        return properties;
    }

    /** A required integer param; a missing or non-numeric value is {@code E_BAD_PARAMS}. */
    private static int requireInt(JsonObject p, String key, String opName) {
        com.google.gson.JsonElement e = p.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                    opName + ": param '" + key + "' is required and must be a number")
                    .with("params", key);
        }
        return e.getAsInt();
    }

    private static int intOr(JsonObject p, String key, int fallback) {
        return Json.intOr(p, key, fallback);
    }

    /**
     * The spec the guard decides on.
     *
     * <p>Read from the catalog that registered this op, so the side-effect list a handler is gated by
     * is exactly the one published in {@code catalog.json} — a handler cannot quietly gate itself on a
     * different list than the one an agent saw. A missing entry is a registration bug and refuses.
     */
    private static Protocol.OpSpec spec(Executor.ExecContext ctx, String opName) {
        Protocol.OpSpec spec = INSTALLED.get(opName);
        if (spec == null) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_EXEC,
                    "no registered OpSpec for " + opName + ": the guard cannot evaluate side effects");
        }
        return spec;
    }

    /**
     * Every spec this class registered, by op name.
     *
     * <p>Handlers look their own spec up here (instead of receiving it) because the executor calls a
     * handler with only the ticket op and the context, and passing the catalog through the context
     * would change a constructor every test in the suite already uses. The map is filled by
     * {@link #install}, which is the only way any of these ops can be reached, and it holds the
     * <i>same</i> {@link Protocol.OpSpec} instances the catalog published — not copies.
     */
    private static final Map<String, Protocol.OpSpec> INSTALLED = new LinkedHashMap<>();

    /** The spec registered for an op, or {@code null} when the op set was never installed. */
    public static Protocol.OpSpec installedSpec(String opName) {
        return INSTALLED.get(opName);
    }

    private static Executor.OpCatalog add(Executor.OpCatalog catalog, Protocol.OpSpec spec,
                                         Executor.OpHandler handler) {
        INSTALLED.put(spec.name(), spec);
        return catalog.register(spec, handler);
    }

    /**
     * Fills the receipt of a write op the guard allowed as a <b>no-op</b> (paused / handshake / not in a
     * world): the op executed, but <b>no write was dispatched</b>.
     *
     * <p>Two invariants decide the shape. Nothing reaches the client, because a write on this path
     * would never have been turned into an allowance and would therefore never appear in the audit log.
     * And the verdict may never be {@code applied}: it says {@code skipped} with the policy's own
     * reason, so {@code ok:true} reads as "the op ran", never "the write happened" ({@code PROTOCOL.md}
     * §6.2a). {@code skipped} is deliberately an <i>existing</i> verdict value — no new vocabulary is
     * invented for "the guard said there was nothing to do".
     */
    private static JsonObject guardNoOp(JsonObject out, String what, Guard.Decision decision) {
        out.addProperty("verdict", "skipped");
        out.add("applied", Json.object());
        out.add("notClientVerifiable", Json.object());
        JsonObject entry = Json.object();
        entry.addProperty("requested", what);
        entry.addProperty("reason", "guard no-op: " + decision.reason());
        JsonObject skipped = Json.object();
        skipped.add("dispatch", entry);
        out.add("skipped", skipped);
        out.addProperty("note", "the op executed and the guard allowed it as a no-op ("
                + decision.reason() + "): nothing was dispatched to the client and no audit line "
                + "was written, because no write happened");
        return out;
    }

    /** Registers the vanilla op set. */
    public static Executor.OpCatalog install(Executor.OpCatalog catalog) {
        add(catalog, spec("state.query", "Read player/world state", schema("what"), null,
                        List.of(), List.of(Protocol.SideEffect.NONE)),
                new StateOps());
        add(catalog, spec("pose.set", "Teleport/rotate with settle", schema("x", "y", "z", "yaw", "pitch"), null,
                        List.of(Protocol.Precondition.of("permitted-session"), Protocol.Precondition.of("flag", "name", "allow-mutate")),
                        List.of(Protocol.SideEffect.PLAYER_STATE)), new PoseOps());
        add(catalog, spec("inv.select", "Select a hotbar slot", schema("slot"), null,
                        List.of(Protocol.Precondition.of("permitted-session"), Protocol.Precondition.of("flag", "name", "allow-mutate")),
                        List.of(Protocol.SideEffect.PLAYER_INVENTORY)), new InvOps("select"));
        add(catalog, spec("inv.click", "Click an inventory slot", objectSchema(
                                prop("slot", "integer"), prop("button", "integer"),
                                prop("mode", "string", CLICK_MODES)),
                        resultSchema(objectOf(
                                prop("verdict", "string", java.util.Set.of("applied", "skipped", "notClientVerifiable")),
                                prop("windowId", "integer"), prop("slot", "integer"), prop("mode", "string"),
                                prop("before", "object"), prop("after", "object"),
                                prop("cursorBefore", "object"), prop("cursorAfter", "object"),
                                prop("applied", "object"), prop("notClientVerifiable", "object"),
                                prop("skipped", "object"), prop("note", "string")), "slot"),
                        List.of(Protocol.Precondition.of("permitted-session"), Protocol.Precondition.of("flag", "name", "allow-mutate")),
                        List.of(Protocol.SideEffect.PLAYER_INVENTORY)), new InvOps("click"));
        add(catalog, spec("inv.toss", "Drop items from a slot", objectSchema(
                                prop("slot", "integer"), prop("count", "integer")),
                        resultSchema(objectOf(
                                prop("verdict", "string", java.util.Set.of("applied", "skipped", "notClientVerifiable")),
                                prop("slot", "integer"), prop("requestedCount", "integer"),
                                prop("observedDelta", "integer"), prop("after", "object"),
                                prop("applied", "object"), prop("notClientVerifiable", "object"),
                                prop("skipped", "object"), prop("note", "string")), "slot"),
                        List.of(Protocol.Precondition.of("permitted-session"), Protocol.Precondition.of("flag", "name", "allow-mutate")),
                        List.of(Protocol.SideEffect.PLAYER_INVENTORY)), new InvOps("toss"));
        add(catalog, spec("use.item", "Use the held item", objectSchema(
                                prop("hand", "string", java.util.Set.of("main", "off"))),
                        resultSchema(objectOf(
                                prop("verdict", "string", java.util.Set.of("dispatched", "notDispatched", "notClientVerifiable")),
                                prop("dispatched", "boolean"), prop("hand", "string"),
                                prop("heldBefore", "object"), prop("heldAfter", "object"),
                                prop("usingBefore", "boolean"), prop("usingAfter", "boolean"),
                                prop("cooldownTicks", "integer"),
                                prop("applied", "object"), prop("notClientVerifiable", "object"),
                                prop("skipped", "object"), prop("note", "string"))),
                        List.of(Protocol.Precondition.of("permitted-session"), Protocol.Precondition.of("flag", "name", "allow-mutate")),
                        List.of(Protocol.SideEffect.PLAYER_STATE, Protocol.SideEffect.PLAYER_INVENTORY)), new UseOps());
        add(catalog, spec("world.place", "Place a block", schema("x", "y", "z", "block"), null,
                        List.of(Protocol.Precondition.of("permitted-session"), Protocol.Precondition.of("flag", "name", "allow-mutate")),
                        List.of(Protocol.SideEffect.WORLD_BLOCKS)), new WorldOps());
        add(catalog, spec("shot.capture", "Capture a screenshot and report its bytes",
                        objectSchema(prop("name", "string")),
                        resultSchema(objectOf(
                                prop("bytes", "integer"), prop("format", "string"), prop("width", "integer"),
                                prop("height", "integer"),
                                prop("sha256", "string"), prop("tick", "integer"), prop("path", "string"),
                                prop("note", "string")), "bytes", "format"),
                        List.of(),
                        List.of(Protocol.SideEffect.TELEMETRY_RECORDING)), new MiscOps("shot"));
        add(catalog, spec("bench.read", "Read a frame-time sample", objectSchema(
                                prop("warmup_frames", "integer"), prop("sample_frames", "integer")),
                        resultSchema(objectOf(
                                prop("warmupFrames", "integer"), prop("sampleFrames", "integer"),
                                prop("sampleCount", "integer"), prop("actualSampleCount", "integer"),
                                prop("overranWindow", "boolean"),
                                prop("windowMs", "integer"),
                                prop("fpsMedian", "number"), prop("frameMsP95", "number"),
                                prop("onePercentLow", "number"), prop("units", "object"),
                                prop("samplesPath", "string"), prop("note", "string")),
                                "warmupFrames", "sampleFrames", "sampleCount", "actualSampleCount",
                                "overranWindow", "windowMs", "units"),
                        List.of(), List.of(Protocol.SideEffect.TELEMETRY_RECORDING)),
                new MiscOps("bench"));
        add(catalog, spec("wait.frames", "Wait client frames", schema("frames"), null,
                        List.of(), List.of(Protocol.SideEffect.NONE)),
                new MiscOps("wait"));
        return catalog;
    }

    // ------------------------------------------------------------------ handlers
    /** {@code state.query} */
    public static final class StateOps implements Executor.OpHandler {
        @Override
        public JsonObject handle(Protocol.Ticket.Op op, Executor.ExecContext ctx) {
            ClientModel c = ctx.client();
            JsonArray what = Json.arrOrNull(op.params() == null ? Json.object() : op.params(), "what");
            List<String> want = new ArrayList<>();
            if (what != null) {
                for (JsonElement e : what) {
                    want.add(e.getAsString());
                }
            }
            JsonObject out = Json.object();
            if (want.contains("all") || want.contains("pose")) {
                JsonObject pose = Json.object();
                pose.addProperty("x", c.x());
                pose.addProperty("y", c.y());
                pose.addProperty("z", c.z());
                pose.addProperty("yaw", c.yaw());
                pose.addProperty("pitch", c.pitch());
                out.add("pose", pose);
            }
            if (want.contains("all") || want.contains("held")) {
                out.addProperty("held", c.heldItemId());
            }
            // The off hand is part of "what is the player holding": without it, use.item{hand:"off"}
            // has no observable evidence at all that it acted on the off hand rather than the main one.
            if (want.contains("all") || want.contains("offhand")) {
                out.addProperty("offhand", c.offHandItemId());
            }
            if (want.contains("all") || want.contains("dimension")) {
                out.addProperty("dimension", c.dimension());
            }
            if (want.contains("all") || want.contains("inventory")) {
                JsonArray inv = Json.array();
                for (String s : c.inventory()) {
                    inv.add(s);
                }
                out.add("inventory", inv);
                // An inventory read is an observation with a sync window (P9): say so, so a caller can
                // tell "the slot really is empty" from "the menu had not caught up yet" and re-read.
                out.addProperty("containerSyncPending", c.containerSyncPending());
            }
            return out;
        }
    }

    /**
     * {@code pose.set} — apply, then report what <b>actually took effect</b>.
     *
     * <p>The verdict MUST come from a <b>settled</b> read-back, never from the value immediately after
     * {@code teleport()}. That instantaneous value is only the local/transient pose: the integrated
     * server (single-player included) or the remote server overwrites it on the next tick, which is
     * exactly how a real receipt reported five applied fields while the position never moved.
     *
     * <p>Rule for every "report what happened" op: a field may only be called applied when the pose
     * settled <i>and</i> the settled value matches the request; otherwise it goes to {@code skipped},
     * with {@code reason: "not settled"} when the settle loop timed out.
     */
    public static final class PoseOps implements Executor.OpHandler {
        private static final double COORD_EPS = 1.0e-3;
        private static final double ANGLE_EPS = 1.0e-3;
        /** Bounded settle budget, in client ticks, before a field is declared not to have settled. */
        private static final int CLIENT_SETTLE_TICKS = 4;
        private static final int SERVER_SETTLE_TICKS = 8;
        private static final java.util.List<String> FIELDS =
                java.util.List.of("x", "y", "z", "yaw", "pitch");

        @Override
        public JsonObject handle(Protocol.Ticket.Op op, Executor.ExecContext ctx) {
            JsonObject p = op.params() == null ? Json.object() : op.params();
            ClientModel c = ctx.client();
            double rx = p.get("x").getAsDouble();
            double ry = p.get("y").getAsDouble();
            double rz = p.get("z").getAsDouble();
            float ryaw = p.get("yaw").getAsFloat();
            float rpitch = p.get("pitch").getAsFloat();

            // pose.set is a write (PLAYER_STATE), so it goes through the same single guard point as the
            // other write ops — and a no-op must not teleport.
            Protocol.OpSpec spec = spec(ctx, "pose.set");
            Guard.Decision decision = ctx.mutationGuard().requireAllowed(spec, "pose.set",
                    "pose.set x=" + rx + " y=" + ry + " z=" + rz + " yaw=" + ryaw + " pitch=" + rpitch);
            boolean integrated = ctx.session().hasIntegratedServer() && !ctx.session().connectedToRemoteServer();
            if (decision.noop()) {
                JsonObject current = Json.object();
                current.addProperty("x", c.x());
                current.addProperty("y", c.y());
                current.addProperty("z", c.z());
                current.addProperty("yaw", c.yaw());
                current.addProperty("pitch", c.pitch());
                JsonObject noOpSkipped = Json.object();
                guardNoOpField(noOpSkipped, "x", rx, decision.reason());
                guardNoOpField(noOpSkipped, "y", ry, decision.reason());
                guardNoOpField(noOpSkipped, "z", rz, decision.reason());
                guardNoOpField(noOpSkipped, "yaw", ryaw, decision.reason());
                guardNoOpField(noOpSkipped, "pitch", rpitch, decision.reason());
                JsonObject out = Json.object();
                out.add("pose", current);
                out.addProperty("poseSource", "client-readback");
                out.addProperty("settled", false);
                out.addProperty("verdict", "skipped");
                out.add("applied", Json.object());
                out.add("skipped", noOpSkipped);
                out.add("notClientVerifiable", Json.object());
                out.addProperty("authority", integrated ? "client" : "server");
                out.addProperty("note", "the pose was not applied: the guard allowed the op as a no-op ("
                        + decision.reason() + "), so nothing was teleported and no audit line was written");
                return out;
            }
            c.teleport(rx, ry, rz, ryaw, rpitch, Json.intOr(p, "settle_ms", 250));
            // Wait for the authority to publish: >= 1 client tick always, more for a remote server.
            int budget = integrated ? CLIENT_SETTLE_TICKS : SERVER_SETTLE_TICKS;
            boolean settled = false;
            for (int i = 0; i < budget && !settled; i++) {
                c.waitFrames(1);
                settled = c.settled();
            }

            // Position is NEVER judged from a client reading: the authority publishes its own pose
            // after the settle window (measured: < 0.57 s after pose.set), so any reading taken inside
            // the window — even two identical ones — can be the transient local value. Only
            // client-authoritative rotation is decided from the read-back.
            Reading second = reading(c);
            JsonObject pose = Json.object();
            pose.addProperty("x", second.x());
            pose.addProperty("y", second.y());
            pose.addProperty("z", second.z());
            pose.addProperty("yaw", second.yaw());
            pose.addProperty("pitch", second.pitch());

            JsonObject applied = Json.object();
            JsonObject skipped = Json.object();
            JsonObject notClientVerifiable = Json.object();
            if (settled && integrated) {
                // Single-player: the integrated server is authoritative AND the client can watch the
                // value revert (measured < 0.57 s), so "did not take effect" is the honest verdict.
                serverOwned(skipped, "x", rx, second.x());
                serverOwned(skipped, "y", ry, second.y());
                serverOwned(skipped, "z", rz, second.z());
                // Rotation is client-authoritative, so it may legitimately be reported as applied.
                compareApplied(applied, skipped, "yaw", ryaw, second.yaw(), ANGLE_EPS, true);
                compareApplied(applied, skipped, "pitch", rpitch, second.pitch(), ANGLE_EPS, true);
            } else if (settled) {
                // Remote authority. On a real LAN the position change DID apply and persist (16.4 s,
                // survived a reconnect, confirmed by the server log), but the client cannot witness
                // that. Calling it `skipped` hid a working feature; calling it `applied` would claim
                // something the client cannot see. Hence the third state.
                notVerifiable(notClientVerifiable, "x", rx, second.x());
                notVerifiable(notClientVerifiable, "y", ry, second.y());
                notVerifiable(notClientVerifiable, "z", rz, second.z());
                compareApplied(applied, skipped, "yaw", ryaw, second.yaw(), ANGLE_EPS, false);
                compareApplied(applied, skipped, "pitch", rpitch, second.pitch(), ANGLE_EPS, false);
            } else {
                // Nothing may be reported as applied when the pose never settled.
                notSettled(skipped, "x", rx);
                notSettled(skipped, "y", ry);
                notSettled(skipped, "z", rz);
                notSettled(skipped, "yaw", ryaw);
                notSettled(skipped, "pitch", rpitch);
            }

            // authority + note are derived from the SAME verdict as applied/skipped, so they cannot
            // contradict it (a real receipt once claimed every position field was applied while its
            // own note said the server owns the position).
            String note = (integrated
                    ? "single-player: the integrated server is authoritative too, and the client can "
                            + "watch the value revert (< 0.57 s measured) — position is reported as skipped"
                    : "multiplayer: the server owns the player position; a LAN measurement showed the "
                            + "position change DID apply and persist (16.4 s, survived a reconnect, "
                            + "confirmed by the server log), but the client cannot witness it — position "
                            + "is reported as notClientVerifiable")
                    + "; client position readings cannot be authoritative; position is owned by the server"
                    + (settled
                            ? (applied.size() == FIELDS.size()
                                    ? "; every requested field took effect"
                                    : (skipped.size() > 0
                                            ? "; not applied: " + skipped.keySet()
                                            : "; position is not client-verifiable (see notClientVerifiable)"))
                            : "; the pose did not settle within " + budget
                                    + " client ticks, so nothing is reported as applied");

            JsonObject out = Json.object();
            out.add("pose", pose);
            // The top-level pose is a client-side snapshot as well; without this marker it reads like
            // an authoritative value (a real receipt showed pose.z = 12 while the truth was 2.851…).
            out.addProperty("poseSource", "client-readback");
            out.addProperty("settled", settled);
            out.add("applied", applied);
            out.add("skipped", skipped);
            out.add("notClientVerifiable", notClientVerifiable);
            out.addProperty("authority", integrated ? "client" : "server");
            out.addProperty("note", note);
            return out;
        }

        /** A pose snapshot; its position fields are informational only (the authority owns them). */
        private record Reading(double x, double y, double z, float yaw, float pitch) {
        }

        private static Reading reading(ClientModel c) {
            return new Reading(c.x(), c.y(), c.z(), c.yaw(), c.pitch());
        }

        /** Position is reported as skipped by construction: the server owns it on every session type. */
        private static void serverOwned(JsonObject skipped, String field, double requested, double observed) {
            JsonObject s = Json.object();
            s.addProperty("requested", requested);
            // Named for what it is: a client-side snapshot taken inside the settle window. It is NOT
            // the authoritative value (the server owns position and never reports it synchronously),
            // so it must not be called `actual` — that read as "the position really is 12".
            s.addProperty("observedAtReadback", observed);
            s.addProperty("reason", "server-authoritative position");
            skipped.add(field, s);
        }

        /**
         * A field the client cannot witness: the request was delivered, but whether the authority
         * applied it is not observable from here. Distinct from {@code skipped}, which means "did not
         * take effect" — conflating the two reported a working remote teleport as if it had failed.
         */
        private static void notVerifiable(JsonObject target, String field, double requested, double observed) {
            JsonObject s = Json.object();
            s.addProperty("requested", requested);
            s.addProperty("observedAtReadback", observed);
            s.addProperty("reason", "the server owns the position; the client cannot witness whether it applied");
            target.add(field, s);
        }

        private static void compareApplied(JsonObject applied, JsonObject skipped, String field, double requested,
                                           double actual, double eps, boolean integrated) {
            if (Math.abs(requested - actual) <= eps) {
                applied.addProperty(field, actual);
            } else {
                JsonObject s = Json.object();
                s.addProperty("requested", requested);
                s.addProperty("actual", actual);
                s.addProperty("reason", integrated ? "did not take effect" : "server-authoritative position");
                skipped.add(field, s);
            }
        }

        private static void notSettled(JsonObject skipped, String field, double requested) {
            JsonObject s = Json.object();
            s.addProperty("requested", requested);
            s.addProperty("reason", "not settled");
            skipped.add(field, s);
        }

        /** One requested pose field, not applied because the guard allowed the op as a no-op. */
        private static void guardNoOpField(JsonObject skipped, String field, double requested, String reason) {
            JsonObject s = Json.object();
            s.addProperty("requested", requested);
            s.addProperty("reason", "guard no-op: " + reason);
            skipped.add(field, s);
        }
    }

    /**
     * {@code inv.select} / {@code inv.click} / {@code inv.toss}.
     *
     * <p>The two writing ops are the reference case for "requested ≠ applied": the client reads the
     * slot <b>before</b> and <b>after</b> the click and reports the comparison, never the request.
     * A local session yields a verdict ({@code applied} / {@code skipped}); a session whose authority
     * is a server can never have its menu edits confirmed by this client, so the verdict is
     * {@code notClientVerifiable} — the request was dispatched, and that is all we know.
     */
    public static final class InvOps implements Executor.OpHandler {
        private final String kind;

        public InvOps(String kind) {
            this.kind = kind;
        }

        @Override
        public JsonObject handle(Protocol.Ticket.Op op, Executor.ExecContext ctx) {
            JsonObject p = op.params() == null ? Json.object() : op.params();
            return switch (kind) {
                case "select" -> select(p, ctx);
                case "click" -> click(p, ctx);
                case "toss" -> toss(p, ctx);
                default -> throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                        "bad inv kind: " + kind);
            };
        }

        private JsonObject select(JsonObject p, Executor.ExecContext ctx) {
            int slot = requireInt(p, "slot", "inv.select");
            // inv.select writes the player's inventory, exactly like inv.click/inv.toss, so it goes
            // through the same single guard point: an allowance is audited once, a refusal leaves no
            // trace, and a no-op writes nothing.
            Protocol.OpSpec spec = spec(ctx, "inv.select");
            Guard.Decision decision = ctx.mutationGuard().requireAllowed(spec, "inv.select",
                    "select slot=" + slot);
            JsonObject out = Json.object();
            out.addProperty("slot", slot);
            if (decision.noop()) {
                return guardNoOp(out, "inv.select slot=" + slot, decision);
            }
            ctx.client().selectSlot(slot);
            JsonArray inv = Json.array();
            for (String s : ctx.client().inventory()) {
                inv.add(s);
            }
            out.add("inventory", inv);
            return out;
        }

        private JsonObject click(JsonObject p, Executor.ExecContext ctx) {
            ClientModel c = ctx.client();
            int slot = requireInt(p, "slot", "inv.click");
            int button = intOr(p, "button", 0);
            String mode = Json.str(p, "mode", "pickup");
            if (!CLICK_MODES.contains(mode)) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                        "unknown click mode '" + mode + "'; expected one of " + new java.util.TreeSet<>(CLICK_MODES))
                        .with("params", "mode");
            }
            // The guard runs first, before the client is even read: an op that was refused must not
            // have looked at, let alone touched, the inventory. Its decision is what the policy — not
            // our own local checks — is for.
            Protocol.OpSpec spec = spec(ctx, "inv.click");
            Guard.Decision clickDecision = ctx.mutationGuard().requireAllowed(spec, "inv.click",
                    "click slot=" + slot + " button=" + button + " mode=" + mode);
            if (clickDecision.noop()) {
                // Allowed, but the policy decided there is nothing to do (paused / handshake / not in
                // a world). NOTHING may reach the client on this path: a click performed here would be
                // a write the guard never turned into an allowance, so it would also never produce an
                // audit line — an unaccountable write. `UseOps` already handles its no-op; this op did
                // not, and the difference was a real, untested bypass.
                JsonObject noOp = Json.object();
                noOp.addProperty("slot", slot);
                noOp.addProperty("mode", mode);
                return guardNoOp(noOp, "inv.click slot=" + slot + " mode=" + mode, clickDecision);
            }

            // Armor slots (36..39) are refused unconditionally, whatever mode or item is involved:
            // identity must never be what decides whether a protected slot can be touched. Checked
            // before the slot is read, so the refusal is about the protection and not about bounds.
            if (slot >= 36 && slot <= 39) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                        "armor slots require an explicit equip path (mode=" + mode + ", slot=" + slot + ")");
            }
            Stack before = stackAt(c, slot, "inv.click");
            int windowId = windowId(c, slot);

            try {
                AdapterRefusedException.call(() -> c.clickSlot(slot, button, mode));
            } catch (AdapterRefusedException e) {
                throw e.toProtocolException();
            }

            Stack after = stackAt(c, slot, "inv.click");
            JsonObject out = Json.object();
            out.addProperty("windowId", windowId);
            out.addProperty("slot", slot);
            out.addProperty("button", button);
            out.addProperty("mode", mode);
            out.add("before", before.json());
            out.add("after", after.json());
            out.add("cursorBefore", cursor(c));
            out.add("cursorAfter", cursor(c));
            verdict(out, LocalObservation.of(before, after, "slot " + slot), c, ctx,
                    "click slot=" + slot + " mode=" + mode + " (window " + windowId + ")");
            return out;
        }

        private JsonObject toss(JsonObject p, Executor.ExecContext ctx) {
            ClientModel c = ctx.client();
            int slot = requireInt(p, "slot", "inv.toss");
            int count = intOr(p, "count", 1);
            if (count < 1 || count > 64) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                        "param 'count' must be within 1..64, got " + count).with("params", "count");
            }
            // The guard runs first, so a refusal means the inventory was never even read.
            Protocol.OpSpec spec = spec(ctx, "inv.toss");
            Guard.Decision tossDecision = ctx.mutationGuard().requireAllowed(spec, "inv.toss",
                    "toss slot=" + slot + " count=" + count);
            if (tossDecision.noop()) {
                // Same rule as inv.click: the policy said "nothing to do", so nothing is tossed and no
                // allowance is recorded — but the op did execute and stays ok, with a verdict that
                // cannot be read as "items were dropped".
                JsonObject noOp = Json.object();
                noOp.addProperty("slot", slot);
                noOp.addProperty("requestedCount", count);
                return guardNoOp(noOp, "inv.toss slot=" + slot + " count=" + count, tossDecision);
            }

            Stack before = stackAt(c, slot, "inv.toss");
            if (before.count() == 0) {
                refuseEmptySlot(c, slot, "toss");
            }

            try {
                AdapterRefusedException.call(() -> c.tossSlot(slot, count));
            } catch (AdapterRefusedException e) {
                throw e.toProtocolException();
            }

            Stack after = stackAt(c, slot, "inv.toss");
            int observedDelta = before.count() - after.count();
            JsonObject out = Json.object();
            out.addProperty("slot", slot);
            // Requested and observed are separate fields on purpose: a toss can be refused by the
            // server, or land as fewer items than asked for, and neither may be read off the request.
            out.addProperty("requestedCount", count);
            out.addProperty("observedDelta", observedDelta);
            out.add("before", before.json());
            out.add("after", after.json());
            out.addProperty("partial", observedDelta > 0 && observedDelta < count);
            verdict(out, LocalObservation.of(before, after, "slot " + slot), c, ctx,
                    "toss slot=" + slot + " requestedCount=" + count + " observedDelta=" + observedDelta);
            return out;
        }

        /** Reads a slot, refusing an out-of-range index as a parameter error and an empty one as state. */
        private static Stack stackAt(ClientModel c, int slot, String opName) {
            java.util.List<String> inv = c.inventory();
            if (slot < 0 || slot >= inv.size()) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                        opName + ": slot " + slot + " is out of range 0.." + (inv.size() - 1))
                        .with("params", "slot");
            }
            return Stack.parse(inv.get(slot));
        }

        /**
         * Refuses an op whose precondition is "there is something in this slot".
         *
         * <p>P9: "slot N is empty" is a <b>factual negative</b>, and container contents are synchronised
         * asynchronously — a join, a dimension change, or a click/toss dispatched a moment ago can make a
         * slot read empty while the item is still there (qa-tester measured exactly that on a real client:
         * the first {@code inv.toss} after joining reported "slot 0 is empty", and a read one second later
         * showed the item). When the adapter reports that its container view may still be catching up, that
         * read must not be turned into a fact: the refusal then says the state <b>cannot be determined</b>,
         * and the machine-readable {@code reason} distinguishes the two cases.
         */
        private static void refuseEmptySlot(ClientModel c, int slot, String action) {
            boolean unsynced = c.containerSyncPending();
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                    unsynced
                            ? "cannot determine whether slot " + slot + " is empty: the container has not "
                                    + "caught up with the authority yet (a join, a dimension change or a "
                                    + "recent click/toss may still be in flight), so '" + action + "' is not "
                                    + "decided from this read — read the inventory again"
                            : "slot " + slot + " is empty: nothing to " + action)
                    .with("reason", unsynced ? "container-not-synced" : "slot-empty");
        }

        /**
         * The carried ("cursor") stack.
         *
         * <p>Honest about its limits: {@link ClientModel} exposes the <b>container</b> contents, not
         * the stack held by the cursor, so the core reports an explicitly empty observation rather
         * than guessing. A cursor field that cannot be observed is reported as
         * {@code {id: "", count: 0, observed: false}} and never as a value.
         */
        private static JsonObject cursor(ClientModel c) {
            JsonObject o = Stack.empty().json();
            o.addProperty("observed", false);
            return o;
        }

        /**
         * The window a click targets, verified against the adapter's current container.
         *
         * <p>A negative window id means no container is open. Slot 0..8 (hotbar) and 36..39 (armor) of
         * the player inventory are addressed through window {@code 0} in vanilla, so no container is
         * required for those; any other slot does require one. Refusing here keeps a click from being
         * dispatched into "the window that used to be open".
         */
        private static int windowId(ClientModel c, int slot) {
            int window = c.windowId();
            if (window < 0) {
                boolean playerInventory = slot <= 8 || (slot >= 36 && slot <= 39);
                if (!playerInventory) {
                    // Same rule as the empty-slot refusal: "no container is open" is a factual negative
                    // about a synchronised view, so an adapter that reports its view may still be catching
                    // up gets "cannot determine" instead of an assertion.
                    boolean unsynced = c.containerSyncPending();
                    throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                            unsynced
                                    ? "cannot determine whether a container is open (windowId=" + window
                                            + ") for slot " + slot + ": the container may not have synced "
                                            + "yet, so this click is not decided from this read"
                                    : "no container is open (windowId=" + window + ") and slot " + slot
                                            + " is not a player-inventory slot")
                            .with("reason", unsynced ? "container-not-synced" : "no-container");
                }
                return 0;
            }
            return window;
        }

        /**
         * Writes the three-state verdict.
         *
         * <p><b>The rule that matters (P9):</b> {@code skipped} asserts that the write did <b>not</b> take
         * effect, so it may never be derived from "the read-back had not changed yet". A click that was
         * dispatched and then read too early looks exactly like a click that did nothing; reporting the
         * former as {@code skipped} makes an agent retry a click that already worked (a duplicate action —
         * the mirror image of P5, where an unverified read was reported as {@code applied}).
         *
         * <p>So the observation decides exactly one thing: a <b>changed</b> read-back is evidence of
         * application. An unchanged one is evidence of nothing and is reported as
         * {@code notClientVerifiable}, with a reason saying the read may simply be early. The local
         * session type no longer earns a stronger claim, because even an integrated server applies the
         * click asynchronously — qa-tester measured exactly that on a real client (P9:
         * {@code inv.click} reported {@code verdict:"skipped"} while the slot had actually changed).
         *
         * <p>A session with a server authority keeps its stronger rule: the menu is owned there, so the
         * verdict stays {@code notClientVerifiable} even if the local menu happens to have changed —
         * {@code applied} is reserved for a client-owned menu (§6.2b).
         */
        private static void verdict(JsonObject out, LocalObservation obs, ClientModel c,
                                    Executor.ExecContext ctx, String what) {
            Guard.SessionState session = ctx.session();
            boolean serverAuthority = session != null
                    && (session.connectedToRemoteServer() || !session.hasIntegratedServer());
            JsonObject applied = Json.object();
            JsonObject skipped = Json.object();
            JsonObject notVerifiable = Json.object();
            String verdict;
            String note;
            if (serverAuthority) {
                verdict = "notClientVerifiable";
                JsonObject entry = Json.object();
                entry.addProperty("requested", what);
                entry.addProperty("beforeCount", obs.before().count());
                entry.addProperty("afterCount", obs.after().count());
                entry.addProperty("observedAtReadback", obs.observedSummary());
                entry.addProperty("reason", "the server owns the container menu; the client dispatched "
                        + "the request but cannot witness whether it changed anything");
                notVerifiable.add(obs.subject(), entry);
                note = "the request was dispatched to the server, which owns the container menu; the "
                        + "client cannot confirm the result — this receipt does not claim the change "
                        + "happened";
            } else if (obs.changed()) {
                // Only an observed change on a client-owned menu earns `applied`: something really moved,
                // so the receipt reports a measurement instead of echoing the request back.
                verdict = "applied";
                applied.addProperty(obs.subject(), obs.observedSummary());
                note = "the client's menu changed as requested: this is a read-back difference, not the "
                        + "request repeated back";
            } else {
                // Unchanged is not the same as "did not happen" (P9). Report what is actually known:
                // this client cannot establish the outcome from this read.
                verdict = "notClientVerifiable";
                JsonObject entry = Json.object();
                entry.addProperty("requested", what);
                entry.addProperty("beforeCount", obs.before().count());
                entry.addProperty("afterCount", obs.after().count());
                entry.addProperty("observedAtReadback", obs.observedSummary());
                entry.addProperty("reason", "the client's menu had not changed when it was read back, "
                        + "which is NOT evidence that the write had no effect: container state is "
                        + "synchronised asynchronously (even an integrated server applies it on a later "
                        + "tick). Read again later instead of treating this as a no-op");
                notVerifiable.add(obs.subject(), entry);
                note = "the request was dispatched and the immediate read-back had not changed yet: this "
                        + "receipt does NOT claim the write failed and MUST NOT be read as skipped — "
                        + "read the menu again to establish the outcome";
            }
            out.addProperty("verdict", verdict);
            out.add("applied", applied);
            out.add("skipped", skipped);
            out.add("notClientVerifiable", notVerifiable);
            out.addProperty("note", note);
        }
    }

    /** What a slot comparison saw. {@code subject} names the field inside the three-state objects. */
    private record LocalObservation(Stack before, Stack after, String subject) {
        static LocalObservation of(Stack before, Stack after, String subject) {
            return new LocalObservation(before, after, subject);
        }

        boolean changed() {
            return !before.equals(after);
        }

        String observedSummary() {
            return after.id() + " x" + after.count();
        }
    }

    /** One slot's contents, as the client reports them. */
    private record Stack(String id, int count) {
        static final Stack EMPTY = new Stack("", 0);

        static Stack empty() {
            return EMPTY;
        }

        /** Parses {@code "minecraft:stone x12"} / {@code "minecraft:stone"} / {@code ""} (empty). */
        static Stack parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return EMPTY;
            }
            String s = raw.trim();
            int x = s.lastIndexOf(" x");
            if (x > 0) {
                try {
                    return new Stack(s.substring(0, x), Integer.parseInt(s.substring(x + 2)));
                } catch (NumberFormatException ignored) {
                    return new Stack(s, 1);
                }
            }
            return new Stack(s, 1);
        }

        JsonObject json() {
            JsonObject o = Json.object();
            o.addProperty("id", id);
            o.addProperty("count", count);
            return o;
        }
    }

    /**
     * {@code use.item} — dispatches the action and reports only client-observable state.
     *
     * <p>The receipt says <b>{@code dispatched: true}</b>, never "the item took effect". What an item
     * does (eat, place, fire, start a spell) is decided by the authority and is deliberately not
     * claimed here: {@code applied} only ever carries facts this client can see, and the outcome of
     * the use itself goes to {@code notClientVerifiable} on a server session.
     */
    public static final class UseOps implements Executor.OpHandler {
        @Override
        public JsonObject handle(Protocol.Ticket.Op op, Executor.ExecContext ctx) {
            JsonObject p = op.params() == null ? Json.object() : op.params();
            ClientModel c = ctx.client();
            // Order matters for the message a caller sees: "already using" is about the running state,
            // so it is reported before anything about the requested hand or the held item.
            if (c.usingItem()) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                        "already using an item");
            }
            String hand = Json.str(p, "hand", "main");
            if (!"main".equals(hand) && !"off".equals(hand)) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                        "param 'hand' must be 'main' or 'off', got '" + hand + "'").with("params", "hand");
            }
            String heldBefore = heldIn(c, hand);
            if (heldBefore == null || heldBefore.isBlank()) {
                // "no item in the <hand> hand" is a factual negative about synchronised state: when the
                // adapter says its inventory may still be catching up, it cannot be determined here (P9).
                boolean unsynced = c.containerSyncPending();
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                        unsynced
                                ? "cannot determine whether the " + hand + " hand holds an item: the "
                                        + "inventory has not caught up with the authority yet, so this read "
                                        + "is not evidence that the hand is empty"
                                : "no item in the " + hand + " hand")
                        .with("reason", unsynced ? "container-not-synced" : "empty-hand");
            }
            // A positive cooldown is a state the op assumes is over. Only what the client actually
            // reports refuses, so an adapter that does not expose cooldowns (the interface default) is
            // never blocked by a check it cannot answer. `itemOnCooldown` covers adapters that know the
            // state but not the remaining ticks.
            int cooldownTicks = c.cooldownTicks();
            if (c.itemOnCooldown() || cooldownTicks > 0) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                        c.itemOnCooldown()
                                ? "the held item is on cooldown"
                                : "the held item is on cooldown: cooldownTicks=" + cooldownTicks);
            }
            int windowBefore = c.windowId();
            String usingBefore = String.valueOf(c.usingItem());
            Stack heldStackBefore = Stack.parse(heldBefore);

            Protocol.OpSpec spec = spec(ctx, "use.item");
            Guard.Decision decision = ctx.mutationGuard().requireAllowed(spec, "use.item",
                    "use hand=" + hand + " held=" + heldBefore);

            boolean dispatched = !decision.noop();
            if (dispatched) {
                try {
                    // The requested hand is part of the request: dispatching from the main hand while
                    // the ticket asked for the off hand would make the receipt describe a different op.
                    AdapterRefusedException.call(() -> c.useItem("off".equals(hand)));
                } catch (AdapterRefusedException e) {
                    throw e.toProtocolException();
                }
            }

            boolean usingAfter = c.usingItem();
            JsonObject out = Json.object();
            out.addProperty("dispatched", dispatched);
            out.addProperty("hand", hand);
            out.add("heldBefore", heldStackBefore.json());
            out.add("heldAfter", Stack.parse(heldIn(c, hand)).json());
            out.addProperty("usingBefore", Boolean.parseBoolean(usingBefore));
            out.addProperty("usingAfter", usingAfter);
            out.addProperty("cooldownTicks", Math.max(c.cooldownTicks(), 0));
            JsonObject applied = Json.object();
            JsonObject skipped = Json.object();
            JsonObject notVerifiable = Json.object();
            if (!dispatched) {
                out.addProperty("verdict", "notDispatched");
                JsonObject entry = Json.object();
                entry.addProperty("requested", "use hand=" + hand);
                entry.addProperty("reason", decision.reason());
                skipped.add("dispatch", entry);
            } else {
                applied.addProperty("dispatch", "useItem(" + hand + ") handed to the client");
                out.addProperty("verdict", "dispatched");
                JsonObject entry = Json.object();
                entry.addProperty("requested", "use hand=" + hand + " held=" + heldBefore);
                entry.addProperty("observedAtReadback", "usingItem=" + usingAfter
                        + " heldAfter=" + heldIn(c, hand));
                entry.addProperty("reason", "the effect of using an item (consume, place, fire, …) is "
                        + "decided by the authority; the client cannot witness it");
                notVerifiable.add("effect", entry);
            }
            out.add("applied", applied);
            out.add("skipped", skipped);
            out.add("notClientVerifiable", notVerifiable);
            out.addProperty("note", "this receipt claims only that the action was DISPATCHED and reports "
                    + "the client state around it; it does not claim that any effect occurred"
                    + (windowBefore >= 0 ? " (container window " + windowBefore + " was open)" : ""));
            return out;
        }

        /**
         * The item in the hand the ticket asked for.
         *
         * <p>{@code hand:"off"} is only verifiable if {@code heldBefore}/{@code heldAfter} actually come
         * from the off hand: reporting the main hand's item for an off-hand request would describe a
         * different op, and (before this) the receipt could not be checked against
         * {@code state.query{what:["offhand"]}}.
         */
        private static String heldIn(ClientModel c, String hand) {
            return "off".equals(hand) ? c.offHandItemId() : c.heldItemId();
        }
    }

    /** {@code world.place} — refuses to place into an occupied cell. */
    public static final class WorldOps implements Executor.OpHandler {
        @Override
        public JsonObject handle(Protocol.Ticket.Op op, Executor.ExecContext ctx) {
            JsonObject p = op.params() == null ? Json.object() : op.params();
            int x = p.get("x").getAsInt();
            int y = p.get("y").getAsInt();
            int z = p.get("z").getAsInt();
            String block = Json.str(p, "block", "minecraft:stone");
            ClientModel c = ctx.client();
            // world.place is a write (WORLD_BLOCKS): same single guard point as the other write ops.
            Protocol.OpSpec spec = spec(ctx, "world.place");
            Guard.Decision decision = ctx.mutationGuard().requireAllowed(spec, "world.place",
                    "place " + block + " at " + x + "," + y + "," + z);
            JsonObject out = Json.object();
            out.addProperty("block", block);
            if (decision.noop()) {
                out.addProperty("placed", false);
                return guardNoOp(out, "place " + block + " at " + x + "," + y + "," + z, decision);
            }
            if (c.cellOccupied(x, y, z)) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_EXEC,
                        "cell (" + x + "," + y + "," + z + ") is occupied");
            }
            // P11: a real placement uses the item the player is holding, so an empty hand is an honest
            // precondition failure — the op must not conjure the block into the world (and, per the P9
            // rule, an empty read inside the container sync window is not evidence of an empty hand).
            String held = c.heldItemId();
            if (held == null || held.isBlank()) {
                boolean unsynced = c.containerSyncPending();
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                        unsynced
                                ? "cannot determine whether the selected slot holds " + block + ": the "
                                        + "inventory has not caught up with the authority yet"
                                : "no item in the selected slot: a real placement needs " + block
                                        + " in hand, and world.place now goes through the "
                                        + "player-interaction path, so it cannot place a block the player "
                                        + "is not holding")
                        .with("reason", unsynced ? "container-not-synced" : "empty-hand");
            }
            c.useItemOnBlock(x, y, z, block);
            // P10: `placed` used to be an unconditional `true` — a self-report derived from the request,
            // which is exactly the class of claim this protocol forbids. It is now the client's own
            // read-back of its world, and the verdict says who owns the outcome.
            String observed = c.blockIdAt(x, y, z);
            boolean witnessed = observed != null;
            boolean occupiedAfter = c.cellOccupied(x, y, z);
            boolean placed = witnessed ? !observed.isEmpty() : occupiedAfter;
            out.addProperty("placed", placed);
            out.addProperty("blockObserved", observed);
            out.addProperty("verdict", "notClientVerifiable");
            JsonObject effect = Json.object();
            effect.addProperty("requested", "place " + block + " at " + x + "," + y + "," + z);
            effect.addProperty("observedAtReadback", "cell occupied=" + occupiedAfter
                    + (witnessed ? " block=" + (observed.isEmpty() ? "(air)" : observed)
                    : " blockId=unavailable"));
            effect.addProperty("reason", "the client applied the placement to its own view of the world "
                    + "and whether the authority keeps it is not observable from here; a read-back that "
                    + "has not changed yet is not evidence that the placement failed"
                    + (witnessed ? "" : ", and this adapter cannot report the block id at all"));
            JsonObject notVerifiable = Json.object();
            notVerifiable.add("effect", effect);
            out.add("notClientVerifiable", notVerifiable);
            out.add("applied", Json.object());
            out.add("skipped", Json.object());
            out.addProperty("note", "placed/blockObserved are this client's own view of the block, not the "
                    + "authority's: the receipt claims neither applied nor skipped"
                    + (witnessed ? "" : " (this adapter exposes no block query, so blockObserved is null)"));
            return out;
        }
    }

    /**
     * {@code shot.capture} / {@code bench.read} / {@code wait.frames}.
     *
     * <p>Both capture ops are tier-1 reads ({@code TELEMETRY_RECORDING}): they are never gated by the
     * injection policy, because reading a frame is not a way to affect a server. What they must not do
     * is overstate what they measured — {@code shot.capture} reports bytes and nothing about the image,
     * {@code bench.read} reports the window, the units and the sample count next to the numbers.
     */
    public static final class MiscOps implements Executor.OpHandler {
        /**
         * Per-JVM sequence so two default capture names cannot collide, not even when one ticket issues
         * two captures inside the same millisecond (the clock is the only other ingredient).
         */
        private static final java.util.concurrent.atomic.AtomicLong SHOT_SEQ =
                new java.util.concurrent.atomic.AtomicLong();

        private final String kind;

        public MiscOps(String kind) {
            this.kind = kind;
        }

        @Override
        public JsonObject handle(Protocol.Ticket.Op op, Executor.ExecContext ctx) {
            JsonObject p = op.params() == null ? Json.object() : op.params();
            ClientModel c = ctx.client();
            JsonObject out = Json.object();
            switch (kind) {
                case "shot" -> capture(p, c, out, ctx, op);
                case "bench" -> bench(p, c, out);
                case "wait" -> {
                    int frames = p.has("frames") ? p.get("frames").getAsInt() : 1;
                    c.waitFrames(frames);
                    out.addProperty("waited_frames", frames);
                }
                default -> throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "bad misc kind");
            }
            return out;
        }

        /**
         * Byte-level facts only.
         *
         * <p>The note is a fixed sentence: whatever a reader does with these numbers, the receipt has
         * said in words that the <b>content</b> of the image was not looked at. That is the line
         * between "a frame was captured" (measured here) and "the scene looks correct" (not measured).
         */
        private void capture(JsonObject p, ClientModel c, JsonObject out, Executor.ExecContext ctx,
                             Protocol.Ticket.Op op) {
            String name = Json.str(p, "name", defaultShotName(ctx, op));
            ClientModel.CapturedFrame frame;
            try {
                frame = c.capture(name);
            } catch (Protocol.ProtocolException e) {
                if (e.code() != Protocol.ErrorCode.E_TIMEOUT) {
                    throw e;
                }
                // A frame that did not arrive in the budget is a timeout, and a timeout never reports
                // a (possibly blank) image as success.
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_TIMEOUT,
                        "waited for a rendered frame but none was delivered within the budget: "
                                + e.getMessage());
            } catch (UnsupportedOperationException e) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                        "frame capture is not implemented by this client adapter");
            }
            if (frame == null || frame.bytes() == null || frame.bytes().length == 0) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                        "no frame is available to capture (minimised, paused, or not in a world)");
            }
            out.addProperty("bytes", frame.bytes().length);
            out.addProperty("format", frame.format() == null ? "png" : frame.format());
            out.addProperty("width", frame.width());
            out.addProperty("height", frame.height());
            out.addProperty("sha256", frame.sha256());
            out.addProperty("tick", frame.tick());
            out.addProperty("path", frame.path());
            out.addProperty("note", "bytes were captured; image content is NOT interpreted");
        }

        /**
         * The default capture name: op id, clock and a per-JVM sequence, so two default shots can never
         * collide — including two captures issued by one ticket inside the same millisecond. (The ticket
         * id is not part of the execution context, so the op id is the closest available identity.)
         */
        private static String defaultShotName(Executor.ExecContext ctx, Protocol.Ticket.Op op) {
            return "shot-" + op.id() + "-" + ctx.clock().nowMs() + "-" + SHOT_SEQ.incrementAndGet();
        }

        /**
         * One sampling window, with everything needed to read the numbers.
         *
         * <p>The three values are meaningless without the window and the units, so
         * {@code warmupFrames}, {@code sampleFrames}, {@code sampleCount}, {@code windowMs} and
         * {@code units} are emitted unconditionally — and when the adapter delivered fewer samples
         * than requested, the receipt says so in the note instead of quietly reporting the numbers as
         * if the full window had been measured.
         */
        private void bench(JsonObject p, ClientModel c, JsonObject out) {
            int warmup = Json.intOr(p, "warmup_frames", 60);
            int sample = Json.intOr(p, "sample_frames", 300);
            if (warmup < 0) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                        "param 'warmup_frames' must not be negative").with("params", "warmup_frames");
            }
            if (sample < 1 || sample > MAX_SAMPLE_FRAMES) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                        "param 'sample_frames' must be within 1.." + MAX_SAMPLE_FRAMES)
                        .with("params", "sample_frames");
            }
            if (warmup + sample > MAX_TOTAL_FRAMES) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                        "warmup_frames + sample_frames must not exceed " + MAX_TOTAL_FRAMES)
                        .with("params", "sample_frames");
            }
            ClientModel.BenchResult r;
            try {
                r = c.benchWindow(warmup, sample);
            } catch (Protocol.ProtocolException e) {
                if (e.code() != Protocol.ErrorCode.E_TIMEOUT) {
                    throw e;
                }
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_TIMEOUT,
                        "frame-time sampling did not complete within the budget: " + e.getMessage());
            } catch (UnsupportedOperationException e) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                        "frame-time sampling is not implemented by this client adapter");
            }
            if (r == null || r.sampleCount() <= 0) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                        "no frames were sampled (minimised, paused, or not in a world)");
            }
            out.addProperty("warmupFrames", r.warmupFrames());
            out.addProperty("sampleFrames", r.sampleFrames());
            out.addProperty("sampleCount", r.sampleCount());
            out.addProperty("windowMs", r.windowMs());
            out.addProperty("fpsMedian", r.fpsMedian());
            out.addProperty("frameMsP95", r.frameMsP95());
            out.addProperty("onePercentLow", r.onePercentLow());
            JsonObject units = Json.object();
            units.addProperty("fps", "frames per second");
            units.addProperty("ms", "milliseconds per frame");
            out.add("units", units);
            out.addProperty("samplesPath", r.samplesPath());
            boolean shortWindow = r.sampleCount() < r.sampleFrames();
            boolean overranWindow = r.sampleCount() > r.sampleFrames();
            out.addProperty("actualSampleCount", r.sampleCount());
            // An adapter that measured MORE frames than the requested window is an anomaly, but not a
            // failed op: the measurement is real and the requested/measured pair already exposes the
            // discrepancy. It is named explicitly rather than silently accepted, or folded into the
            // generic E_EXEC ("the op ran and failed"), because the numbers stay usable and hiding the
            // overrun is exactly what a receipt must not do.
            out.addProperty("overranWindow", overranWindow);
            out.addProperty("note", "client-side frame durations only (median fps, p95 frame time, "
                    + "1% low); no GPU/vendor counters are read, and the numbers are not directly "
                    + "comparable across machines or scenes"
                    + (shortWindow
                            ? "; the adapter delivered " + r.sampleCount() + " of the requested "
                                    + r.sampleFrames() + " samples, so the window is shorter than asked for"
                            : overranWindow
                                    ? "; the adapter reported " + r.sampleCount() + " samples for a "
                                            + "requested window of " + r.sampleFrames()
                                            + ", so the measurement overran the requested window and is "
                                            + "reported as measured, not clamped"
                                    : ""));
        }
    }
}
