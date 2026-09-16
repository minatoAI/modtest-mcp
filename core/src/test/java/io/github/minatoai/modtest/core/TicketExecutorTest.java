package io.github.minatoai.modtest.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3: ordering, on_error, preconditions, the mutating gate, timeouts and structured errors. */
class TicketExecutorTest {

    private final Executor.OpCatalog catalog =
            VanillaOps.install(new Executor.OpCatalog("exec-test", "0.1.0", "unit-test"));

    private Protocol.Ticket ticket(String ops) {
        return Protocol.Ticket.fromJson(Json.parseObject(
                "{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"t\",\"ops\":[" + ops + "]}"), "t");
    }

    private Executor.ExecContext ctx(FakeClient client, boolean allowMutate, Guard.SessionState session,
                                     Bridge.Clock clock, Map<String, Boolean> flags) {
        Bridge.BridgeConfig config = new Bridge.BridgeConfig(Path.of("."), 500L, "exec-test", "0.1.0",
                allowMutate, Bridge.BusyPolicy.ANSWER_BUSY, 64);
        return new Executor.ExecContext(config, session, Guard.ActivationState.off(), clock, flags, client);
    }

    private Bridge.BridgeConfig config(boolean allowMutate) {
        return new Bridge.BridgeConfig(Path.of("."), 500L, "exec-test", "0.1.0", allowMutate,
                Bridge.BusyPolicy.ANSWER_BUSY, 64);
    }

    @Test
    void opsRunInOrderAndIdsArePreserved() {
        Protocol.Ticket t = ticket("{\"id\":\"first\",\"op\":\"state.query\",\"params\":{\"what\":[\"pose\"]}},"
                + "{\"id\":\"second\",\"op\":\"bench.read\"}");
        Executor.TicketExecutor executor = new Executor.TicketExecutor(config(false), catalog);
        Protocol.Receipt r = executor.execute(t, Executor.quietLog(), ctx(new FakeClient(), false,
                Guard.SessionState.singleplayer(), Bridge.Clock.system(), Map.of()));

        assertEquals(List.of("first", "second"), r.ops().stream().map(Protocol.Receipt.OpResult::id).toList());
        assertTrue(r.ok());
        assertEquals(144.5, r.ops().get(1).result().get("fps_median").getAsDouble(), 1e-9);
    }

    @Test
    void abortStopsTheRestAndMarksThemSkipped() {
        FakeClient client = new FakeClient();
        client.occupied.add("1,64,1");
        Protocol.Ticket t = ticket("{\"id\":\"a\",\"op\":\"world.place\","
                + "\"params\":{\"x\":1,\"y\":64,\"z\":1,\"block\":\"minecraft:stone\"},\"on_error\":\"abort\"},"
                + "{\"id\":\"b\",\"op\":\"bench.read\"}");
        Protocol.Receipt r = new Executor.TicketExecutor(config(true), catalog)
                .execute(t, Executor.quietLog(), ctx(client, true, Guard.SessionState.singleplayer(),
                        Bridge.Clock.system(), Map.of("allow-mutate", true)));

        assertFalse(r.ok());
        assertFalse(r.ops().get(0).ok());
        assertEquals("E_EXEC", r.ops().get(0).error().code());
        assertTrue(r.ops().get(1).skipped(), "the op after an abort must be skipped");
        assertTrue(client.placed.isEmpty(), "nothing may be placed into an occupied cell");
    }

    @Test
    void continueKeepsExecutingAfterAFailure() {
        FakeClient client = new FakeClient();
        client.occupied.add("1,64,1");
        Protocol.Ticket t = ticket("{\"id\":\"a\",\"op\":\"world.place\","
                + "\"params\":{\"x\":1,\"y\":64,\"z\":1,\"block\":\"minecraft:stone\"},\"on_error\":\"continue\"},"
                + "{\"id\":\"b\",\"op\":\"bench.read\"}");
        Protocol.Receipt r = new Executor.TicketExecutor(config(true), catalog)
                .execute(t, Executor.quietLog(), ctx(client, true, Guard.SessionState.singleplayer(),
                        Bridge.Clock.system(), Map.of("allow-mutate", true)));

        assertFalse(r.ok());
        assertFalse(r.ops().get(1).skipped(), "on_error=continue must keep going");
        assertTrue(r.ops().get(1).ok());
    }

    @Test
    void mutatingOpsAreRefusedWithoutAllowMutate() {
        FakeClient client = new FakeClient();
        Protocol.Ticket t = ticket("{\"op\":\"world.place\",\"params\":{\"x\":0,\"y\":64,\"z\":0,"
                + "\"block\":\"minecraft:stone\"},\"expect\":{\"placed\":{\"op\":\"eq\",\"value\":true}}}");
        Protocol.Receipt r = new Executor.TicketExecutor(config(false), catalog)
                .execute(t, Executor.quietLog(), ctx(client, false, Guard.SessionState.singleplayer(),
                        Bridge.Clock.system(), Map.of("allow-mutate", true)));

        assertEquals("E_PRECONDITION", r.ops().get(0).error().code());
        assertTrue(client.placed.isEmpty(), "no world write without allow-mutate");
    }

    @Test
    void mutatingOpsAreRefusedOnARemoteSession() {
        FakeClient client = new FakeClient();
        Protocol.Ticket t = ticket("{\"op\":\"world.place\",\"params\":{\"x\":0,\"y\":64,\"z\":0,"
                + "\"block\":\"minecraft:stone\"}}");
        Protocol.Receipt r = new Executor.TicketExecutor(config(true), catalog)
                .execute(t, Executor.quietLog(), ctx(client, true, Guard.SessionState.remote("play.example.net"),
                        Bridge.Clock.system(), Map.of("allow-mutate", true)));

        assertEquals("E_PRECONDITION", r.ops().get(0).error().code());
        assertTrue(r.ops().get(0).error().message().contains("single-player"));
        assertTrue(client.placed.isEmpty(), "no world write on a remote session");
    }

    @Test
    void preconditionFlagMustBeSetForPoseOps() {
        Protocol.Ticket t = ticket("{\"op\":\"pose.set\",\"params\":{\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0}}");
        FakeClient client = new FakeClient();
        Protocol.Receipt r = new Executor.TicketExecutor(config(true), catalog)
                .execute(t, Executor.quietLog(), ctx(client, true, Guard.SessionState.singleplayer(),
                        Bridge.Clock.system(), Map.of()));

        assertEquals("E_PRECONDITION", r.ops().get(0).error().code());
        assertTrue(r.ops().get(0).error().message().contains("allow-mutate"));
        assertEquals(0, client.teleports, "precondition failures must not move the player");
    }

    @Test
    void exceedingThePerOpBudgetYieldsETimeout() {
        // A clock that advances 10 ms on every read simulates a slow op.
        long[] now = {1_000L};
        Bridge.Clock stepping = () -> {
            now[0] += 10L;
            return now[0];
        };
        Protocol.Ticket t = ticket("{\"op\":\"bench.read\",\"timeout_ms\":5}");
        Protocol.Receipt r = new Executor.TicketExecutor(config(false), catalog)
                .execute(t, Executor.quietLog(), ctx(new FakeClient(), false, Guard.SessionState.singleplayer(),
                        stepping, Map.of()));

        assertEquals("E_TIMEOUT", r.ops().get(0).error().code());
    }

    @Test
    void aThrowingHandlerBecomesAStructuredEExec() {
        FakeClient client = new FakeClient();
        client.useThrows = true;
        Protocol.Ticket t = ticket("{\"op\":\"use.item\"}");
        Protocol.Receipt r = new Executor.TicketExecutor(config(true), catalog)
                .execute(t, Executor.quietLog(), ctx(client, true, Guard.SessionState.singleplayer(),
                        Bridge.Clock.system(), Map.of("allow-mutate", true)));

        assertEquals("E_EXEC", r.ops().get(0).error().code());
        assertTrue(r.ops().get(0).error().message().contains("IllegalStateException"));
    }

    @Test
    void aFailedExpectationYieldsEAssertWithTheFailingPath() {
        Protocol.Ticket t = ticket("{\"op\":\"state.query\",\"params\":{\"what\":[\"pose\"]},"
                + "\"expect\":{\"pose.yaw\":{\"op\":\"eq\",\"value\":999}}}");
        Protocol.Receipt r = new Executor.TicketExecutor(config(false), catalog)
                .execute(t, Executor.quietLog(), ctx(new FakeClient(), false, Guard.SessionState.singleplayer(),
                        Bridge.Clock.system(), Map.of()));

        assertEquals("E_ASSERT", r.ops().get(0).error().code());
        assertEquals("pose.yaw", r.ops().get(0).error().detail().get("failed").getAsString());
        assertNotNull(r.ops().get(0).error().message());
    }

    @Test
    void receiptCarriesTimingAndExecutorIdentity() {
        Protocol.Ticket t = ticket("{\"op\":\"state.query\",\"params\":{\"what\":[\"all\"]}}");
        Protocol.Receipt r = new Executor.TicketExecutor(config(false), catalog)
                .execute(t, Executor.quietLog(), ctx(new FakeClient(), false, Guard.SessionState.singleplayer(),
                        Bridge.Clock.system(), Map.of()));
        assertEquals("exec-test", r.executor().id());
        assertEquals("0.1.0", r.executor().version());
        assertTrue(r.durationMs() >= 0);
        assertTrue(r.toJson().has("ops") && r.toJson().get("ops").isJsonArray());
    }
}
