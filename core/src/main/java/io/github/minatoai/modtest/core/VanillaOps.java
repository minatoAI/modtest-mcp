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

    /** Registers the vanilla op set. */
    public static Executor.OpCatalog install(Executor.OpCatalog catalog) {
        catalog.register(spec("state.query", "Read player/world state", schema("what"), null,
                        List.of(), List.of(Protocol.SideEffect.NONE)),
                new StateOps());
        catalog.register(spec("pose.set", "Teleport/rotate with settle", schema("x", "y", "z", "yaw", "pitch"), null,
                        List.of(Protocol.Precondition.of("permitted-session"), Protocol.Precondition.of("flag", "name", "allow-mutate")),
                        List.of(Protocol.SideEffect.PLAYER_STATE)), new PoseOps());
        catalog.register(spec("inv.select", "Select a hotbar slot", schema("slot"), null,
                        List.of(Protocol.Precondition.of("permitted-session"), Protocol.Precondition.of("flag", "name", "allow-mutate")),
                        List.of(Protocol.SideEffect.PLAYER_INVENTORY)), new InvOps("select"));
        catalog.register(spec("inv.click", "Click an inventory slot", schema("slot", "button", "mode"), null,
                        List.of(Protocol.Precondition.of("permitted-session"), Protocol.Precondition.of("flag", "name", "allow-mutate")),
                        List.of(Protocol.SideEffect.PLAYER_INVENTORY)), new InvOps("click"));
        catalog.register(spec("inv.toss", "Drop items from a slot", schema("slot", "count"), null,
                        List.of(Protocol.Precondition.of("permitted-session"), Protocol.Precondition.of("flag", "name", "allow-mutate")),
                        List.of(Protocol.SideEffect.PLAYER_INVENTORY)), new InvOps("toss"));
        catalog.register(spec("use.item", "Use the held item", Json.object(), null,
                        List.of(Protocol.Precondition.of("permitted-session"), Protocol.Precondition.of("flag", "name", "allow-mutate")),
                        List.of(Protocol.SideEffect.PLAYER_STATE)), new UseOps());
        catalog.register(spec("world.place", "Place a block", schema("x", "y", "z", "block"), null,
                        List.of(Protocol.Precondition.of("permitted-session"), Protocol.Precondition.of("flag", "name", "allow-mutate")),
                        List.of(Protocol.SideEffect.WORLD_BLOCKS)), new WorldOps());
        catalog.register(spec("shot.capture", "Capture a screenshot", schema("name"), null,
                        List.of(),
                        List.of(Protocol.SideEffect.TELEMETRY_RECORDING)), new MiscOps("shot"));
        catalog.register(spec("bench.read", "Read a frame-time sample", Json.object(), null,
                        List.of(), List.of(Protocol.SideEffect.NONE)),
                new MiscOps("bench"));
        catalog.register(spec("wait.frames", "Wait client frames", schema("frames"), null,
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
            if (want.contains("all") || want.contains("dimension")) {
                out.addProperty("dimension", c.dimension());
            }
            if (want.contains("all") || want.contains("inventory")) {
                JsonArray inv = Json.array();
                for (String s : c.inventory()) {
                    inv.add(s);
                }
                out.add("inventory", inv);
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
            c.teleport(rx, ry, rz, ryaw, rpitch, Json.intOr(p, "settle_ms", 250));

            boolean integrated = ctx.session().hasIntegratedServer() && !ctx.session().connectedToRemoteServer();
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
            if (settled) {
                // x/y/z are owned by the authority on BOTH session types (a single-player world's
                // integrated server included) => always skipped, never "applied". A window-internal
                // read-back cannot prove anything about them.
                serverOwned(skipped, "x", rx, second.x());
                serverOwned(skipped, "y", ry, second.y());
                serverOwned(skipped, "z", rz, second.z());
                // Rotation is client-authoritative, so it may legitimately be reported as applied.
                compareApplied(applied, skipped, "yaw", ryaw, second.yaw(), ANGLE_EPS, integrated);
                compareApplied(applied, skipped, "pitch", rpitch, second.pitch(), ANGLE_EPS, integrated);
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
                    ? "single-player: the integrated server is authoritative too"
                    : "multiplayer: the server owns the player position")
                    + "; client position readings cannot be authoritative; position is owned by the server"
                    + (settled
                            ? (applied.size() == FIELDS.size()
                                    ? "; every requested field took effect"
                                    : "; not applied: " + skipped.keySet())
                            : "; the pose did not settle within " + budget
                                    + " client ticks, so nothing is reported as applied");

            JsonObject out = Json.object();
            out.add("pose", pose);
            out.addProperty("settled", settled);
            out.add("applied", applied);
            out.add("skipped", skipped);
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
            s.addProperty("actual", observed);
            s.addProperty("reason", "server-authoritative position");
            skipped.add(field, s);
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
    }

    /** {@code inv.*} — with the {@code beforeOp} hook notes recorded in the result. */
    public static final class InvOps implements Executor.OpHandler {
        private final String kind;

        public InvOps(String kind) {
            this.kind = kind;
        }

        @Override
        public JsonObject handle(Protocol.Ticket.Op op, Executor.ExecContext ctx) {
            JsonObject p = op.params() == null ? Json.object() : op.params();
            ClientModel c = ctx.client();
            switch (kind) {
                case "select" -> c.selectSlot(p.get("slot").getAsInt());
                case "click" -> {
                    int slot = p.get("slot").getAsInt();
                    String mode = Json.str(p, "mode", "pick");
                    // "同款互换不穿甲" analogue: an armor slot is refused regardless of whether the
                    // incoming item is identical, so identity can never be used to bypass the guard.
                    if (slot >= 36 && slot <= 39) {
                        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                                "armor slots require an explicit equip path (mode=" + mode + ")");
                    }
                    c.clickSlot(slot, Json.intOr(p, "button", 0), mode);
                }
                case "toss" -> c.tossSlot(p.get("slot").getAsInt(), Json.intOr(p, "count", 1));
                default -> throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "bad inv kind");
            }
            JsonObject out = Json.object();
            out.addProperty("slot", p.has("slot") ? p.get("slot").getAsInt() : -1);
            JsonArray inv = Json.array();
            for (String s : c.inventory()) {
                inv.add(s);
            }
            out.add("inventory", inv);
            return out;
        }
    }

    /** {@code use.item} — refuses to start a second use while one is already running. */
    public static final class UseOps implements Executor.OpHandler {
        @Override
        public JsonObject handle(Protocol.Ticket.Op op, Executor.ExecContext ctx) {
            ClientModel c = ctx.client();
            if (c.usingItem()) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                        "already using an item");
            }
            c.useItem();
            JsonObject out = Json.object();
            out.addProperty("used", true);
            out.addProperty("held", c.heldItemId());
            out.addProperty("using", c.usingItem());
            return out;
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
            ClientModel c = ctx.client();
            if (c.cellOccupied(x, y, z)) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_EXEC,
                        "cell (" + x + "," + y + "," + z + ") is occupied");
            }
            c.placeBlock(x, y, z, Json.str(p, "block", "minecraft:stone"));
            JsonObject out = Json.object();
            out.addProperty("placed", true);
            out.addProperty("block", Json.str(p, "block", "minecraft:stone"));
            return out;
        }
    }

    /** {@code shot.capture} / {@code bench.read} / {@code wait.frames}. */
    public static final class MiscOps implements Executor.OpHandler {
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
                case "shot" -> out.addProperty("file", c.captureScreenshot(Json.str(p, "name", "shot")));
                case "bench" -> {
                    ClientModel.BenchSample s = c.bench(Json.intOr(p, "warmup_frames", 60),
                            Json.intOr(p, "sample_frames", 600));
                    out.addProperty("fps_median", s.fpsMedian());
                    out.addProperty("frame_ms_p95", s.frameMsP95());
                    out.addProperty("one_percent_low", s.onePercentLow());
                }
                case "wait" -> {
                    int frames = p.has("frames") ? p.get("frames").getAsInt() : 1;
                    c.waitFrames(frames);
                    out.addProperty("waited_frames", frames);
                }
                default -> throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "bad misc kind");
            }
            return out;
        }
    }
}
