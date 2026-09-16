package io.github.minatoai.modtest.forge;

import com.google.gson.JsonObject;
import io.github.minatoai.modtest.core.Bridge;
import io.github.minatoai.modtest.core.ClientModel;
import io.github.minatoai.modtest.core.Executor;
import io.github.minatoai.modtest.core.Guard;
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
@Mod(value = "modtestharness", dist = Dist.CLIENT)
public final class ModtestHarnessMod {
    public static final String MOD_ID = "modtestharness";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static final AtomicReference<Guard.InputCommand> PENDING = new AtomicReference<>();

    private static Bridge.BridgeConfig config;
    private static Relay.BridgeRelay relay;
    private static Guard.GuardedInputWriter guardedWriter;
    private static Guard.ActivationState activation = Guard.ActivationState.off();
    private static Protocol.OpSpec inputOp;
    private static long lastPoll;
    private static int writeCount;

    public ModtestHarnessMod() {
        Map<String, String> env = System.getenv();
        if (env.get("MODTEST_AGENT_DIR") == null) {
            LOG.info("[modtest-mcp] harness idle: MODTEST_AGENT_DIR is not set (off by default)");
            return;
        }
        config = new Bridge.BridgeConfig(Path.of(env.get("MODTEST_AGENT_DIR")), 500L, "forge-client",
                "0.1.0", Boolean.parseBoolean(env.getOrDefault("MODTEST_ALLOW_MUTATE", "false")),
                Bridge.BusyPolicy.ANSWER_BUSY, 64);

        boolean devFlag = Boolean.parseBoolean(env.getOrDefault("MODTEST_DEV_HARNESS", "false"));
        String tokenValue = env.get("MODTEST_ACTIVATION_TOKEN");
        long until = Long.parseLong(env.getOrDefault("MODTEST_ACTIVATION_UNTIL_MS", "0"));
        activation = new Guard.ActivationState(devFlag,
                tokenValue == null ? null : new Guard.ActivationToken(tokenValue, until));

        Executor.OpCatalog catalog = VanillaOps.install(
                new Executor.OpCatalog("forge-client", "0.1.0", "modtest-harness-forge"));
        inputOp = new Protocol.OpSpec("input.set", "Queue a player-input command", Json.object(), Json.object(),
                List.of(Protocol.Precondition.of("singleplayer")),
                List.of(Protocol.SideEffect.PLAYER_INPUT), "forge-client", null, "1.0");
        catalog.register(inputOp, ModtestHarnessMod::queueInput);

        guardedWriter = new Guard.GuardedInputWriter(
                command -> {
                    writeCount++;
                    PENDING.set(command);
                },
                new Guard.InputInjectionPolicy(), new Guard.HumanSpeedClamp());

        Executor.TicketExecutor executor = new Executor.TicketExecutor(config, catalog);
        Minecraft mc = Minecraft.getInstance();
        relay = new Relay.BridgeRelay(config, new Bridge.NioBridgeFs(config.dir()),
                new Relay.TicketValidator(catalog), executor,
                new Relay.ReceiptStore(new Bridge.NioBridgeFs(config.dir()), Bridge.Clock.system()),
                Bridge.Clock.system(), line -> LOG.info("[modtest-mcp] {}", line),
                () -> new Executor.ExecContext(config, new MinecraftSessionState(mc), activation,
                        Bridge.Clock.system(), Map.of("allow-mutate", config.allowMutate()),
                        new MinecraftClientModel(mc)));
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

    /** Op handler: never writes directly — it queues, and the mixin submits through the guard. */
    private static JsonObject queueInput(Protocol.Ticket.Op op, Executor.ExecContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        Guard.Decision decision = guardedWriter.submit(Guard.InputCommand.none(), inputOp,
                new MinecraftSessionState(mc), activation, Bridge.Clock.system());
        JsonObject out = JsonObject.class.cast(
                com.google.gson.JsonParser.parseString("{\"queued\":true}"));
        out.addProperty("allowed", decision.allowed());
        out.addProperty("noop", decision.noop());
        out.addProperty("reason", decision.reason());
        return out;
    }

    /** Called from the mixin right after vanilla updated the player input for this tick. */
    public static void onAiStepAfterInput(Minecraft mc) {
        Guard.InputCommand pending = PENDING.getAndSet(null);
        if (pending == null || mc.player == null) {
            return;
        }
        Guard.Decision decision = guardedWriter.submit(pending, inputOp, new MinecraftSessionState(mc),
                activation, Bridge.Clock.system());
        if (decision.allowed() && !decision.noop()) {
            mc.player.input.forwardImpulse = pending.forward();
            mc.player.input.leftImpulse = pending.strafe();
            mc.player.setYRot(mc.player.getYRot() + pending.yawDelta());
            mc.player.setXRot(mc.player.getXRot() + pending.pitchDelta());
        }
    }

    public static int writesPerformed() {
        return guardedWriter == null ? 0 : guardedWriter.writesPerformed();
    }

    public static int directWrites() {
        return writeCount;
    }

    public static ClientModel clientModel() {
        return new MinecraftClientModel(Minecraft.getInstance());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (relay == null || event.phase != TickEvent.Phase.END) {
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
