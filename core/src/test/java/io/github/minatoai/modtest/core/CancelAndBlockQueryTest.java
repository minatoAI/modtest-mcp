package io.github.minatoai.modtest.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The A/B/C batch, tested at the level the rules live at.
 *
 * <ul>
 *   <li><b>A</b> — the block reading has three unmistakable states (known / air / unknown) and the cheap
 *       movement bit says when it does not know;</li>
 *   <li><b>B</b> — every version line agrees (Gradle, pyproject, the MCP server, the Forge metadata);</li>
 *   <li><b>C</b> — the two cancellation tiers stop at the right moment, a stop is never renewed (P7), and
 *       the two non-failure terminations ({@code E_STOPPED}, {@code E_SUPERSEDED}) are what they say.</li>
 * </ul>
 */
class CancelAndBlockQueryTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Bridge.Clock CLOCK = Bridge.Clock.fixed(NOW);
    private static final String TOKEN_VALUE = "tok-abc-secret";

    // ---------------------------------------------------------------- harness

    private static Bridge.BridgeConfig config() {
        return new Bridge.BridgeConfig(Path.of("."), 500L, "abc-test", "0.1.0", true,
                Bridge.BusyPolicy.ANSWER_BUSY, 64, Guard.HostWhitelist.of("127.0.0.1"));
    }

    private static Guard.MutationGuard guard(List<String> auditLines) {
        return new Guard.MutationGuard(
                new Guard.InputInjectionPolicy(Guard.HostWhitelist.of("127.0.0.1"),
                        Guard.BuildVariant.GUARDED, "abc-test", () -> "minecraft:overworld"),
                Guard.SessionState.singleplayer(),
                new Guard.ActivationState(true, new Guard.ActivationToken(TOKEN_VALUE, NOW + 60_000L)),
                CLOCK, Guard.AuditSink.to(auditLines::add), "ALLOWED-MUTATION");
    }

    private static Protocol.Receipt run(String opsJson, ClientModel client, List<String> auditLines) {
        Executor.ExecContext ctx = new Executor.ExecContext(config(), Guard.SessionState.singleplayer(),
                new Guard.ActivationState(true, new Guard.ActivationToken(TOKEN_VALUE, NOW + 60_000L)),
                CLOCK, Map.of("allow-mutate", true), client, guard(auditLines));
        Protocol.Ticket ticket = Protocol.Ticket.fromJson(Json.parseObject(
                "{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"abc\",\"ops\":[" + opsJson + "]}"), "abc");
        return new Executor.TicketExecutor(config(), VanillaOps.install(
                new Executor.OpCatalog("abc-test", "0.1.0", "abc-test"))).execute(ticket, Executor.quietLog(), ctx);
    }

    private static Protocol.Receipt.OpResult only(Protocol.Receipt receipt) {
        assertEquals(1, receipt.ops().size(), receipt.toString());
        return receipt.ops().get(0);
    }

    private static JsonObject result(Protocol.Receipt receipt) {
        Protocol.Receipt.OpResult r = only(receipt);
        assertTrue(r.ok(), "expected success, got " + r.error());
        assertNotNull(r.result());
        return r.result();
    }

    private static String code(Protocol.Receipt receipt) {
        Protocol.Receipt.Error e = only(receipt).error();
        assertNotNull(e, "a failed op must carry a structured error: " + only(receipt).result());
        return e.code();
    }

    private static Protocol.Receipt runOne(String opJson, ClientModel client) {
        return run(opJson, client, new ArrayList<>());
    }

    // ================================================================ A: block reading

    @Test
    void aBlockCellThatCannotBeReadIsReportedAsUnknownAndNeverAsAir() {
        FakeClient client = new FakeClient();
        client.unknownBlocks.add("5,70,-3");   // e.g. an unloaded chunk or outside the build height

        JsonObject out = result(runOne("{\"op\":\"state.query\",\"params\":{\"what\":[\"block\"],"
                + "\"x\":5,\"y\":70,\"z\":-3}}", client));

        assertFalse(out.get("blockKnown").getAsBoolean(), "an unreadable cell must not be known: " + out);
        assertTrue(out.get("block").isJsonNull(), "and its id must be null, never \"\" (air): " + out);
        assertTrue(out.get("blockIsAir").isJsonNull(), "air must not be claimed for an unread cell: " + out);
        assertTrue(out.get("blockReplaceable").isJsonNull(),
                "replaceability must be unknown too, not false: " + out);
        assertEquals("minecraft:overworld", out.get("blockDimension").getAsString());
    }

    @Test
    void aKnownBlockCellReportsItsIdAndReplaceability() {
        FakeClient client = new FakeClient();
        client.blocks.put("5,70,-3", "minecraft:tall_grass");
        client.replaceable.put("5,70,-3", true);

        JsonObject out = result(runOne("{\"op\":\"state.query\",\"params\":{\"what\":[\"block\"],"
                + "\"x\":5,\"y\":70,\"z\":-3}}", client));

        assertTrue(out.get("blockKnown").getAsBoolean(), out.toString());
        assertEquals("minecraft:tall_grass", out.get("block").getAsString());
        assertFalse(out.get("blockIsAir").getAsBoolean(), out.toString());
        // The implicit goal of A: "is this cell replaceable" is answered by our own op, so the judgement
        // no longer needs a KubeJS probe.
        assertTrue(out.get("blockReplaceable").getAsBoolean(),
                "the query must answer whether the cell can be replaced: " + out);
    }

    @Test
    void aKnownAirCellIsReportedAsAirExplicitlyNotAsUnknown() {
        FakeClient client = new FakeClient();   // an unrecorded cell is air, as a real empty world is

        JsonObject out = result(runOne("{\"op\":\"state.query\",\"params\":{\"what\":[\"block\"],"
                + "\"x\":1,\"y\":64,\"z\":1}}", client));

        assertTrue(out.get("blockKnown").getAsBoolean(), out.toString());
        assertEquals("minecraft:air", out.get("block").getAsString(),
                "air is spelled out, so it can never be confused with unknown: " + out);
        assertTrue(out.get("blockIsAir").getAsBoolean(), out.toString());
    }

    @Test
    void aBlockQueryWithoutCoordinatesFailsLoudlyInsteadOfAnsweringAboutAnotherCell() {
        FakeClient client = new FakeClient();

        Protocol.Receipt r = runOne("{\"op\":\"state.query\",\"params\":{\"what\":[\"block\"]}}", client);

        assertEquals("E_BAD_PARAMS", code(r));
    }

    @Test
    void theBlockReadingIsNotPartOfAllBecauseItNeedsCoordinates() {
        FakeClient client = new FakeClient();

        JsonObject out = result(runOne("{\"op\":\"state.query\",\"params\":{\"what\":[\"all\"]}}", client));

        assertFalse(out.has("blockKnown"), "`all` must not silently answer about a cell: " + out);
        assertTrue(out.has("pose"), "but it still reports everything that needs no arguments: " + out);
    }

    @Test
    void theCheapMovementBitSaysWhenItCannotTell() {
        FakeClient moving = new FakeClient();
        moving.moving = true;
        JsonObject yes = result(runOne("{\"op\":\"state.query\",\"params\":{\"what\":[\"moving\"]}}", moving));
        assertTrue(yes.get("movingKnown").getAsBoolean(), yes.toString());
        assertTrue(yes.get("moving").getAsBoolean(), yes.toString());

        FakeClient unknown = new FakeClient();   // moving left null = the client cannot tell
        JsonObject no = result(runOne("{\"op\":\"state.query\",\"params\":{\"what\":[\"moving\"]}}", unknown));
        assertFalse(no.get("movingKnown").getAsBoolean(), no.toString());
        assertTrue(no.get("moving").isJsonNull(), "unknown must never be reported as `not moving`: " + no);
    }

    // ================================================================ C: the two tiers

    @Test
    void anImmediateStopCancelsAndIsReportedAsApplied() {
        FakeClient client = new FakeClient();
        client.stopResult = new ClientModel.StopResult(true, true, true, 0, false, false);
        List<String> audit = new ArrayList<>();

        JsonObject out = result(run("{\"op\":\"input.stop\",\"params\":{\"mode\":\"immediate\"}}", client, audit));

        assertEquals("immediate", client.stopMode, "the tier must reach the adapter");
        assertEquals("immediate", out.get("mode").getAsString());
        assertTrue(out.get("stopped").getAsBoolean(), out.toString());
        assertEquals("applied", out.get("verdict").getAsString(), out.toString());
        assertEquals(1, audit.size(), "an allowed write op leaves exactly one audit line: " + audit);
    }

    @Test
    void aSafeStopThatCanHappenNowIsAlsoApplied() {
        FakeClient client = new FakeClient();
        client.stopResult = new ClientModel.StopResult(true, true, true, 0, false, false);

        JsonObject out = result(runOne("{\"op\":\"input.stop\",\"params\":{\"mode\":\"safe\"}}", client));

        assertEquals("safe", client.stopMode);
        assertTrue(out.get("stopped").getAsBoolean(), out.toString());
        assertTrue(out.get("atSafePoint").getAsBoolean(), out.toString());
        assertEquals("applied", out.get("verdict").getAsString(), out.toString());
    }

    @Test
    void anArmedSafeStopDoesNotClaimThePlayerHasStopped() {
        FakeClient client = new FakeClient();
        client.stopResult = new ClientModel.StopResult(true, false, false, 0, false, true);   // armed

        JsonObject out = result(runOne("{\"op\":\"input.stop\",\"params\":{\"mode\":\"safe\",\"ticks\":20}}",
                client));

        assertFalse(out.get("stopped").getAsBoolean(), out.toString());
        assertTrue(out.get("armed").getAsBoolean(), out.toString());
        assertEquals("notClientVerifiable", out.get("verdict").getAsString(),
                "an armed stop has not stopped anything yet: " + out);
        assertEquals(20, client.stopMaxTicks, "the bound must reach the adapter");
    }

    @Test
    void stoppingNothingIsANonFailureTerminationCalledStopped() {
        FakeClient client = new FakeClient();
        client.stopResult = new ClientModel.StopResult(false, false, null, 0, false, false);

        Protocol.Receipt r = runOne("{\"op\":\"input.stop\",\"params\":{\"mode\":\"immediate\"}}", client);

        assertEquals("E_STOPPED", code(r));
        assertTrue(Protocol.ErrorCode.E_STOPPED.nonFailureTermination(),
                "E_STOPPED must be classified as a non-failure termination");
        assertFalse(Protocol.ErrorCode.E_PRECONDITION.nonFailureTermination(),
                "and the existing codes must keep their meaning");
    }

    @Test
    void aStopSupersededByANewerCommandIsReportedAsSuchAndDoesNotLieAboutStopping() {
        FakeClient client = new FakeClient();
        client.stopResult = new ClientModel.StopResult(true, false, false, 0, true, false);

        Protocol.Receipt r = runOne("{\"op\":\"input.stop\",\"params\":{\"mode\":\"safe\"}}", client);

        assertEquals("E_SUPERSEDED", code(r));
        assertTrue(Protocol.ErrorCode.E_SUPERSEDED.nonFailureTermination());
    }

    @Test
    void theTierIsRequiredSoNobodyStopsByAccident() {
        FakeClient client = new FakeClient();
        client.stopResult = new ClientModel.StopResult(true, true, true, 0, false, false);

        Protocol.Receipt missing = runOne("{\"op\":\"input.stop\",\"params\":{}}", client);
        assertEquals("E_BAD_PARAMS", code(missing));
        assertEquals(0, client.stopInputCalls, "a bad tier must not reach the client");

        Protocol.Receipt wrong = runOne("{\"op\":\"input.stop\",\"params\":{\"mode\":\"whenever\"}}", client);
        assertEquals("E_BAD_PARAMS", code(wrong));
        assertEquals(0, client.stopInputCalls);
    }

    // ================================================================ C: the state machines

    @Test
    void theImmediateTierStopsOnTheFirstTickWhateverTheFootingSays() {
        StopRequest req = new StopRequest(StopRequest.Tier.IMMEDIATE, 20, "test");

        assertTrue(req.tick(false), "immediate means now, even mid-air");
        assertTrue(req.stopped());
        assertFalse(req.boundReached());
        assertFalse(req.tick(false), "and it must not stop twice");
    }

    @Test
    void theSafeTierWaitsForASafePointAndStopsExactlyThere() {
        StopRequest req = new StopRequest(StopRequest.Tier.SAFE, 20, "test");

        assertFalse(req.tick(false), "unsafe: keep moving");
        assertFalse(req.tick(false));
        assertFalse(req.stopped());
        assertTrue(req.tick(true), "the first safe tick is when it stops");
        assertTrue(req.stopped());
        assertEquals(3, req.ticksUsed());
        assertTrue(req.atSafePoint());
        assertFalse(req.boundReached());
    }

    @Test
    void theSafeTierIsBoundedAndSaysItNeverReachedASafePoint() {
        StopRequest req = new StopRequest(StopRequest.Tier.SAFE, 3, "test");

        assertFalse(req.tick(false));
        assertFalse(req.tick(false));
        assertTrue(req.tick(false), "the bound must end the wait — a stop can never wait forever");
        assertTrue(req.stopped());
        assertTrue(req.boundReached(), "and the receipt has to admit it was not at a safe point");
        assertFalse(req.atSafePoint());
    }

    @Test
    void anUnknownFootingIsNotTreatedAsSafe() {
        StopRequest req = new StopRequest(StopRequest.Tier.SAFE, 3, "test");

        assertFalse(req.tick(null), "null is not a safe point, it is not knowing");
        assertFalse(req.tick(null));
        assertTrue(req.tick(null), "the bound still ends it");
        assertTrue(req.stopped());
        assertTrue(req.boundReached());
    }

    @Test
    void aSupersededStopLetsTheNewCommandKeepThePlayer() {
        StopRequest req = new StopRequest(StopRequest.Tier.SAFE, 20, "test");
        req.supersede();

        assertFalse(req.tick(true), "a superseded stop must not cancel the command that replaced it");
        assertTrue(req.superseded());
        assertTrue(req.done());
    }

    @Test
    void aStopRequestMustReallyStopAndMustNeverBeRenewed() {
        InputHold hold = new InputHold();
        hold.install("t-old", Guard.InputCommand.fromParams(Json.parseObject("{\"forward\":1.0}")), 20);

        assertEquals(1, hold.nextWrite() == null ? 0 : 1, "the hold is live");
        hold.stop("input.stop:immediate");

        for (int i = 0; i < 30; i++) {
            assertNull(hold.nextWrite(),
                    "P7: after a stop the hold must never write again (no renewal)");
        }
        assertEquals(0, hold.remaining(), "and no tick budget may survive the stop");
        assertEquals("input.stop:immediate", hold.stopReason(), "the stop is recorded as a stop");
        assertEquals(1, hold.attributedWrites("t-old"), "the old ticket's write count is frozen at 1");

        // A later ticket installing its own hold is a new hold, not a renewal of the stopped one.
        hold.install("t-new", Guard.InputCommand.fromParams(Json.parseObject("{\"forward\":0.0}")), 2);
        assertNull(hold.stopReason(), "a fresh hold is not a stopped one");
        assertEquals(2, hold.remaining(), "and it has its own budget");
    }

    // ================================================================ B: one version line

    @Test
    void everyVersionLineAgreesOnAlphaFour() throws IOException {
        List<String> missing = new ArrayList<>();
        String gradle = versionFrom("gradle.properties", "version=(.+)", missing);
        String pyproject = versionFrom("pyproject.toml", "version\\s*=\\s*\"(.+)\"", missing);
        String server = versionFrom(Path.of("src", "modtest_mcp", "server.py").toString(),
                "__version__\\s*=\\s*\"(.+)\"", missing);
        String mods = versionFrom(Path.of("forge", "src", "main", "resources", "META-INF", "mods.toml").toString(),
                "(?m)^version\\s*=\\s*\"(.+)\"", missing);

        assertTrue(missing.isEmpty(), "could not read the version line(s) from " + missing);
        String expected = "1.0.0a4";   // PEP 440 for 1.0.0-alpha.4
        for (String v : List.of(gradle, pyproject, server, mods)) {
            assertEquals(expected, pep440(v), "every version line must name the same release");
        }
    }

    @Test
    void theTwoReservedCodesHaveNoProducerYetAndThatIsPinned() throws IOException {
        // E_NO_PATH / E_STUCK are declared for the movement planner (walk.within, task-80) and are
        // deliberately NOT produced by anything today. Declaring a code with no producer is only honest
        // while it is pinned: the day someone wires one up, this test goes red and the protocol's
        // "reserved" wording has to be revisited in that same change.
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(Path.of("core", "src", "main", "java"),
                Path.of("..", "core", "src", "main", "java"))) {
            if (!Files.exists(root)) {
                continue;
            }
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (file.getFileName().toString().equals("Protocol.java")) {
                        continue;   // the declaration itself, plus its documentation
                    }
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    if (text.contains("E_NO_PATH") || text.contains("E_STUCK")) {
                        offenders.add(file.toString());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "E_NO_PATH/E_STUCK are reserved (PROTOCOL §4.1: no producer yet); a change that wires one "
                        + "up must remove the reservation in the same change. Offenders: " + offenders);
        assertTrue(Protocol.ErrorCode.E_NO_PATH.nonFailureTermination());
        assertTrue(Protocol.ErrorCode.E_STUCK.nonFailureTermination());
    }

    /** Gradle writes the prerelease with a dash; PEP 440 writes it as a letter suffix. */
    private static String pep440(String v) {
        return v.replace("-alpha.", "a").replace("-alpha-", "a");
    }

    @Test
    void theForgeAdapterStopsByCancellingTheHoldAndNeverByReinstallingIt() throws IOException {
        String model = forgeSource("MinecraftClientModel.java");
        assertTrue(model.contains("hold.stop("),
                "the stop must cancel the hold (a cancellation cannot be renewed)");
        assertFalse(model.contains("hold.install("),
                "and it must never install a hold while stopping: that is the P7 renewal defect");
        assertTrue(model.contains("isOutsideBuildHeight"),
                "an unreadable cell (unloaded chunk / outside the build height) must be unknown, not air");
        assertTrue(model.contains("canBeReplaced"),
                "the replaceability answer must come from the block state itself");

        String mod = forgeSource("ModtestHarnessMod.java");
        assertTrue(mod.contains("MinecraftClientModel.armedStop()"),
                "the client-tick loop must apply an armed safe-point stop");
        assertTrue(mod.contains("HOLD.stop("),
                "and applying it must cancel the hold");
        assertTrue(mod.contains("supersedeArmedStop()"),
                "a new input command must make a waiting stop superseded, so the new command keeps the player");
    }

    private static String forgeSource(String name) throws IOException {
        Path base = Path.of("forge", "src", "main", "java", "io", "github", "minatoai", "modtest", "forge", name);
        for (Path candidate : List.of(base, Path.of("..").resolve(base))) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find the forge source " + name);
    }

    private static String versionFrom(String relative, String regex, List<String> missing) throws IOException {
        for (Path candidate : List.of(Path.of(relative), Path.of("..", relative))) {
            if (!Files.exists(candidate)) {
                continue;
            }
            String text = Files.readString(candidate, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        missing.add(relative);
        return "";
    }
}
