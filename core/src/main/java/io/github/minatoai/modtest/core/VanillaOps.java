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

    /** {@code pose.set} — teleport then read back, reporting what actually took effect. */
    public static final class PoseOps implements Executor.OpHandler {
        private static final double COORD_EPS = 1.0e-3;
        private static final double ANGLE_EPS = 1.0e-3;

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

            double ax = c.x();
            double ay = c.y();
            double az = c.z();
            float ayaw = c.yaw();
            float apitch = c.pitch();
            JsonObject pose = Json.object();
            pose.addProperty("x", ax);
            pose.addProperty("y", ay);
            pose.addProperty("z", az);
            pose.addProperty("yaw", ayaw);
            pose.addProperty("pitch", apitch);

            // "requested" is not "applied". On a server-authoritative session the position the client
            // sets is rubber-banded back while client-side rotation survives, so the receipt must say
            // which fields actually took effect instead of letting ok:true imply "all of them did".
            JsonObject applied = Json.object();
            JsonObject skipped = Json.object();
            compare(applied, skipped, "x", rx, ax, COORD_EPS);
            compare(applied, skipped, "y", ry, ay, COORD_EPS);
            compare(applied, skipped, "z", rz, az, COORD_EPS);
            compare(applied, skipped, "yaw", ryaw, ayaw, ANGLE_EPS);
            compare(applied, skipped, "pitch", rpitch, apitch, ANGLE_EPS);

            boolean integrated = ctx.session().hasIntegratedServer() && !ctx.session().connectedToRemoteServer();
            JsonObject out = Json.object();
            out.add("pose", pose);
            out.addProperty("settled", c.settled());
            out.add("applied", applied);
            out.add("skipped", skipped);
            out.addProperty("authority", integrated ? "client" : "server");
            out.addProperty("note", integrated
                    ? "single-player: the client is authoritative, so a settled pose is the real pose"
                    : "multiplayer: the server owns the player position, so position fields may be "
                            + "requested but not take effect (rotation is client-side and usually does)");
            return out;
        }

        private static void compare(JsonObject applied, JsonObject skipped, String field,
                                    double requested, double actual, double eps) {
            if (Math.abs(requested - actual) <= eps) {
                applied.addProperty(field, actual);
            } else {
                JsonObject s = Json.object();
                s.addProperty("requested", requested);
                s.addProperty("actual", actual);
                skipped.add(field, s);
            }
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
