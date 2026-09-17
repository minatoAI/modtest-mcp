package io.github.minatoai.modtest.forge;

import com.google.gson.JsonObject;
import io.github.minatoai.modtest.core.Bridge;
import io.github.minatoai.modtest.core.ClientModel;
import io.github.minatoai.modtest.core.Executor;
import io.github.minatoai.modtest.core.Guard;
import io.github.minatoai.modtest.core.InputHold;
import io.github.minatoai.modtest.core.Protocol;
import io.github.minatoai.modtest.core.Relay;
import io.github.minatoai.modtest.core.VanillaOps;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The dev-only Forge entry point: wires the pure-JVM protocol core to a real client.
 *
 * <p>Safety posture of this adapter:
 * <ul>
 *   <li>it does nothing unless {@code MODTEST_AGENT_DIR} and the dev flag are set (off by default);</li>
 *   <li>input injection additionally needs an activation token supplied by the operator through the
 *       environment, with an expiry — a ticket can never activate anything;</li>
 *   <li>every injection goes through {@link Guard.GuardedInputWriter}, whose policy refuses remote
 *       sessions before any write.</li>
 * </ul>
 */
@Mod(ModtestHarnessMod.MOD_ID)
public final class ModtestHarnessMod {
    public static final String MOD_ID = "modtestharness";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /**
     * The single owner of "how long does this injection keep writing". A hold is installed once per
     * ticket and is replaced (never extended) by the next ticket; every path that ends a hold clears
     * it explicitly. See {@link InputHold} for why this is core state, not adapter state.
     */
    private static final InputHold HOLD = new InputHold();
    /** Upper bound on a hold duration, so one ticket cannot drive input indefinitely. */
    public static final int MAX_TICKS = 200;

    private static Bridge.BridgeConfig config;
    private static Relay.BridgeRelay relay;
    private static Guard.GuardedInputWriter guardedWriter;
    private static Guard.ActivationState activation = Guard.ActivationState.off();
    private static Protocol.OpSpec inputOp;
    private static long lastPoll;
    private static int writeCount;

    public ModtestHarnessMod() {
        if (!net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            LOG.info("[modtest-mcp] dedicated server detected: harness stays inert");
            return;
        }
        Map<String, String> env = System.getenv();
        if (env.get("MODTEST_AGENT_DIR") == null) {
            LOG.info("[modtest-mcp] harness idle: MODTEST_AGENT_DIR is not set (off by default)");
            return;
        }
        // The declared-host whitelist must be wired here: assembling the 7-arg BridgeConfig left it
        // at its empty default, which silently disabled the whole "hosts I own" feature in production
        // (a self-hosted dev server could never be used), while the config looked perfectly valid.
        config = new Bridge.BridgeConfig(Path.of(env.get("MODTEST_AGENT_DIR")), 500L, "forge-client",
                "0.1.0", Boolean.parseBoolean(env.getOrDefault("MODTEST_ALLOW_MUTATE", "false")),
                Bridge.BusyPolicy.ANSWER_BUSY, 64,
                Guard.HostWhitelist.parse(env.get("MODTEST_ALLOWED_HOSTS")));

        boolean devFlag = Boolean.parseBoolean(env.getOrDefault("MODTEST_DEV_HARNESS", "false"));
        String tokenValue = env.get("MODTEST_ACTIVATION_TOKEN");
        long until = Long.parseLong(env.getOrDefault("MODTEST_ACTIVATION_UNTIL_MS", "0"));
        activation = new Guard.ActivationState(devFlag,
                tokenValue == null ? null : new Guard.ActivationToken(tokenValue, until));

        Executor.OpCatalog catalog = VanillaOps.install(
                new Executor.OpCatalog("forge-client", "0.1.0", "modtest-harness-forge"));
        inputOp = new Protocol.OpSpec("input.set", "Queue a player-input command", inputParamsSchema(),
                io.github.minatoai.modtest.core.Json.object(),
                List.of(Protocol.Precondition.of("permitted-session")),
                List.of(Protocol.SideEffect.PLAYER_INPUT), "forge-client", null, "1.0");
        catalog.register(inputOp, ModtestHarnessMod::queueInput);

        // Production goes through the same "audited" factory the tests use: an allowance is written
        // to the game log as ALLOWED-INPUT (SLF4J), with the token fingerprint only — never its value.
        Guard.InputInjectionPolicy policy = new Guard.InputInjectionPolicy(
                config.allowedHosts(), Guard.BuildVariant.current(), config.executorId(),
                () -> {
                    Minecraft m = Minecraft.getInstance();
                    return m.level == null ? "no-world" : m.level.dimension().location().toString();
                });
        guardedWriter = Guard.GuardedInputWriter.audited(
                // Count only. Arming the hold from this callback re-armed it on EVERY write, which is
                // what made a hold self-perpetuating and produced 106 audit lines for a ticks:20
                // request (P7). The hold is owned solely by InputHold.
                command -> writeCount++,
                policy, new Guard.HumanSpeedClamp(),
                line -> LOG.info("[modtest-mcp] {}", line));

        Executor.TicketExecutor executor = new Executor.TicketExecutor(config, catalog);
        Minecraft mc = Minecraft.getInstance();
        // The write-op guard: every op that can change the player (input.set, inv.select, inv.click,
        // inv.toss, use.item, pose.set, world.place) passes through this one object, so an allowance is
        // logged exactly once (token FINGERPRINT only, never its value), a refusal leaves no line, and a
        // no-op writes nothing. Leaving it out is not "no guard": ExecContext substitutes a fail-closed
        // one, which is why the same policy instance used by the input writer is reused here.
        Guard.MutationGuard mutationGuard = new Guard.MutationGuard(
                policy, new MinecraftSessionState(mc), activation, Bridge.Clock.system(),
                line -> LOG.info("[modtest-mcp] {}", line));
        relay = new Relay.BridgeRelay(config, new Bridge.NioBridgeFs(config.dir()),
                new Relay.TicketValidator(catalog), executor,
                new Relay.ReceiptStore(new Bridge.NioBridgeFs(config.dir()), Bridge.Clock.system()),
                Bridge.Clock.system(), line -> LOG.info("[modtest-mcp] {}", line),
                () -> new Executor.ExecContext(config, new MinecraftSessionState(mc), activation,
                        Bridge.Clock.system(), Map.of("allow-mutate", config.allowMutate()),
                        new MinecraftClientModel(mc, config.dir().resolve("recordings")), mutationGuard));
        MinecraftForge.EVENT_BUS.register(this);
        LOG.warn("[modtest-mcp] {} — this is a DEVELOPMENT harness, not a gameplay mod",
                io.github.minatoai.modtest.core.BuildInfo.describe());
        if (!io.github.minatoai.modtest.core.BuildInfo.guarded()) {
            LOG.warn("[modtest-mcp] UNGUARDED BUILD: the injection policy is disabled. "
                    + "Use it only on servers you own; do not redistribute this artifact.");
        }
        LOG.info("[modtest-mcp] harness armed: dir={} devFlag={} tokenActive={} allowedHosts={}",
                config.dir(), devFlag, activation.active(Bridge.Clock.system()), config.allowedHosts().hosts());
    }

    /** The authoritative params schema for {@code input.set} (mirrored in docs/PROTOCOL.md §6.2). */
    private static JsonObject inputParamsSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        JsonObject props = new JsonObject();
        for (String f : List.of("forward", "strafe", "yawDelta", "pitchDelta")) {
            JsonObject p = new JsonObject();
            p.addProperty("type", "number");
            props.add(f, p);
        }
        for (String f : List.of("jump", "sneak", "sprint")) {
            JsonObject p = new JsonObject();
            p.addProperty("type", "boolean");
            props.add(f, p);
        }
        JsonObject ticks = new JsonObject();
        ticks.addProperty("type", "integer");
        props.add("ticks", ticks);
        schema.add("properties", props);
        return schema;
    }

    /** Op handler: never writes directly — it queues, and the mixin submits through the guard. */
    private static JsonObject queueInput(Protocol.Ticket.Op op, Executor.ExecContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        // The ticket's params decide *what* to write; an empty params object is the old no-op
        // command, byte for byte. `ticks` is a hold duration, not part of the command value.
        Guard.InputCommand command = Guard.InputCommand.fromParams(op.params());
        int ticks = readTicks(op.params());
        Guard.Decision decision = guardedWriter.submit(command, inputOp,
                new MinecraftSessionState(mc), activation, Bridge.Clock.system());
        // A refusal is NOT a successful op: `ok:true` must mean "this op really executed". The
        // executor turns this exception into ops[].ok=false + error.code (see Guard.requireAllowed).
        Guard.requireAllowed(decision, "input.set");
        if (decision.allowed() && !decision.noop()) {
            // Install this ticket's hold — REPLACING any previous one, so a new ticket can never
            // extend a running hold. Exactly `ticks` writes follow, then it clears itself.
            HOLD.install(op.id(), command, ticks);
        }
        JsonObject out = new JsonObject();
        out.addProperty("queued", true);
        out.addProperty("allowed", decision.allowed());
        out.addProperty("noop", decision.noop());
        out.addProperty("reason", decision.reason());
        out.addProperty("ticks", ticks);
        // Named for what it is: the command that was QUEUED (the ticket's own values). What the client
        // actually receives is the clamped value, reported as `writtenCommand` and in the audit line
        // (`ALLOWED-INPUT … cmd=[…]`), which is the authoritative record.
        out.addProperty("queuedCommand", command.summary());
        if (decision.allowed() && !decision.noop() && guardedWriter.lastCommand() != null) {
            out.addProperty("writtenCommand", guardedWriter.lastCommand().summary());
        }
        return out;
    }

    /** Hold duration for a queued command; 1 means "this tick only". Bounded by design. */
    private static int readTicks(com.google.gson.JsonObject params) {
        com.google.gson.JsonElement e = params == null ? null : params.get("ticks");
        if (e == null || e.isJsonNull()) {
            return 1;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                    "param 'ticks' must be a number");
        }
        int ticks = e.getAsInt();
        if (ticks < 1 || ticks > MAX_TICKS) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                    "param 'ticks' must be within 1.." + MAX_TICKS);
        }
        return ticks;
    }

    /** Called from the mixin right after vanilla updated the player input for this tick. */
    public static void onAiStepAfterInput(Minecraft mc) {
        // Exactly one tick's worth is consumed here, and only here: the hold decrements atomically and
        // clears itself at expiry, so a ticket can never write more times than it asked for.
        Guard.InputCommand pending = HOLD.nextWrite();
        if (pending == null || mc.player == null) {
            return;
        }
        Guard.Decision decision = guardedWriter.submit(pending, inputOp, new MinecraftSessionState(mc),
                activation, Bridge.Clock.system());
        if (!decision.allowed() || decision.noop()) {
            // Refusal or no-op ends the hold immediately: no tick budget may survive into a later
            // ticket (leaking budget was the other half of the P7 defect).
            HOLD.clear();
            return;
        }
        var clamped = guardedWriter.lastCommand() == null ? pending : guardedWriter.lastCommand();
        mc.player.input.forwardImpulse = clamped.forward();
        mc.player.input.leftImpulse = clamped.strafe();
        mc.player.setYRot(mc.player.getYRot() + clamped.yawDelta());
        mc.player.setXRot(mc.player.getXRot() + clamped.pitchDelta());
        if (clamped.jump()) {
            mc.player.input.jumping = true;
        }
        if (clamped.sneak()) {
            mc.player.input.shiftKeyDown = true;
        }
    }

    public static int writesPerformed() {
        return guardedWriter == null ? 0 : guardedWriter.writesPerformed();
    }

    public static int directWrites() {
        return writeCount;
    }

    public static ClientModel clientModel() {
        return new MinecraftClientModel(Minecraft.getInstance(), config == null ? null : config.dir());
    }

    /**
     * Per-frame hook: one frame duration per rendered frame, so {@code bench.read} can report a real
     * window without any op ever blocking the render thread waiting for future frames.
     */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            MinecraftClientModel.recordFrame(System.nanoTime());
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Count real client ticks first: the container synchronisation window is measured in ticks the
        // client actually ran, so it always converges (P12) — a wall-clock window did not.
        MinecraftClientModel.noteClientTick();
        if (relay == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPoll < config.pollIntervalMs()) {
            return;
        }
        lastPoll = now;
        relay.tick();
    }
}
