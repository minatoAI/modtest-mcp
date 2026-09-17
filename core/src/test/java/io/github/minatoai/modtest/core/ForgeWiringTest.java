package io.github.minatoai.modtest.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The task-70 batch is only "wired" if the <b>game-side adapter</b> stops refusing the five ops, and
 * only "safe" if the adapter's executor carries the write-op guard.
 *
 * <p>Both are checked here, in the two ways that are actually available without a running client:
 * <ul>
 *   <li><b>Behaviour</b>: an adapter that still answers {@code E_UNSUPPORTED} for a core-implemented op
 *       must be named loudly ({@code NOT-WIRED-DEFECT}) — so "core implemented it, the game side does
 *       not" can never look like a protocol gap. A working adapter must produce no such line. This is
 *       the "missed wiring goes red" control: remove the backstop and the first test fails.</li>
 *   <li><b>Wiring</b>: the Forge entry point must hand a real {@link Guard.MutationGuard} to every
 *       {@code ExecContext} it builds, and {@code MinecraftClientModel} must no longer contain stub
 *       refusals for the five ops. This is a source-level assertion on purpose: a forge unit test would
 *       need a started Minecraft, which the core test suite deliberately does not have.</li>
 * </ul>
 */
class ForgeWiringTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Bridge.Clock CLOCK = Bridge.Clock.fixed(NOW);

    /** The five ops whose core implementation exists; the game side must stop answering E_UNSUPPORTED. */
    private static final List<String> WIRED_OPS =
            List.of("inv.click", "inv.toss", "use.item", "shot.capture", "bench.read");

    /** An adapter that behaves normally everywhere except the five ops, which are still stubbed. */
    private static final class StillStubbed extends FakeClient {
        @Override
        public void clickSlot(int slot, int button, String mode) {
            throw stubbed("inv.click");
        }

        @Override
        public void tossSlot(int slot, int count) {
            throw stubbed("inv.toss");
        }

        @Override
        public void useItem() {
            throw stubbed("use.item");
        }

        @Override
        public void useItem(boolean offHand) {
            throw stubbed("use.item");
        }

        @Override
        public CapturedFrame capture(String name) {
            throw stubbed("shot.capture");
        }

        @Override
        public BenchResult benchWindow(int warmupFrames, int sampleFrames) {
            throw stubbed("bench.read");
        }

        private static Protocol.ProtocolException stubbed(String op) {
            return new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                    op + " is not wired yet");
        }
    }

    private static Executor.OpCatalog catalog() {
        return VanillaOps.install(new Executor.OpCatalog("forge-wiring-test", "0.1.0", "wiring-test"));
    }

    private static Bridge.BridgeConfig config() {
        return new Bridge.BridgeConfig(Path.of("."), 4_000L, "forge-wiring-test", "0.1.0", true,
                Bridge.BusyPolicy.ANSWER_BUSY, 64, Guard.HostWhitelist.empty());
    }

    private static Executor.ExecContext ctx(ClientModel client, List<String> audit) {
        Bridge.BridgeConfig config = config();
        Guard.ActivationState armed = new Guard.ActivationState(true,
                new Guard.ActivationToken("tok-wiring", NOW + 60_000L));
        return new Executor.ExecContext(config, Guard.SessionState.singleplayer(), armed, CLOCK,
                Map.of("allow-mutate", true), client,
                new Guard.MutationGuard(
                        new Guard.InputInjectionPolicy(Guard.HostWhitelist.empty(),
                                Guard.BuildVariant.GUARDED, "forge-wiring-test",
                                () -> "minecraft:overworld"),
                        Guard.SessionState.singleplayer(), armed, CLOCK, Guard.AuditSink.to(audit::add)));
    }

    private static Protocol.Receipt run(ClientModel client, List<String> audit, List<String> opLog) {
        StringBuilder ops = new StringBuilder();
        int i = 0;
        for (String op : WIRED_OPS) {
            if (i++ > 0) {
                ops.append(',');
            }
            ops.append("{\"id\":\"op").append(i).append("\",\"op\":\"").append(op).append('"');
            if ("inv.click".equals(op)) {
                ops.append(",\"params\":{\"slot\":0,\"mode\":\"pickup\"}");
            } else if ("inv.toss".equals(op)) {
                // A different slot than inv.click: the click empties its slot, and a toss from the emptied
                // slot would be a precondition failure rather than the wiring under test.
                ops.append(",\"params\":{\"slot\":3,\"count\":1}");
            }
            ops.append('}');
        }
        Protocol.Ticket ticket = Protocol.Ticket.fromJson(Json.parseObject(
                "{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"wiring\",\"on_error\":\"continue\",\"ops\":["
                        + ops + "]}"), "wiring");
        return new Executor.TicketExecutor(config(), catalog()).execute(ticket, opLog::add, ctx(client, audit));
    }

    @Test
    void anAdapterThatStillRefusesACoreImplementedOpIsNamedLoudly() {
        FakeClient client = new StillStubbed();
        client.windowId = 0;
        client.slots.set(0, "minecraft:stone");
        List<String> opLog = new ArrayList<>();

        Protocol.Receipt receipt = run(client, new ArrayList<>(), opLog);

        Set<String> named = new LinkedHashSet<>();
        String marker = "NOT-WIRED-DEFECT op=";
        for (String line : opLog) {
            int at = line.indexOf(marker);
            if (at < 0) {
                continue;
            }
            int start = at + marker.length();
            int end = line.indexOf(':', start);
            named.add(line.substring(start, end < 0 ? line.length() : end));
        }
        for (String op : WIRED_OPS) {
            Protocol.Receipt.OpResult result = receipt.ops().stream()
                    .filter(r -> op.equals(r.op())).findFirst().orElseThrow();
            assertEquals("E_UNSUPPORTED", result.error().code(), op);
            assertTrue(named.contains(op),
                    op + " still answered E_UNSUPPORTED from the adapter but was not named by "
                            + "NOT-WIRED-DEFECT; log was " + opLog);
        }
    }

    @Test
    void aWorkingAdapterProducesNoSuchWarningAndSucceeds() {
        // The control: the same ticket against an adapter that implements the ops must be quiet and green,
        // so the warning above measures the wiring and not the test fixture.
        FakeClient client = new FakeClient();
        client.windowId = 0;
        client.slots.set(0, "minecraft:stone");
        List<String> opLog = new ArrayList<>();
        List<String> audit = new ArrayList<>();

        Protocol.Receipt receipt = run(client, audit, opLog);

        assertTrue(receipt.ok(), receipt.ops().toString());
        for (String line : opLog) {
            assertFalse(line.contains("NOT-WIRED-DEFECT"), line);
        }
        assertEquals(3, audit.size(), "the three write ops are each audited once: " + audit);
    }

    // ---------------------------------------------------------------- source-level wiring

    private static Path forgeSource(String name) {
        for (Path candidate : List.of(
                Path.of("forge", "src", "main", "java", "io", "github", "minatoai", "modtest", "forge", name),
                Path.of("..", "forge", "src", "main", "java", "io", "github", "minatoai", "modtest", "forge",
                        name))) {
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
        }
        throw new AssertionError("forge source not found: " + name + " from " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** The text inside each {@code new Executor.ExecContext(...)}, by balanced parentheses. */
    private static List<String> execContextArguments(String source) {
        List<String> out = new ArrayList<>();
        String needle = "new Executor.ExecContext(";
        int at = source.indexOf(needle);
        while (at >= 0) {
            int i = at + needle.length();
            int depth = 1;
            StringBuilder args = new StringBuilder();
            while (i < source.length() && depth > 0) {
                char c = source.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                args.append(c);
                i++;
            }
            out.add(args.toString());
            at = source.indexOf(needle, i);
        }
        return out;
    }

    @Test
    void theForgeEntryPointHandsTheMutationGuardToEveryExecutorContextItBuilds() throws IOException {
        String source = read(forgeSource("ModtestHarnessMod.java"));

        assertTrue(source.contains("new Guard.MutationGuard("),
                "the Forge entry point must construct the write-op guard");
        List<String> contexts = execContextArguments(source);
        assertFalse(contexts.isEmpty(), "expected at least one ExecContext in the Forge entry point");
        for (String args : contexts) {
            assertTrue(args.contains("mutationGuard"),
                    "every ExecContext the Forge entry point builds must carry the guard (otherwise the "
                            + "executor substitutes a fail-closed one and every write op is refused at "
                            + "runtime); arguments were: " + args);
        }
    }

    @Test
    void theForgeClientModelNoLongerRefusesTheFiveOps() throws IOException {
        String source = read(forgeSource("MinecraftClientModel.java"));

        assertFalse(source.contains("is not wired yet"),
                "MinecraftClientModel must not keep a stub refusal for the five task-70 ops");
        for (String marker : List.of("handleInventoryMouseClick", "ClickType.THROW",
                "Screenshot.takeScreenshot", "benchWindow", "useItem(boolean")) {
            assertTrue(source.contains(marker),
                    "MinecraftClientModel is missing the wiring for " + marker);
        }
        // The two legacy entry points may still refuse (core no longer calls them), but each must say so.
        assertTrue(source.contains("the legacy screenshot path is gone"));
    }

    /**
     * P11: the world write went around the player. The spike showed that {@code setBlockAndUpdate} never
     * produces a KubeJS {@code BlockEvents.placed} (the event type existed, the handler was registered,
     * and a positive control fired), so the adapter must use the interaction entry instead — and must not
     * keep a direct write anywhere.
     */
    @Test
    void theForgeAdapterPlacesThroughThePlayerInteractionPathAndNeverWritesTheWorldDirectly()
            throws IOException {
        String source = read(forgeSource("MinecraftClientModel.java"));

        assertTrue(source.contains("mc.gameMode.useItemOn("),
                "world.place must go through MultiPlayerGameMode.useItemOn (the player interaction path)");
        assertTrue(source.contains("BlockHitResult"),
                "the interaction needs a real aim: a block hit result against a neighbouring face");
        assertFalse(source.contains(".setBlockAndUpdate("),
                "P11: no direct world write may remain in the adapter — that path is invisible to the "
                        + "server, to Forge/KubeJS events and to protection plugins");
        assertFalse(source.contains("void placeBlock("),
                "the adapter must not implement the direct-write entry at all (the interface default "
                        + "refuses it)");
        assertTrue(source.contains("new ServerboundSetCarriedItemPacket("),
                "the audited same-family fix: selecting a slot must reach the server, which learns the "
                        + "carried slot only from this packet (otherwise the server places the wrong item)");
        assertTrue(source.contains("getBlockReach"),
                "a real placement is bounded by the player's block reach, and must refuse honestly");
    }

    /**
     * The audited same-family fix: the server learns the carried hotbar slot <b>only</b> from
     * {@code ServerboundSetCarriedItemPacket}. Writing {@code inventory.selected} alone leaves the server
     * on the old slot — which would make the new {@code world.place} place the wrong item (the server
     * decides what is in hand). Scoped to the method, not the file, so a stray mention elsewhere cannot
     * satisfy it.
     */
    @Test
    void theForgeAdapterTellsTheServerWhichSlotWasSelected() throws IOException {
        String source = read(forgeSource("MinecraftClientModel.java"));
        String body = methodBody(source, "public void selectSlot(");

        assertTrue(body.contains("getInventory().selected = slot"),
                "selectSlot must still set the local index: " + body);
        assertTrue(body.contains("new ServerboundSetCarriedItemPacket("),
                "and must tell the server, which learns the carried slot only from that packet: " + body);
        assertTrue(body.contains("getConnection().send("),
                "the packet has to be sent on the connection: " + body);
    }

    /**
     * KubeJS exists only in a developer's instance {@code mods/} folder — never as a dependency of this
     * repository. Declared dependencies are what put a jar inside the product, so the check is mechanical:
     * no declarative build file may mention it. (Text in comments and docs is fine and deliberately not
     * policed here.)
     */
    @Test
    void noDeclarativeKubeJsDependencyExistsInTheBuild() {
        List<String> declarative = List.of("build.gradle", "settings.gradle", "gradle.properties",
                "core/build.gradle", "forge/build.gradle", "forge/src/main/resources/META-INF/mods.toml",
                "pyproject.toml");
        int checked = 0;
        for (String relative : declarative) {
            for (Path candidate : List.of(Path.of(relative), Path.of("..", relative))) {
                if (!Files.exists(candidate)) {
                    continue;
                }
                checked++;
                try {
                    String text = Files.readString(candidate, StandardCharsets.UTF_8)
                            .toLowerCase(java.util.Locale.ROOT);
                    assertFalse(text.contains("kubejs"),
                            candidate + " declares a KubeJS dependency (or mentions it as one): KubeJS is "
                                    + "a dev-instance mod, and a declared dependency is how it would end up "
                                    + "inside the product jar");
                } catch (IOException e) {
                    throw new AssertionError("could not read " + candidate, e);
                }
                break;
            }
        }
        assertTrue(checked >= 6,
                "expected to check the declarative build files, checked=" + checked);
    }

    /** The text inside a method, found by its signature and closed by balanced braces. */
    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "method not found in the adapter: " + signature);
        int open = source.indexOf('{', at);
        assertTrue(open > 0, "method has no body: " + signature);
        int depth = 1;
        int i = open + 1;
        StringBuilder body = new StringBuilder();
        while (i < source.length() && depth > 0) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            body.append(c);
            i++;
        }
        return body.toString();
    }
}
