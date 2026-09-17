package io.github.minatoai.modtest.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-70: the five ops that used to be permanent {@code E_UNSUPPORTED} stubs — {@code inv.click},
 * {@code inv.toss}, {@code use.item}, {@code shot.capture}, {@code bench.read}.
 *
 * <p>What the tests in this class are written to prove, in the order the task asks for it:
 * <ol>
 *   <li><b>the stub is gone</b> — each op produces a result through the real catalog, with a field
 *       that can only exist if the implementation ran;</li>
 *   <li><b>the write ops go through the guard</b> — a refusal is {@code E_PRECONDITION} and touches
 *       nothing, while an allowance leaves exactly one loud audit line naming the op and the actor,
 *       with the token's 8-character fingerprint and never its value;</li>
 *   <li><b>the reads are layer 1</b> — tier-1 ops are never gated by the injection policy, even on a
 *       host we do not act on;</li>
 *   <li><b>requested ≠ observed</b> — the receipts carry the request and the measurement in
 *       <i>different</i> fields, and a partial application is visible as such;</li>
 *   <li><b>nothing is overstated</b> — {@code use.item} claims dispatch only, {@code shot.capture}
 *       reports bytes (and says the image is not interpreted), {@code bench.read} carries its window,
 *       units and sample count.</li>
 * </ol>
 */
class WiredOpsTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Bridge.Clock CLOCK = Bridge.Clock.fixed(NOW);
    private static final String TOKEN_VALUE = "tok-task70-secret";

    /** The five ops under test, and the handler class that must be the one serving them. */
    private static final List<String> WRITES = List.of("inv.click", "inv.toss", "use.item");
    private static final List<String> READS = List.of("shot.capture", "bench.read");

    // ---------------------------------------------------------------- harness

    private Executor.OpCatalog catalog() {
        return VanillaOps.install(new Executor.OpCatalog("wired-test", "0.1.0", "wired-ops-test"));
    }

    private static Bridge.BridgeConfig config(boolean allowMutate) {
        return new Bridge.BridgeConfig(Path.of("."), 500L, "wired-test", "0.1.0", allowMutate,
                Bridge.BusyPolicy.ANSWER_BUSY, 64, Guard.HostWhitelist.of("127.0.0.1"));
    }

    private static Guard.ActivationState armed() {
        return new Guard.ActivationState(true, new Guard.ActivationToken(TOKEN_VALUE, NOW + 60_000L));
    }

    private static Guard.MutationGuard mutationGuard(List<String> auditLines, Guard.SessionState session,
                                                    Guard.ActivationState activation, String prefix) {
        return new Guard.MutationGuard(
                new Guard.InputInjectionPolicy(Guard.HostWhitelist.of("127.0.0.1"),
                        Guard.BuildVariant.GUARDED, "wired-test", () -> "minecraft:overworld"),
                session, activation, CLOCK, Guard.AuditSink.to(auditLines::add), prefix);
    }

    /** A fully wired context: allow-mutate on, a guard present. */
    private Executor.ExecContext ctx(ClientModel client, Guard.SessionState session, List<String> auditLines,
                                     Guard.ActivationState activation) {
        return new Executor.ExecContext(config(true), session, activation, CLOCK,
                Map.of("allow-mutate", true), client,
                mutationGuard(auditLines, session, activation, "ALLOWED-MUTATION"));
    }

    private Executor.ExecContext ctx(ClientModel client, Guard.SessionState session) {
        return ctx(client, session, new ArrayList<>(), armed());
    }

    private Executor.ExecContext unwiredGuard(ClientModel client, Guard.SessionState session) {
        return new Executor.ExecContext(config(true), session, armed(), CLOCK,
                Map.of("allow-mutate", true), client);
    }

    private Protocol.Receipt run(String opsJson, Executor.ExecContext ctx) {
        Protocol.Ticket ticket = Protocol.Ticket.fromJson(Json.parseObject(
                "{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"wired\",\"ops\":[" + opsJson + "]}"), "wired");
        return new Executor.TicketExecutor(config(true), catalog()).execute(ticket, Executor.quietLog(), ctx);
    }

    private Protocol.Receipt run(String opsJson, ClientModel client) {
        return run(opsJson, ctx(client, Guard.SessionState.singleplayer()));
    }

    private static JsonObject params(String json) {
        return Json.parseObject(json);
    }

    private static Protocol.Receipt.OpResult only(Protocol.Receipt receipt) {
        assertEquals(1, receipt.ops().size());
        return receipt.ops().get(0);
    }

    private static String code(Protocol.Receipt receipt) {
        Protocol.Receipt.Error e = only(receipt).error();
        assertNotNull(e, "a failed op must carry a structured error: " + only(receipt).result());
        assertNotNull(e.code());
        return e.code();
    }

    private static JsonObject result(Protocol.Receipt receipt) {
        Protocol.Receipt.OpResult r = only(receipt);
        assertTrue(r.ok(), "expected success, got " + r.error());
        assertNotNull(r.result());
        return r.result();
    }

    private static String fail(String op, String paramsJson) {
        return paramsJson == null ? "{\"op\":\"" + op + "\"}" : "{\"op\":\"" + op + "\",\"params\":" + paramsJson + "}";
    }

    // ================================================================ inv.click

    @Test
    void clickIsNoLongerUnsupportedAndReportsWhatTheSlotLookedLikeAroundIt() {
        FakeClient client = new FakeClient();
        client.windowId = 3;
        client.slots.set(0, "minecraft:torch");

        JsonObject out = result(run(fail("inv.click", "{\"slot\":0,\"button\":0,\"mode\":\"pickup\"}"), client));

        assertEquals(1, client.clickCalls, "the click must actually have been dispatched");
        assertEquals(3, out.get("windowId").getAsInt(), "the click names the window it targeted");
        assertEquals("pickup", out.get("mode").getAsString());
        // The proof that the implementation ran (rather than a stub): before and after are read from
        // the client, and the request never appears where a measurement belongs.
        assertEquals("minecraft:torch", out.getAsJsonObject("before").get("id").getAsString());
        assertEquals(1, out.getAsJsonObject("before").get("count").getAsInt());
        assertEquals("", out.getAsJsonObject("after").get("id").getAsString());
        assertEquals(0, out.getAsJsonObject("after").get("count").getAsInt());
        assertEquals("applied", out.get("verdict").getAsString());
        assertTrue(out.getAsJsonObject("applied").has("slot 0"), out.toString());
        assertFalse(out.getAsJsonObject("skipped").has("slot 0"), out.toString());
    }

    @Test
    void aClickWhoseReadBackHasNotCaughtUpIsNeverReportedAsSkipped() {
        // P9. This test used to assert `skipped`, which is exactly the defect qa-tester measured on a
        // real client: the click worked, the read-back was simply early, and the receipt said "did not
        // happen" — an agent reading that retries the click and duplicates the action.
        FakeClient client = new FakeClient();
        client.windowId = 3;
        client.slots.set(0, "minecraft:torch");
        client.clickApplies = false;

        JsonObject out = result(run(fail("inv.click", "{\"slot\":0,\"mode\":\"pickup\"}"), client));

        assertEquals(1, client.clickCalls);
        assertNotEquals("skipped", out.get("verdict").getAsString(),
                "an unchanged read-back is not evidence that the click did nothing: " + out);
        assertEquals("notClientVerifiable", out.get("verdict").getAsString());
        JsonObject nv = out.getAsJsonObject("notClientVerifiable").getAsJsonObject("slot 0");
        assertTrue(nv.has("requested"), "the request stays separate from the reading: " + out);
        assertTrue(nv.get("reason").getAsString().contains("NOT evidence"), out.toString());
        assertEquals(0, out.getAsJsonObject("applied").size(), "nothing may be claimed as applied");
        assertEquals(0, out.getAsJsonObject("skipped").size(),
                "nothing may be claimed as skipped either: " + out);
    }

    @Test
    void aRemoteClickIsReportedAsNotClientVerifiableBecauseTheServerOwnsTheMenu() {
        FakeClient client = new FakeClient();
        client.windowId = 3;
        client.slots.set(0, "minecraft:torch");
        List<String> audit = new ArrayList<>();

        JsonObject out = result(run(fail("inv.click", "{\"slot\":0,\"mode\":\"pickup\"}"),
                ctx(client, Guard.SessionState.remote("127.0.0.1:25585"), audit, armed())));

        assertEquals(1, client.clickCalls, "the request really was dispatched");
        assertEquals("notClientVerifiable", out.get("verdict").getAsString());
        assertNotNull(out.getAsJsonObject("notClientVerifiable").getAsJsonObject("slot 0"));
        assertTrue(out.get("note").getAsString().contains("cannot confirm"), out.toString());
        assertEquals(1, audit.size(), "an allowed write op leaves exactly one audit line");
    }

    @Test
    void clickingAnArmorSlotIsRefusedEvenWhenTheItemIsIdentical() {
        FakeClient client = new FakeClient();
        client.windowId = 0;
        client.slots.set(0, "minecraft:diamond_helmet");

        Protocol.Receipt r = run(fail("inv.click", "{\"slot\":36,\"mode\":\"quick_move\"}"), client);

        assertEquals("E_PRECONDITION", code(r));
        assertTrue(only(r).error().message().contains("armor"), only(r).error().message());
        assertEquals(0, client.clickCalls, "a refused click must not reach the client");
    }

    @Test
    void clickingAContainerSlotWithNoContainerOpenIsRefused() {
        FakeClient client = new FakeClient(41);   // a full player inventory, windowId still -1
        Protocol.Receipt r = run(fail("inv.click", "{\"slot\":20,\"mode\":\"pickup\"}"), client);

        assertEquals("E_PRECONDITION", code(r));
        assertTrue(only(r).error().message().contains("no container"), only(r).error().message());
        assertEquals(0, client.clickCalls);
    }

    @Test
    void clickParamProblemsAreBadParamsNotPreconditions() {
        FakeClient client = new FakeClient();
        client.windowId = 0;
        assertEquals("E_BAD_PARAMS", code(run(fail("inv.click", "{\"slot\":99}"), client)),
                "an out-of-range slot is a parameter error");
        assertEquals("E_BAD_PARAMS", code(run(fail("inv.click", "{\"slot\":0,\"mode\":\"teleport\"}"), client)),
                "the mode vocabulary is closed");
        assertEquals(0, client.clickCalls);
    }

    // ================================================================ inv.toss

    @Test
    void tossReportsTheRequestedCountAndTheObservedDeltaSeparately() {
        FakeClient client = new FakeClient();
        client.slots.set(0, "minecraft:stone");

        JsonObject out = result(run(fail("inv.toss", "{\"slot\":0,\"count\":1}"), client));

        assertEquals(1, client.tossCalls);
        assertEquals(1, out.get("requestedCount").getAsInt());
        assertEquals(1, out.get("observedDelta").getAsInt());
        assertEquals(false, out.get("partial").getAsBoolean());
        assertEquals("applied", out.get("verdict").getAsString());
        assertEquals(0, out.getAsJsonObject("after").get("count").getAsInt());
    }

    @Test
    void aTossThatObservedNothingIsNotReportedAsTheRequestedCount() {
        FakeClient client = new FakeClient();
        client.slots.set(0, "minecraft:stone");
        client.tossAppliesAtMost = 0;   // the authority refused it: the slot is unchanged

        JsonObject out = result(run(fail("inv.toss", "{\"slot\":0,\"count\":5}"), client));

        assertEquals(5, out.get("requestedCount").getAsInt(), "the request is recorded as requested");
        assertEquals(0, out.get("observedDelta").getAsInt(), "and the observation as observed");
        assertFalse(out.get("partial").getAsBoolean());
        // P9: an unchanged read-back is not proof the toss did nothing — it may just be early.
        assertNotEquals("skipped", out.get("verdict").getAsString(), out.toString());
        assertEquals("notClientVerifiable", out.get("verdict").getAsString());
        assertTrue(out.getAsJsonObject("notClientVerifiable").getAsJsonObject("slot 0")
                .has("observedAtReadback"), out.toString());
    }

    @Test
    void tossingFromAnEmptySlotIsAPreconditionAndCountIsBounded() {
        FakeClient client = new FakeClient();
        Protocol.Receipt empty = run(fail("inv.toss", "{\"slot\":1,\"count\":1}"), client);
        assertEquals("E_PRECONDITION", code(empty));
        assertTrue(only(empty).error().message().contains("empty"), only(empty).error().message());

        assertEquals("E_BAD_PARAMS", code(run(fail("inv.toss", "{\"slot\":0,\"count\":0}"), client)));
        assertEquals("E_BAD_PARAMS", code(run(fail("inv.toss", "{\"slot\":0,\"count\":4096}"), client)));
        assertEquals(0, client.tossCalls, "nothing may be tossed by a refused op");
    }

    @Test
    void aTossOnARemoteSessionOnlyClaimsItWasDispatched() {
        FakeClient client = new FakeClient();
        client.slots.set(0, "minecraft:stone");
        List<String> audit = new ArrayList<>();

        JsonObject out = result(run(fail("inv.toss", "{\"slot\":0,\"count\":1}"),
                ctx(client, Guard.SessionState.remote("127.0.0.1:25585"), audit, armed())));

        assertEquals("notClientVerifiable", out.get("verdict").getAsString());
        assertEquals(1, out.get("requestedCount").getAsInt());
        assertEquals(1, audit.size());
    }

    // ================================================================ use.item

    @Test
    void useItemClaimsDispatchAndClientStateOnlyNeverAnEffect() {
        FakeClient client = new FakeClient();
        JsonObject out = result(run(fail("use.item", null), client));

        assertEquals(1, client.useCalls);
        assertEquals(true, out.get("dispatched").getAsBoolean());
        assertEquals("main", out.get("hand").getAsString());
        assertEquals("dispatched", out.get("verdict").getAsString());
        // The state around the dispatch is reported as before/after, not as a result of the use.
        assertFalse(out.get("usingBefore").getAsBoolean());
        assertTrue(out.get("usingAfter").getAsBoolean());
        assertEquals("minecraft:stone", out.getAsJsonObject("heldBefore").get("id").getAsString());
        assertTrue(out.get("cooldownTicks").getAsInt() >= 0);
        assertTrue(out.get("note").getAsString().contains("does not claim that any effect occurred"),
                out.toString());
        // The effect of the use is explicitly *not* claimed by the client: `applied` carries the
        // dispatch and nothing else, and the outcome lives under notClientVerifiable.
        assertNotNull(out.getAsJsonObject("notClientVerifiable").getAsJsonObject("effect"), out.toString());
        assertEquals(1, out.getAsJsonObject("applied").size(), out.toString());
        assertTrue(out.getAsJsonObject("applied").has("dispatch"), out.toString());
    }

    @Test
    void useItemWithAnEmptyHandOrWhileAlreadyUsingIsRefused() {
        FakeClient emptyHand = new FakeClient();
        emptyHand.held = "";
        Protocol.Receipt r1 = run(fail("use.item", null), emptyHand);
        assertEquals("E_PRECONDITION", code(r1));
        assertTrue(only(r1).error().message().contains("no item"), only(r1).error().message());

        FakeClient busy = new FakeClient();
        busy.using = true;
        Protocol.Receipt r2 = run(fail("use.item", null), busy);
        assertEquals("E_PRECONDITION", code(r2));
        assertTrue(only(r2).error().message().contains("already using"), only(r2).error().message());
        assertEquals(0, busy.useCalls);
    }

    @Test
    void useItemRejectsAnUnknownHandAsABadParam() {
        FakeClient client = new FakeClient();
        Protocol.Receipt r = run(fail("use.item", "{\"hand\":\"third\"}"), client);
        assertEquals("E_BAD_PARAMS", code(r));
        assertEquals(0, client.useCalls);
    }

    @Test
    void useItemRefusesWhileTheHeldItemIsOnCooldown() {
        // The handoff lists "on cooldown" among the states this op assumes are over. Only a value the
        // client actually reports refuses, so an adapter that does not expose cooldowns is not blocked
        // by a check it cannot answer (the interface default is 0).
        FakeClient cooling = new FakeClient();
        cooling.cooldownTicks = 7;
        Protocol.Receipt r = run(fail("use.item", null), cooling);

        assertEquals("E_PRECONDITION", code(r));
        assertTrue(only(r).error().message().contains("cooldownTicks=7"), only(r).error().message());
        assertEquals(0, cooling.useCalls, "a cooling item must not be dispatched");

        FakeClient ready = new FakeClient();
        assertEquals("dispatched", result(run(fail("use.item", null), ready)).get("verdict").getAsString());
        assertEquals(1, ready.useCalls);
    }

    @Test
    void useItemRefusesWhenTheAdapterOnlyKnowsTheItemIsOnCooldown() {
        // An adapter may know "on cooldown" without knowing the remaining ticks (Minecraft exposes a
        // percentage), so the two signals are separate and either one refuses.
        FakeClient client = new FakeClient();
        client.onCooldown = true;
        Protocol.Receipt r = run(fail("use.item", null), client);

        assertEquals("E_PRECONDITION", code(r));
        assertTrue(only(r).error().message().contains("on cooldown"), only(r).error().message());
        assertEquals(0, client.useCalls);
    }

    @Test
    void useItemDispatchesFromTheRequestedHand() {
        // `hand:"off"` must reach the adapter: accepting the parameter and then using the main hand
        // would make the receipt describe a different op than the ticket asked for.
        FakeClient offHand = new FakeClient();
        offHand.offHand = "minecraft:shield";   // an empty hand cannot be used at all
        result(run(fail("use.item", "{\"hand\":\"off\"}"), offHand));
        assertEquals(List.of(true), offHand.useOffHand, "hand:off must be dispatched off-hand");

        FakeClient mainHand = new FakeClient();
        result(run(fail("use.item", "{\"hand\":\"main\"}"), mainHand));
        assertEquals(List.of(false), mainHand.useOffHand);
    }

    @Test
    void anAdapterThatRefusesTheUseIsReportedAsUnsupportedNotAsADispatch() {
        FakeClient client = new ScriptedAdapter() {
            @Override
            public void useItem() {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "not wired in forge yet");
            }

            // The hand-aware overload is what the op actually calls, so the stub must cover it too —
            // otherwise this test would exercise the working path and pass for the wrong reason.
            @Override
            public void useItem(boolean offHand) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "not wired in forge yet");
            }
        };
        Protocol.Receipt r = run(fail("use.item", null), client);
        assertEquals("E_UNSUPPORTED", code(r),
                "an unimplemented adapter is not the same thing as a successful dispatch");
        assertFalse(only(r).ok());
    }

    // ================================================================ shot.capture

    @Test
    void shotCaptureReportsByteFactsOnlyAndSaysTheImageIsNotInterpreted() {
        FakeClient client = new FakeClient();
        JsonObject out = result(run(fail("shot.capture", "{\"name\":\"probe\"}"), client));

        assertEquals(1, client.screenshots.size());
        assertEquals(FakeClient.SAMPLE_PNG.length, out.get("bytes").getAsInt());
        assertEquals("png", out.get("format").getAsString());
        assertEquals(2, out.get("width").getAsInt());
        assertEquals(3, out.get("height").getAsInt());
        assertEquals(FakeClient.sha256Hex(FakeClient.SAMPLE_PNG), out.get("sha256").getAsString());
        assertEquals(64, out.get("sha256").getAsString().length());
        assertEquals(4242L, out.get("tick").getAsLong());
        assertTrue(out.get("path").getAsString().endsWith(".png"), out.toString());
        assertEquals("bytes were captured; image content is NOT interpreted", out.get("note").getAsString());
        // Byte facts only: no field of the receipt may carry a judgement about the picture.
        for (String wording : List.of("looks", "scene", "appears", "rendersCorrectly", "verify", "visible")) {
            assertFalse(out.toString().contains(wording),
                    "a capture receipt must not describe the image (" + wording + "): " + out);
        }
        // …and what it *does* say about the image is that it did not look at it.
        assertTrue(out.toString().contains("NOT interpreted"), out.toString());
    }

    @Test
    void aCaptureThatTimedOutIsATimeoutNotAnEmptyImage() {
        FakeClient client = new ScriptedAdapter() {
            @Override
            public CapturedFrame capture(String name) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_TIMEOUT,
                        "no frame within 2000 ms");
            }
        };
        Protocol.Receipt r = run(fail("shot.capture", null), client);
        assertEquals("E_TIMEOUT", code(r));
        assertFalse(only(r).ok(), "a timeout must never be reported as a successful capture");
        assertTrue(only(r).error().message().contains("waited for a rendered frame"), only(r).error().message());
    }

    @Test
    void aCaptureWithNoFrameAvailableIsRefusedRatherThanReturningAnEmptyImage() {
        FakeClient client = new ScriptedAdapter() {
            @Override
            public CapturedFrame capture(String name) {
                return new CapturedFrame(new byte[0], "png", 0, 0, "", 0L, null);
            }
        };
        Protocol.Receipt r = run(fail("shot.capture", null), client);
        assertEquals("E_PRECONDITION", code(r));
        assertTrue(only(r).error().message().contains("no frame"), only(r).error().message());
    }

    @Test
    void anAdapterWithoutCaptureSupportIsReportedAsUnsupported() {
        Protocol.Receipt r = run(fail("shot.capture", null), new LegacyAdapter());
        assertEquals("E_UNSUPPORTED", code(r));
    }

    @Test
    void defaultShotNamesCannotCollideEvenInTheSameMillisecond() {
        // The default name used to be `shot-<clockMs>` and the test clock is fixed, so two captures in
        // one ticket produced the same name. The name now carries the op id and a per-JVM sequence.
        FakeClient client = new FakeClient();
        Protocol.Ticket ticket = Protocol.Ticket.fromJson(Json.parseObject(
                "{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"wired\",\"ops\":["
                        + "{\"id\":\"s1\",\"op\":\"shot.capture\"},"
                        + "{\"id\":\"s2\",\"op\":\"shot.capture\"}]}"), "wired");
        Protocol.Receipt r = new Executor.TicketExecutor(config(true), catalog())
                .execute(ticket, Executor.quietLog(), ctx(client, Guard.SessionState.singleplayer()));

        assertEquals(2, r.ops().size(), r.ops().toString());
        String first = r.ops().get(0).result().get("path").getAsString();
        String second = r.ops().get(1).result().get("path").getAsString();
        assertNotEquals(first, second, "two default captures in one ticket must not share a name");
        assertTrue(first.contains("shot-s1-"), first);
        assertTrue(second.contains("shot-s2-"), second);
    }

    // ================================================================ bench.read

    @Test
    void benchReadCarriesItsWindowUnitsAndSampleCount() {
        FakeClient client = new FakeClient();
        JsonObject out = result(run(fail("bench.read", "{\"warmup_frames\":30,\"sample_frames\":200}"), client));

        assertEquals(30, out.get("warmupFrames").getAsInt());
        assertEquals(200, out.get("sampleFrames").getAsInt());
        assertEquals(120, out.get("sampleCount").getAsInt());
        assertEquals(120, out.get("actualSampleCount").getAsInt());
        assertTrue(out.get("windowMs").getAsLong() > 0, out.toString());
        assertTrue(out.get("fpsMedian").getAsDouble() > 0);
        assertTrue(out.get("frameMsP95").getAsDouble() > 0);
        assertTrue(out.get("onePercentLow").getAsDouble() > 0);
        assertNotNull(out.getAsJsonObject("units").get("fps"), "a bare fps number is not interpretable");
        assertNotNull(out.getAsJsonObject("units").get("ms"));
        assertTrue(out.get("samplesPath").getAsString().endsWith(".csv"), out.toString());
        // The limits of the measurement are stated, not implied.
        String note = out.get("note").getAsString();
        assertTrue(note.contains("client-side frame durations"), note);
        assertTrue(note.contains("no GPU/vendor counters"), note);
        assertTrue(note.contains("not directly comparable"), note);
        assertTrue(note.contains("120 of the requested 200"), note);
    }

    @Test
    void benchReadBoundsItsSamplingWindow() {
        FakeClient client = new FakeClient();
        assertEquals("E_BAD_PARAMS", code(run(fail("bench.read", "{\"sample_frames\":601}"), client)));
        assertEquals("E_BAD_PARAMS", code(run(fail("bench.read", "{\"warmup_frames\":400,\"sample_frames\":600}"),
                client)));
        assertEquals("E_BAD_PARAMS", code(run(fail("bench.read", "{\"warmup_frames\":-1}"), client)));
        assertEquals(0, client.waitedFrames);
    }

    @Test
    void benchReadNamesAnAdapterThatOverranTheRequestedWindow() {
        FakeClient overran = new ScriptedAdapter() {
            @Override
            public BenchResult benchWindow(int warmupFrames, int sampleFrames) {
                return new BenchResult(warmupFrames, sampleFrames, sampleFrames + 50, 2_000L,
                        144.5, 8.25, 96.0, "bench/overran.csv");
            }
        };
        JsonObject out = result(run(fail("bench.read", "{\"sample_frames\":100}"), overran));

        assertEquals(100, out.get("sampleFrames").getAsInt(), "the request stays the request");
        assertEquals(150, out.get("sampleCount").getAsInt(), "the measurement stays the measurement");
        assertEquals(150, out.get("actualSampleCount").getAsInt());
        assertTrue(out.get("overranWindow").getAsBoolean(), out.toString());
        assertTrue(out.get("note").getAsString().contains("overran"), out.toString());

        JsonObject normal = result(run(fail("bench.read", "{\"sample_frames\":100}"), new FakeClient()));
        assertFalse(normal.get("overranWindow").getAsBoolean(), normal.toString());
    }

    @Test
    void benchReadWithNoFramesIsAPreconditionAndAnIncompleteRunIsATimeout() {
        FakeClient minimized = new ScriptedAdapter() {
            @Override
            public BenchResult benchWindow(int warmupFrames, int sampleFrames) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                        "client is minimised; no frames are being rendered");
            }
        };
        assertEquals("E_PRECONDITION", code(run(fail("bench.read", null), minimized)));

        FakeClient stalled = new ScriptedAdapter() {
            @Override
            public BenchResult benchWindow(int warmupFrames, int sampleFrames) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_TIMEOUT,
                        "sampling did not finish within the budget");
            }
        };
        Protocol.Receipt r = run(fail("bench.read", null), stalled);
        assertEquals("E_TIMEOUT", code(r));
        assertFalse(only(r).ok(), "an incomplete sample is a failure, never a success");
        assertTrue(only(r).error().message().contains("did not complete"), only(r).error().message());

        FakeClient empty = new ScriptedAdapter() {
            @Override
            public BenchResult benchWindow(int warmupFrames, int sampleFrames) {
                return new BenchResult(warmupFrames, sampleFrames, 0, 0L, 0, 0, 0, null);
            }
        };
        assertEquals("E_PRECONDITION", code(run(fail("bench.read", null), empty)));
    }

    // ================================================================ the guard

    @Test
    void everyWriteOpIsRefusedWhenTheGuardIsNotWiredAndNothingIsTouched() {
        // The honest half of "core only this round": an executor whose adapter never built a guard
        // must refuse, not write. (Forged wiring lands next round; until then the five ops fail
        // closed instead of silently skipping the policy.)
        for (String op : WRITES) {
            FakeClient client = new FakeClient();
            client.slots.set(0, "minecraft:stone");
            // Parameters are valid on purpose: the refusal under test must come from the guard, not
            // from a parameter check that happens to run first.
            String params = switch (op) {
                case "inv.click" -> "{\"slot\":0}";
                case "inv.toss" -> "{\"slot\":0,\"count\":1}";
                default -> "{\"hand\":\"main\"}";
            };
            Protocol.Receipt r = run(fail(op, params), unwiredGuard(client, Guard.SessionState.singleplayer()));

            assertEquals("E_PRECONDITION", code(r), op + " must fail closed without a guard; got "
                    + (only(r).error() == null ? only(r).result() : only(r).error().code() + ": "
                            + only(r).error().message()));
            assertTrue(only(r).error().message().contains("not wired"), only(r).error().message());
            assertEquals(0, client.clickCalls + client.tossCalls + client.useCalls,
                    op + " must not reach the client when the guard is missing");
        }
    }

    @Test
    void theSameOpIsAllowedOnceTheGuardIsWiredProvingTheGuardIsTheOnlyDifference() {
        // A companion to the test above: same client, same single-player session, same params — the
        // only change is the presence of the guard. If this passes while the previous one refuses,
        // the test is measuring the guard and not something else in the fixture.
        FakeClient client = new FakeClient();
        client.slots.set(0, "minecraft:stone");
        JsonObject out = result(run(fail("inv.toss", "{\"slot\":0,\"count\":1}"),
                ctx(client, Guard.SessionState.singleplayer())));
        assertEquals("applied", out.get("verdict").getAsString());
        assertEquals(1, client.tossCalls);
    }

    @Test
    void anAllowedWriteLeavesExactlyOneAuditLineWithTheFingerprintAndNeverTheToken() {
        FakeClient client = new FakeClient();
        client.slots.set(0, "minecraft:stone");
        List<String> audit = new ArrayList<>();
        Guard.MutationGuard guard = mutationGuard(audit, Guard.SessionState.singleplayer(), armed(),
                "ALLOWED-MUTATION");

        Protocol.Ticket ticket = Protocol.Ticket.fromJson(Json.parseObject(
                "{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"wired\",\"ops\":["
                        + "{\"id\":\"t1\",\"op\":\"inv.toss\",\"params\":{\"slot\":0,\"count\":1}}]}"), "wired");
        new Executor.TicketExecutor(config(true), catalog()).execute(ticket, Executor.quietLog(),
                new Executor.ExecContext(config(true), Guard.SessionState.singleplayer(), armed(), CLOCK,
                        Map.of("allow-mutate", true), client, guard));

        assertEquals(1, audit.size(), "one allowance, exactly one audit line: " + audit);
        // The guard's own view and the sink's view are the same single line, under the write-op
        // prefix — not the input prefix and not two lines for one allowance.
        assertEquals(1, guard.lines().size(), "the guard must count the same one allowance: " + guard.lines());
        assertEquals(audit.get(0), guard.lines().get(0));
        String line = audit.get(0);
        assertTrue(line.startsWith("ALLOWED-MUTATION"), line);
        assertTrue(line.contains("executor=wired-test"), "who acted: " + line);
        assertTrue(line.contains("host=singleplayer"), "which host: " + line);
        assertTrue(line.contains("at=" + NOW), "when: " + line);
        assertTrue(line.contains("op=inv.toss"), "which op: " + line);
        assertTrue(line.contains("cmd=[toss slot=0 count=1]"), "what was written: " + line);
        String fingerprint = new Guard.ActivationToken(TOKEN_VALUE, 0L).fingerprint();
        assertEquals(8, fingerprint.length(), "the audit line names the token by an 8-char fingerprint");
        assertTrue(line.contains("token=" + fingerprint), line);
        assertFalse(line.contains(TOKEN_VALUE), "the token VALUE must never be logged: " + line);
        // Zero occurrences of the token value anywhere in the emitted lines, not just "not equal".
        int occurrences = 0;
        for (String l : audit) {
            int idx = 0;
            while ((idx = l.indexOf(TOKEN_VALUE, idx)) >= 0) {
                occurrences++;
                idx += TOKEN_VALUE.length();
            }
        }
        assertEquals(0, occurrences, "0 occurrences of the token value are allowed: " + audit);
        assertEquals(1, guard.allowances().size());
        assertEquals("inv.toss", guard.allowances().get(0).op());
    }

    @Test
    void aRefusedWriteLeavesNoTraceAtAllInTheAuditLog() {
        FakeClient client = new FakeClient();
        client.slots.set(0, "minecraft:stone");
        List<String> audit = new ArrayList<>();
        // Undeclared host: the policy refuses, and a refusal is not an allowance.
        Guard.MutationGuard guard = mutationGuard(audit, Guard.SessionState.remote("10.0.0.7:25585"), armed(),
                "ALLOWED-MUTATION");

        Protocol.Ticket ticket = Protocol.Ticket.fromJson(Json.parseObject(
                "{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"wired\",\"ops\":["
                        + "{\"id\":\"t1\",\"op\":\"inv.toss\",\"params\":{\"slot\":0,\"count\":1}}]}"), "wired");
        Protocol.Receipt receipt = new Executor.TicketExecutor(config(true), catalog()).execute(ticket,
                Executor.quietLog(),
                new Executor.ExecContext(config(true), Guard.SessionState.remote("10.0.0.7:25585"), armed(),
                        CLOCK, Map.of("allow-mutate", true), client, guard));

        assertEquals("E_PRECONDITION", receipt.ops().get(0).error().code());
        assertTrue(receipt.ops().get(0).error().message().contains("not whitelisted"),
                receipt.ops().get(0).error().message());
        assertEquals(0, audit.size(), "a refusal must not leave an audit line: " + audit);
        assertTrue(guard.allowances().isEmpty());
        assertEquals(0, client.tossCalls, "a refusal means the op did not execute");
    }

    @Test
    void eachWriteOpIsAuditedUnderItsOwnOpName() {
        List<String> audit = new ArrayList<>();
        for (String op : WRITES) {
            FakeClient client = new FakeClient();
            client.slots.set(0, "minecraft:stone");
            String params = switch (op) {
                case "inv.click" -> "{\"slot\":0}";
                case "inv.toss" -> "{\"slot\":0,\"count\":1}";
                default -> null;
            };
            result(run(fail(op, params), ctx(client, Guard.SessionState.singleplayer(), audit, armed())));
        }
        assertEquals(WRITES.size(), audit.size(), "one line per allowance: " + audit);
        for (String op : WRITES) {
            assertTrue(audit.stream().anyMatch(l -> l.contains("op=" + op)),
                    "no audit line names " + op + ": " + audit);
        }
    }

    @Test
    void aClientPausedBetweenCheckAndDispatchIsReportedAsNotDispatchedNotAsApplied() {
        FakeClient client = new FakeClient();
        List<String> audit = new ArrayList<>();
        // The policy's no-op branch: the client owns the decision "nothing to do right now".
        JsonObject out = result(run(fail("use.item", null),
                ctx(client, Guard.SessionState.of(true, false, true, true, false, null), audit, armed())));

        assertEquals(false, out.get("dispatched").getAsBoolean());
        assertEquals("notDispatched", out.get("verdict").getAsString());
        assertEquals(0, client.useCalls, "a no-op must not reach the client");
        assertTrue(out.getAsJsonObject("skipped").getAsJsonObject("dispatch")
                .get("reason").getAsString().contains("paused"), out.toString());
        assertEquals(0, audit.size(),
                "no write happened, so there is no allowance to log — read ops and no-ops stay silent");
    }

    /**
     * Regression guard for the bypass found in review: {@code inv.click}/{@code inv.toss} ignored the
     * guard's <b>no-op</b> decision and dispatched anyway.
     *
     * <p>The guard's no-op branch means "allowed, but nothing is going to be written" (paused /
     * handshake / not in a world). A write performed on that path is never turned into an allowance, so
     * it produces <b>no audit line</b>: the client is written to and nothing records it. The receipt
     * also used to say {@code applied}, contradicting the policy that had just said there was nothing
     * to do. Both are asserted here, for both writing ops, on both no-op session shapes.
     */
    @Test
    void aGuardNoOpOnClickOrTossWritesNothingAndLeavesNoAuditLine() {
        List<Guard.SessionState> noOpSessions = List.of(
                Guard.SessionState.of(true, false, true, true, false, null),    // paused
                Guard.SessionState.of(true, false, false, false, false, null)); // not in a world

        for (Guard.SessionState session : noOpSessions) {
            for (String op : List.of("inv.click", "inv.toss")) {
                FakeClient client = new FakeClient();
                client.windowId = 0;
                client.slots.set(0, "minecraft:stone");
                List<String> audit = new ArrayList<>();
                String params = "inv.click".equals(op) ? "{\"slot\":0}" : "{\"slot\":0,\"count\":1}";

                JsonObject out = result(run(fail(op, params), ctx(client, session, audit, armed())));

                assertEquals("skipped", out.get("verdict").getAsString(),
                        op + " must not claim applied when the guard said no-op: " + out);
                assertEquals(0, out.getAsJsonObject("applied").size(), out.toString());
                assertTrue(out.getAsJsonObject("skipped").getAsJsonObject("dispatch")
                        .get("reason").getAsString().contains("guard no-op"), out.toString());
                assertEquals(0, client.clickCalls + client.tossCalls,
                        op + " must not reach the client on a no-op path");
                assertTrue(audit.isEmpty(), op + " wrote nothing, so it is not an allowance: " + audit);

                // Control: with no guard wired at all the same op is *refused* (not a no-op) and equally
                // touches nothing — so the no-op path is not a hole in fail-closed.
                FakeClient unwiredClient = new FakeClient();
                unwiredClient.windowId = 0;
                unwiredClient.slots.set(0, "minecraft:stone");
                Protocol.Receipt refused = run(fail(op, params), unwiredGuard(unwiredClient, session));
                assertEquals("E_PRECONDITION", code(refused), op);
                assertTrue(only(refused).error().message().contains("not wired"),
                        only(refused).error().message());
                assertEquals(0, unwiredClient.clickCalls + unwiredClient.tossCalls,
                        op + " must not reach the client without a guard either");
            }
        }
    }

    // ================================================================ tier-1 reads

    @Test
    void theTwoReadOpsAreNeverGatedByTheInjectionPolicy() {
        for (String op : READS) {
            List<String> audit = new ArrayList<>();
            FakeClient client = new FakeClient();
            // Unarmed, an undeclared host, no allow-mutate flag: everything the policy could refuse on.
            Bridge.BridgeConfig lockedDown = new Bridge.BridgeConfig(Path.of("."), 500L, "wired-test",
                    "0.1.0", false, Bridge.BusyPolicy.ANSWER_BUSY, 64, Guard.HostWhitelist.empty());
            Executor.ExecContext ctx = new Executor.ExecContext(lockedDown,
                    Guard.SessionState.remote("someone-elses-server.example"), Guard.ActivationState.off(),
                    CLOCK, Map.of(), client,
                    mutationGuard(audit, Guard.SessionState.remote("someone-elses-server.example"),
                            Guard.ActivationState.off(), "ALLOWED-MUTATION"));

            Protocol.Receipt receipt = run(fail(op, null), ctx);
            assertTrue(only(receipt).ok(), op + " is a tier-1 read and must not be gated: "
                    + only(receipt).error());
            assertTrue(audit.isEmpty(), "a read is not an allowance: " + audit);
        }
    }

    @Test
    void theReadersAreTheOnlyOnesWithANonMutatingSideEffect() {
        Executor.OpCatalog catalog = catalog();
        for (String op : READS) {
            Protocol.OpSpec spec = catalog.lookup(op);
            assertNotNull(spec, op + " must be registered");
            assertEquals(List.of(Protocol.SideEffect.TELEMETRY_RECORDING), spec.sideEffects(), op);
            assertFalse(spec.mutating(), op + " must not be mutating");
        }
        assertEquals(List.of(Protocol.SideEffect.PLAYER_INVENTORY), catalog.lookup("inv.click").sideEffects());
        assertEquals(List.of(Protocol.SideEffect.PLAYER_INVENTORY), catalog.lookup("inv.toss").sideEffects());
        assertEquals(List.of(Protocol.SideEffect.PLAYER_STATE, Protocol.SideEffect.PLAYER_INVENTORY),
                catalog.lookup("use.item").sideEffects(),
                "using an item changes the player state and may consume the item: both are declared");
        // No new wire vocabulary was invented for this batch.
        assertEquals(9, Protocol.SideEffect.values().length,
                "the side-effect vocabulary must not grow: " + List.of(Protocol.SideEffect.values()));
        // Updated in task-78: eleven codes plus the four ADDITIVE non-failure terminations
        // (E_SUPERSEDED/E_STOPPED/E_NO_PATH/E_STUCK). This freeze still holds — the pre-existing eleven
        // keep their exact meaning, no error field was added, and every new code is classified by
        // ErrorCode.nonFailureTermination() — so it is widened deliberately, not removed.
        assertEquals(15, Protocol.ErrorCode.values().length,
                "codes may only grow by the documented non-failure terminations: "
                        + List.of(Protocol.ErrorCode.values()));
        assertEquals(4, List.of(Protocol.ErrorCode.values()).stream()
                .filter(Protocol.ErrorCode::nonFailureTermination).count(),
                "exactly four codes are non-failure terminations");
    }

    // ================================================================ the anti-regression check

    /**
     * The one test that must fail if any of the five ops goes back to being "not implemented".
     *
     * <p>"Not supported" used to be the permanent answer for these five, which hid the fact that core
     * had no implementation at all. The assertion is deliberately stronger than "the op is not
     * refused": each op must come back with a field that only exists because the implementation ran,
     * so a future adapter stub (or a deleted handler) cannot satisfy it.
     */
    @Test
    void noneOfTheFiveOpsReturnsEUnsupportedFromCoreAnyMore() {
        // Writes: the guard is the only refusal left, and it must never be the old stub.
        FakeClient client = new FakeClient();
        client.slots.set(0, "minecraft:stone");
        Protocol.Receipt click = run(fail("inv.click", "{\"slot\":0}"), client);
        assertNoUnsupported("inv.click", click);
        assertTrue(click.ops().get(0).result().has("before"), "inv.click: " + click.ops().get(0));
        assertTrue(click.ops().get(0).result().has("verdict"), "inv.click: " + click.ops().get(0));

        FakeClient tossClient = new FakeClient();
        tossClient.slots.set(0, "minecraft:stone");
        Protocol.Receipt toss = run(fail("inv.toss", "{\"slot\":0,\"count\":1}"), tossClient);
        assertNoUnsupported("inv.toss", toss);
        assertTrue(toss.ops().get(0).result().has("requestedCount"), "inv.toss: " + toss.ops().get(0));
        assertTrue(toss.ops().get(0).result().has("observedDelta"), "inv.toss: " + toss.ops().get(0));

        Protocol.Receipt use = run(fail("use.item", null), new FakeClient());
        assertNoUnsupported("use.item", use);
        assertTrue(use.ops().get(0).result().get("dispatched").getAsBoolean(), "use.item: " + use.ops().get(0));

        Protocol.Receipt shot = run(fail("shot.capture", null), new FakeClient());
        assertNoUnsupported("shot.capture", shot);
        assertTrue(shot.ops().get(0).result().has("sha256"), "shot.capture: " + shot.ops().get(0));

        Protocol.Receipt bench = run(fail("bench.read", null), new FakeClient());
        assertNoUnsupported("bench.read", bench);
        assertTrue(bench.ops().get(0).result().has("sampleCount"), "bench.read: " + bench.ops().get(0));

        // The ops must also name the stub for what it is when an adapter has not been wired: it stays
        // a structured failure, so "implemented in core, not yet reached in game" is visible instead
        // of silent. This is the check the Forge round has to satisfy by removing the stub.
        for (String op : List.of("inv.click", "inv.toss", "use.item", "shot.capture", "bench.read")) {
            Protocol.Receipt r = run(fail(op, null), new LegacyAdapter());
            Protocol.Receipt.Error e = r.ops().get(0).error();
            assertNotNull(e, op + " against an adapter without the op must fail structurally");
            assertFalse(r.ops().get(0).ok(), op + " must not report success from a stub adapter");
            assertTrue(e.message() != null && !e.message().isBlank(),
                    op + ": the refusal must carry an explanation");
        }
    }

    /** Asserts the current implementation no longer answers with the removed stub code. */
    private static void assertNoUnsupported(String op, Protocol.Receipt receipt) {
        Protocol.Receipt.OpResult r = receipt.ops().get(0);
        String actual = r.error() == null ? "<no error>" : r.error().code();
        assertFalse("E_UNSUPPORTED".equals(actual),
                op + " must no longer answer E_UNSUPPORTED (core implements it since task-70), got " + actual);
        assertFalse("E_UNKNOWN_OP".equals(actual), op + " must be a known op, got " + actual);
        assertTrue(r.ok(), op + " must succeed on a working client, got " + r.error());
    }

    /** A {@link FakeClient} a test can subclass to script one adapter behaviour. */
    private static class ScriptedAdapter extends FakeClient {
    }

    /** The minimal adapter: only what task-70 did not add. It models "the game side is not wired". */
    private static class LegacyAdapter implements ClientModel {
        @Override
        public double x() {
            return 0;
        }

        @Override
        public double y() {
            return 64;
        }

        @Override
        public double z() {
            return 0;
        }

        @Override
        public float yaw() {
            return 0;
        }

        @Override
        public float pitch() {
            return 0;
        }

        @Override
        public String dimension() {
            return "minecraft:overworld";
        }

        @Override
        public String heldItemId() {
            return "minecraft:stone";
        }

        @Override
        public void teleport(double x, double y, double z, float yaw, float pitch, int settleMs) {
        }

        @Override
        public boolean settled() {
            return true;
        }

        @Override
        public List<String> inventory() {
            return List.of("minecraft:stone");
        }

        @Override
        public boolean usingItem() {
            return false;
        }

        @Override
        public void releaseUsingItem() {
        }

        @Override
        public void selectSlot(int slot) {
        }

        @Override
        public void clickSlot(int slot, int button, String mode) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "not wired yet");
        }

        @Override
        public void tossSlot(int slot, int count) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "not wired yet");
        }

        @Override
        public void useItem() {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "not wired yet");
        }

        @Override
        public boolean cellOccupied(int x, int y, int z) {
            return false;
        }

        @Override
        public void useItemOnBlock(int x, int y, int z, String block) {
        }

        @Override
        public String captureScreenshot(String name) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "not wired yet");
        }

        @Override
        public BenchSample bench(int warmupFrames, int sampleFrames) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "not wired yet");
        }

        // capture() and benchWindow() are deliberately NOT overridden: the interface defaults refuse,
        // which is exactly the adapter state this test wants to model.
        @Override
        public void waitFrames(int frames) {
        }
    }

    // ================================================================ wire contract

    @Test
    void everyWiredOpIsRefusedWithAPreconditionWhenTheAllowMutateFlagIsMissing() {
        for (String op : WRITES) {
            FakeClient client = new FakeClient();
            client.slots.set(0, "minecraft:stone");
            Bridge.BridgeConfig noMutate = config(false);
            List<String> audit = new ArrayList<>();
            Executor.ExecContext ctx = new Executor.ExecContext(noMutate, Guard.SessionState.singleplayer(),
                    armed(), CLOCK, Map.of(), client,
                    mutationGuard(audit, Guard.SessionState.singleplayer(), armed(), "ALLOWED-MUTATION"));
            String params = switch (op) {
                case "inv.click" -> "{\"slot\":0}";
                case "inv.toss" -> "{\"slot\":0,\"count\":1}";
                default -> null;
            };
            Protocol.Receipt r = run(fail(op, params), ctx);
            assertEquals("E_PRECONDITION", code(r), op);
            assertTrue(only(r).error().message().contains("allow-mutate"), only(r).error().message());
            assertEquals(0, client.clickCalls + client.tossCalls + client.useCalls);
            assertTrue(audit.isEmpty(), op + " must not be audited when it never ran");
        }
    }

    @Test
    void theCatalogPublishesTheResultShapeEachOpActuallyProduces() {
        Executor.OpCatalog catalog = catalog();
        for (String op : List.of("inv.click", "inv.toss", "use.item", "shot.capture", "bench.read")) {
            Protocol.OpSpec spec = catalog.lookup(op);
            assertNotNull(spec, op);
            assertNotNull(spec.resultSchema(), op + " must publish a result schema");
            assertEquals("object", spec.resultSchema().get("type").getAsString(), op);
            assertTrue(spec.resultSchema().getAsJsonObject("properties").size() > 0, op);
            assertNotNull(spec.paramsSchema(), op);
            assertEquals("object", spec.paramsSchema().get("type").getAsString(), op);
        }
        JsonObject clickParams = catalog.lookup("inv.click").paramsSchema().getAsJsonObject("properties");
        assertTrue(clickParams.has("slot") && clickParams.has("button") && clickParams.has("mode"),
                clickParams.toString());
        assertTrue(clickParams.getAsJsonObject("mode").getAsJsonArray("enum").size() > 0,
                "the mode vocabulary must be published: " + clickParams);
        // Every parameter of these five ops is optional (absent means "the documented default"), so
        // the schemas must not require any of them — a required list would turn a default into an error.
        assertFalse(catalog.lookup("inv.click").paramsSchema().has("required"), "params are optional");
        assertFalse(catalog.lookup("bench.read").paramsSchema().has("required"), "params are optional");
    }

    @Test
    void everyWiredOpIsRegisteredWithItsHandlerSoADeletedHandlerCannotHide() {
        Executor.OpCatalog catalog = catalog();
        for (String op : List.of("inv.click", "inv.toss", "use.item", "shot.capture", "bench.read")) {
            assertNotNull(catalog.handler(op), op + " has no handler registered");
            assertTrue(VanillaOps.WIRED_IN_CORE.contains(op), op + " must be listed as wired in core");
            assertNotNull(VanillaOps.installedSpec(op), op + " does not publish a spec to the guard");
        }
    }

    @Test
    void aMissingRequiredParamIsABadParamRatherThanARuntimeFailure() {
        FakeClient client = new FakeClient();
        Protocol.Receipt r = run(fail("inv.toss", "{}"), client);
        assertEquals("E_BAD_PARAMS", code(r), "a missing slot must be a parameter error: " + only(r).error());
        assertEquals("E_BAD_PARAMS", code(run(fail("inv.click", "{}"), client)));

        FakeClient full = new FakeClient(41);   // slot 36 is armor; 20 is a container slot
        assertEquals("E_BAD_PARAMS", code(run(fail("inv.click", "{\"slot\":99}"), full)));
    }

    @Test
    void theGuardRefusesWhenItsSinkIsMissingInsteadOfLoggingNowhere() {
        assertThrows(IllegalArgumentException.class, () -> new Guard.MutationGuard(
                new Guard.InputInjectionPolicy(Guard.HostWhitelist.empty(), Guard.BuildVariant.GUARDED,
                        "x", () -> "d"),
                Guard.SessionState.singleplayer(), armed(), CLOCK, null));
    }

    // ================================================================ P9: one early read is not a fact

    /** P9: the click is dispatched, but this client's menu shows the change only on a later read. */
    private static final class DelayedSyncClient extends FakeClient {
        private List<String> stale;

        @Override
        public void clickSlot(int slot, int button, String mode) {
            clickCalls++;
            stale = List.copyOf(slots);   // what the menu still shows immediately after the click
            slots.set(slot, "");          // the authority's answer lands before the next read
        }

        @Override
        public List<String> inventory() {
            if (stale != null) {
                List<String> s = stale;
                stale = null;
                return s;
            }
            return super.inventory();
        }
    }

    /** P9, second shape: the container has not caught up, so "empty" cannot be determined. */
    private static final class NotYetSyncedClient extends FakeClient {
        NotYetSyncedClient() {
        }

        NotYetSyncedClient(int slotCount) {
            super(slotCount);
        }

        @Override
        public long containerSyncAgeTicks() {
            return 0L;   // the newest possible reason to distrust the view: inside the window
        }
    }

    /** P10: an adapter with no block query at all — what qa-tester measured on the bridge side. */
    private static final class NoBlockQueryClient extends FakeClient {
        @Override
        public String blockIdAt(int x, int y, int z) {
            return null;
        }
    }

    @Test
    void p9AReadBackThatHasNotCaughtUpIsNotSkippedAndALaterReadProvesTheEffect() {
        DelayedSyncClient client = new DelayedSyncClient();
        client.windowId = 0;
        client.slots.set(0, "minecraft:stone");

        JsonObject click = result(run(fail("inv.click", "{\"slot\":0,\"mode\":\"pickup\"}"), client));

        assertNotEquals("skipped", click.get("verdict").getAsString(),
                "the old code derived `skipped` from this early read: " + click);
        assertEquals("notClientVerifiable", click.get("verdict").getAsString());
        assertEquals(1, client.clickCalls, "the click really was dispatched");
        // The proof that `skipped` was the wrong answer: the effect is visible one read later.
        assertEquals("", client.inventory().get(0),
                "a later read shows the slot emptied — the first read was simply early");
    }

    @Test
    void p9AnEmptyReadWhileTheContainerIsStillSyncingIsNotReportedAsEmptiness() {
        Protocol.Receipt r = run(fail("inv.toss", "{\"slot\":1,\"count\":1}"), new NotYetSyncedClient());

        assertEquals("E_PRECONDITION", code(r));
        String message = only(r).error().message();
        assertTrue(message.contains("cannot determine"), message);
        assertTrue(message.contains("caught up"), message);
        // The factual phrasing of the settled refusal must not appear in the unsettled one.
        assertFalse(message.contains("is empty: nothing to toss"),
                "the core must not turn an unsettled read into a fact: " + message);
        assertEquals("container-not-synced", only(r).error().detail().get("reason").getAsString());

        // Control: once the adapter vouches for its view, the same absent lot IS a factual refusal.
        Protocol.Receipt settled = run(fail("inv.toss", "{\"slot\":1,\"count\":1}"), new FakeClient());
        assertEquals("E_PRECONDITION", code(settled));
        assertTrue(only(settled).error().message().contains("is empty"),
                only(settled).error().message());
        assertEquals("slot-empty", only(settled).error().detail().get("reason").getAsString());
    }

    @Test
    void p9AnUnsyncedContainerIsNotReportedAsAbsent() {
        Protocol.Receipt r = run(fail("inv.click", "{\"slot\":20,\"mode\":\"pickup\"}"),
                new NotYetSyncedClient(41));

        assertEquals("E_PRECONDITION", code(r));
        assertTrue(only(r).error().message().contains("cannot determine"), only(r).error().message());
        assertEquals("container-not-synced", only(r).error().detail().get("reason").getAsString());
    }

    @Test
    void p9AnUnsyncedInventoryIsNotReportedAsAnEmptyHand() {
        NotYetSyncedClient client = new NotYetSyncedClient();
        client.held = "";   // the read says "no item", but the inventory may not have caught up

        Protocol.Receipt r = run(fail("use.item", null), client);

        assertEquals("E_PRECONDITION", code(r));
        assertTrue(only(r).error().message().contains("cannot determine"), only(r).error().message());
        assertEquals("container-not-synced", only(r).error().detail().get("reason").getAsString());
        assertEquals(0, client.useCalls, "nothing may be used when the state cannot be determined");
    }

    // ================================================================ P10: self-report needs a witness

    @Test
    void p10APlacementTheClientCannotWitnessIsNotReportedAsApplied() {
        NoBlockQueryClient client = new NoBlockQueryClient();
        client.held = "minecraft:oak_planks";   // P11: the block has to be in hand
        List<String> audit = new ArrayList<>();

        JsonObject out = result(run(fail("world.place",
                "{\"x\":1,\"y\":64,\"z\":2,\"block\":\"minecraft:oak_planks\"}"),
                ctx(client, Guard.SessionState.singleplayer(), audit, armed())));

        assertEquals(1, client.placed.size(), "the placement was dispatched");
        assertEquals(1, client.useItemOnCalls, "and through the interaction entry");
        // The old code wrote `placed:true, verdict:"applied"` unconditionally — a self-report with no
        // witness at all. Nothing here may claim the block is there.
        assertEquals(false, out.get("placed").getAsBoolean(),
                "placed must be the read-back, not the request: " + out);
        assertTrue(out.get("blockObserved").isJsonNull(), out.toString());
        assertNotEquals("applied", out.get("verdict").getAsString(), out.toString());
        assertEquals("notClientVerifiable", out.get("verdict").getAsString());
        assertEquals(0, out.getAsJsonObject("applied").size(), out.toString());
        assertTrue(out.getAsJsonObject("notClientVerifiable").getAsJsonObject("effect")
                .get("reason").getAsString().contains("not observable"), out.toString());
        assertEquals(1, audit.size(), "the guard allowed it, so its allowance line exists");
    }

    @Test
    void p10APlacementTheClientCanReadBackReportsTheBlockItObserved() {
        FakeClient client = new FakeClient();
        client.held = "minecraft:oak_planks";   // P11: the block has to be in hand

        JsonObject out = result(run(fail("world.place",
                "{\"x\":1,\"y\":64,\"z\":2,\"block\":\"minecraft:oak_planks\"}"), client));

        assertEquals(true, out.get("placed").getAsBoolean(), out.toString());
        assertEquals("minecraft:oak_planks", out.get("blockObserved").getAsString(), out.toString());
        assertEquals("notClientVerifiable", out.get("verdict").getAsString(),
                "the client's own view is not the authority's decision: " + out);
        assertEquals(0, out.getAsJsonObject("applied").size(), out.toString());
    }

    // ================================================================ P11: like a player, or not at all

    @Test
    void p11WorldPlaceGoesThroughThePlayerInteractionEntryAndNeverWritesTheWorldDirectly() {
        FakeClient client = new FakeClient();
        client.held = "minecraft:oak_planks";
        List<String> audit = new ArrayList<>();

        JsonObject out = result(run(fail("world.place",
                "{\"x\":1,\"y\":64,\"z\":2,\"block\":\"minecraft:oak_planks\"}"),
                ctx(client, Guard.SessionState.singleplayer(), audit, armed())));

        assertEquals(1, client.useItemOnCalls,
                "world.place must go through the interaction entry, like a right-click: " + out);
        assertEquals(0, client.directPlaceCalls,
                "P11: it must NOT write the world directly — that is why no KubeJS BlockEvents.placed "
                        + "fired on a real client: " + out);
        assertEquals(1, audit.size(), "exactly one allowance line: " + audit);
        assertTrue(audit.get(0).startsWith("ALLOWED-MUTATION"), audit.get(0));
        assertEquals("8", String.valueOf(audit.get(0).split("token=")[1].split("\\s")[0].length()),
                "the fingerprint stays 8 characters: " + audit.get(0));
        assertEquals(0, out.getAsJsonObject("skipped").size(), out.toString());
    }

    @Test
    void p11WorldPlaceWithoutTheBlockInHandIsRefusedHonestlyInsteadOfConjuringIt() {
        FakeClient client = new FakeClient();
        client.held = "";   // nothing in the selected slot

        Protocol.Receipt r = run(fail("world.place",
                "{\"x\":1,\"y\":64,\"z\":2,\"block\":\"minecraft:oak_planks\"}"), client);

        assertEquals("E_PRECONDITION", code(r));
        assertTrue(only(r).error().message().contains("in hand"), only(r).error().message());
        assertEquals("empty-hand", only(r).error().detail().get("reason").getAsString());
        assertEquals(0, client.useItemOnCalls, "nothing may be placed");
        assertEquals(0, client.directPlaceCalls, "and certainly not conjured");
        assertTrue(client.placed.isEmpty(), "the world must be untouched: " + client.placed);
    }

    @Test
    void p11WorldPlaceWithADifferentItemInHandIsRefusedByTheInteractionPath() {
        FakeClient client = new FakeClient();
        client.held = "minecraft:stone";   // the ticket asks for oak_planks

        Protocol.Receipt r = run(fail("world.place",
                "{\"x\":1,\"y\":64,\"z\":2,\"block\":\"minecraft:oak_planks\"}"), client);

        assertEquals("E_PRECONDITION", code(r));
        assertTrue(only(r).error().message().contains("not minecraft:oak_planks"),
                only(r).error().message());
        assertEquals(0, client.directPlaceCalls, "a wrong item must not become a direct write");
        assertTrue(client.placed.isEmpty(), "the world must be untouched: " + client.placed);
    }

    @Test
    void p11AnUnsyncedInventoryIsNotReportedAsAnEmptyHandForWorldPlace() {
        NotYetSyncedClient client = new NotYetSyncedClient();
        client.held = "";

        Protocol.Receipt r = run(fail("world.place",
                "{\"x\":1,\"y\":64,\"z\":2,\"block\":\"minecraft:oak_planks\"}"), client);

        assertEquals("E_PRECONDITION", code(r));
        assertTrue(only(r).error().message().contains("cannot determine"), only(r).error().message());
        assertEquals("container-not-synced", only(r).error().detail().get("reason").getAsString());
        assertTrue(client.placed.isEmpty(), client.placed.toString());
    }

    // ================================================================ the corrected audit rule

    @Test
    void aPreconditionFailureAfterTheGuardAllowedStillLeavesItsAllowanceLine() {
        // "A refusal leaves no trace" is about guard/parameter refusals. An op precondition that fails
        // AFTER the guard allowed (an empty slot here) has a real allowance, and denying that line would
        // hide an allowance the guard really granted.
        FakeClient client = new FakeClient();
        List<String> audit = new ArrayList<>();

        Protocol.Receipt r = run(fail("inv.toss", "{\"slot\":1,\"count\":1}"),
                ctx(client, Guard.SessionState.singleplayer(), audit, armed()));

        assertEquals("E_PRECONDITION", code(r));
        assertEquals(0, client.tossCalls, "a refused toss must not reach the client");
        assertEquals(1, audit.size(), "the guard DID allow this op: " + audit);
        assertTrue(audit.get(0).startsWith("ALLOWED-MUTATION"), audit.get(0));

        // The control: a guard refusal really does leave no trace.
        List<String> refusedAudit = new ArrayList<>();
        Protocol.Receipt refused = run(fail("inv.toss", "{\"slot\":0,\"count\":1}"),
                ctx(client, Guard.SessionState.singleplayer(), refusedAudit, Guard.ActivationState.off()));
        assertEquals("E_PRECONDITION", code(refused));
        assertEquals(0, refusedAudit.size(), "a guard refusal must leave no trace: " + refusedAudit);
    }

    // ================================================================ off hand

    @Test
    void useItemReportsTheItemOfTheRequestedHand() {
        FakeClient client = new FakeClient();
        client.held = "minecraft:stone";
        client.offHand = "minecraft:shield";

        JsonObject off = result(run(fail("use.item", "{\"hand\":\"off\"}"), client));

        assertEquals("off", off.get("hand").getAsString());
        assertEquals("minecraft:shield", off.getAsJsonObject("heldBefore").get("id").getAsString(),
                "hand:off must report the OFF hand's item, not the main hand's: " + off);
        assertEquals("minecraft:shield", off.getAsJsonObject("heldAfter").get("id").getAsString());
        assertEquals(List.of(Boolean.TRUE), client.useOffHand, "and it must dispatch from the off hand");
    }

    @Test
    void useItemRefusesAnEmptyRequestedHandAndNamesThatHand() {
        FakeClient client = new FakeClient();   // main hand has stone, the off hand is empty

        Protocol.Receipt r = run(fail("use.item", "{\"hand\":\"off\"}"), client);

        assertEquals("E_PRECONDITION", code(r));
        assertTrue(only(r).error().message().contains("off"), only(r).error().message());
        assertEquals("empty-hand", only(r).error().detail().get("reason").getAsString());
        assertEquals(0, client.useCalls);
    }

    @Test
    void stateQueryExposesTheOffHandSoAnOffHandDispatchCanBeChecked() {
        FakeClient client = new FakeClient();
        client.offHand = "minecraft:shield";

        JsonObject all = result(run("{\"op\":\"state.query\",\"params\":{\"what\":[\"all\"]}}", client));
        assertEquals("minecraft:shield", all.get("offhand").getAsString(), all.toString());
        assertEquals("minecraft:stone", all.get("held").getAsString(), all.toString());

        JsonObject justOff = result(
                run("{\"op\":\"state.query\",\"params\":{\"what\":[\"offhand\"]}}", client));
        assertEquals("minecraft:shield", justOff.get("offhand").getAsString(), justOff.toString());
    }

    @Test
    void stateQuerySaysWhenTheInventoryReadMayStillBeCatchingUp() {
        NotYetSyncedClient client = new NotYetSyncedClient();

        JsonObject out = result(run("{\"op\":\"state.query\",\"params\":{\"what\":[\"inventory\"]}}", client));

        assertTrue(out.get("containerSyncPending").getAsBoolean(),
                "an inventory read inside the sync window must say so: " + out);
    }

    // ============================================================ P12: the window must converge

    /** A container whose view never settles — what the P12 defect looked like on a real client. */
    private static final class NeverSettlingClient extends FakeClient {
        @Override
        public long containerSyncAgeTicks() {
            return 0L;   // always "just changed": the age never grows
        }
    }

    /** The same, with nothing in the player's hand: the read that P12 was about. */
    private static NeverSettlingClient neverSettlingWithEmptyHand() {
        NeverSettlingClient c = new NeverSettlingClient();
        c.held = "";
        return c;
    }

    @Test
    void p12APermanentlyUnsettledViewNeverReachesThePlainEmptyHandRefusal() {
        // This is the defect as measured: with a view that never settles, every placement with an empty
        // hand answers `cannot determine` and `empty-hand` is unreachable — a caller waits forever.
        Protocol.Receipt r = run(fail("world.place",
                "{\"x\":1,\"y\":64,\"z\":2,\"block\":\"minecraft:oak_planks\"}"), neverSettlingWithEmptyHand());

        assertEquals("E_PRECONDITION", code(r));
        assertEquals("container-not-synced", only(r).error().detail().get("reason").getAsString(),
                "the conservative answer is correct — and this test exists to show that it must not be "
                        + "the only answer that ever arrives");
        assertFalse(only(r).error().message().contains("no item in the selected slot"),
                only(r).error().message());
    }

    @Test
    void p12TheSyncWindowIsBoundedSoThePlainRefusalBecomesReachable() {
        // The fix: the window is counted in client ticks, so it always closes. One tick inside it stays
        // conservative; one tick past it gives the plain, settled refusal.
        FakeClient inside = new FakeClient();
        inside.held = "";
        inside.containerSyncAgeTicks = ClientModel.CONTAINER_SYNC_WINDOW_TICKS - 1;

        Protocol.Receipt during = run(fail("world.place",
                "{\"x\":1,\"y\":64,\"z\":2,\"block\":\"minecraft:oak_planks\"}"), inside);
        assertEquals("E_PRECONDITION", code(during));
        assertEquals("container-not-synced", only(during).error().detail().get("reason").getAsString(),
                during.toString());

        FakeClient after = new FakeClient();
        after.held = "";
        after.containerSyncAgeTicks = ClientModel.CONTAINER_SYNC_WINDOW_TICKS;

        Protocol.Receipt settled = run(fail("world.place",
                "{\"x\":1,\"y\":64,\"z\":2,\"block\":\"minecraft:oak_planks\"}"), after);
        assertEquals("E_PRECONDITION", code(settled));
        assertEquals("empty-hand", only(settled).error().detail().get("reason").getAsString(),
                "once the window has closed the honest `empty-hand` refusal must be reachable: "
                        + only(settled).error());
        assertTrue(only(settled).error().message().contains("no item in the selected slot"),
                only(settled).error().message());
        assertTrue(after.placed.isEmpty(), "and nothing may be placed");
    }

    @Test
    void p12AnAgeOfExactlyTheWindowIsAlreadySettled() {
        // Boundary pinned: `< window` is conservative, `>= window` is settled. Pinning it here means a
        // future change to either side of the comparison has to be deliberate.
        FakeClient client = new FakeClient();
        client.held = "";
        client.containerSyncAgeTicks = ClientModel.CONTAINER_SYNC_WINDOW_TICKS;

        Protocol.Receipt r = run(fail("use.item", null), client);

        assertEquals("E_PRECONDITION", code(r));
        assertEquals("empty-hand", only(r).error().detail().get("reason").getAsString(),
                only(r).error().toString());
    }

    @Test
    void p12ThePlainEmptyHandRefusalAlsoConvergesForTheOtherOpsThatReadTheInventory() {
        // inv.toss and use.item take the same path, so they must converge with it.
        FakeClient settled = new FakeClient();
        settled.held = "";
        settled.containerSyncAgeTicks = ClientModel.CONTAINER_SYNC_WINDOW_TICKS;

        assertEquals("slot-empty", only(run(fail("inv.toss", "{\"slot\":1,\"count\":1}"), settled))
                .error().detail().get("reason").getAsString());

        FakeClient unsettled = new NeverSettlingClient();
        assertEquals("container-not-synced",
                only(run(fail("inv.toss", "{\"slot\":1,\"count\":1}"), unsettled))
                        .error().detail().get("reason").getAsString());
    }
}
