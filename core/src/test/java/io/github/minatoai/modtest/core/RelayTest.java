package io.github.minatoai.modtest.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M1: the relay loop — bad input never executes, receipts are written once, policy is honoured. */
class RelayTest {

    private final List<String> logLines = new ArrayList<>();
    private final Bridge.MemoryBridgeFs fs = new Bridge.MemoryBridgeFs();

    private Relay.BridgeRelay relay(Bridge.BusyPolicy policy) {
        Executor.OpCatalog catalog = VanillaOps.install(new Executor.OpCatalog("relay", "0.1.0", "relay-test"));
        Bridge.BridgeConfig config = new Bridge.BridgeConfig(Path.of("."), 500L, "relay", "0.1.0", true, policy, 64);
        Executor.TicketExecutor executor = new Executor.TicketExecutor(config, catalog);
        Executor.ExecContext ctx = new Executor.ExecContext(config, Guard.SessionState.singleplayer(),
                Guard.ActivationState.off(), Bridge.Clock.system(),
                java.util.Map.of("allow-mutate", true), new FakeClient());
        return new Relay.BridgeRelay(config, fs, new Relay.TicketValidator(catalog), executor,
                new Relay.ReceiptStore(fs, Bridge.Clock.system()), Bridge.Clock.system(), logLines::add, () -> ctx);
    }

    private static String ticket(String name) {
        return "{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"" + name + "\","
                + "\"ops\":[{\"op\":\"state.query\",\"params\":{\"what\":[\"all\"]}}]}";
    }

    @Test
    void emptyInboxIsANoOp() {
        assertNull(relay(Bridge.BusyPolicy.ANSWER_BUSY).tick());
        assertTrue(fs.outbox.isEmpty());
    }

    @Test
    void validTicketIsExecutedOnceAndMovedToDone() {
        fs.inbox.put("t-ok.json", ticket("t-ok"));
        String answered = relay(Bridge.BusyPolicy.ANSWER_BUSY).tick();

        assertEquals("t-ok", answered);
        assertTrue(fs.hasReceipt("t-ok"));
        assertTrue(fs.done.containsKey("t-ok.json"), "a successful ticket lands in done/");
        assertTrue(fs.failed.isEmpty());
        assertTrue(Json.parseObject(fs.outbox.get("t-ok.result.json")).get("ok").getAsBoolean());
        assertTrue(logLines.stream().anyMatch(l -> l.startsWith("ticket=t-ok result=ok")),
                "structured log line per ticket: " + logLines);
        assertTrue(logLines.stream().anyMatch(l -> l.startsWith("op=state.query opId=op1 result=ok")),
                "structured log line per op: " + logLines);
    }

    @Test
    void aTicketThatAlreadyHasAReceiptIsNeverReExecuted() {
        fs.inbox.put("t-once.json", ticket("t-once"));
        Relay.BridgeRelay relay = relay(Bridge.BusyPolicy.ANSWER_BUSY);
        assertEquals("t-once", relay.tick());
        String firstReceipt = fs.outbox.get("t-once.result.json");
        // Put the ticket back (as a crash mid-move would) and poll again.
        fs.inbox.put("t-once.json", ticket("t-once"));
        assertNull(relay.tick(), "must not run a ticket that already has a receipt");
        assertEquals(firstReceipt, fs.outbox.get("t-once.result.json"));
        assertEquals(0, fs.archiveStamps.size(), "no archive implies no overwrite");
    }

    @Test
    void malformedJsonIsRejectedWithoutExecution() {
        fs.inbox.put("t-bad.json", "{ this is not json");
        String answered = relay(Bridge.BusyPolicy.ANSWER_BUSY).tick();

        assertEquals("t-bad", answered);
        assertTrue(fs.failed.containsKey("t-bad.json"), "unreadable tickets go to failed/");
        assertFalse(Json.parseObject(fs.outbox.get("t-bad.result.json")).get("ok").getAsBoolean());
        assertTrue(logLines.stream().anyMatch(l -> l.contains("result=unreadable")), logLines.toString());
    }

    @Test
    void unknownOpIsRejectedWithEUnknownOpReceipt() {
        fs.inbox.put("t-unknown.json", "{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"t-unknown\","
                + "\"ops\":[{\"op\":\"does.not.exist\"}]}");
        relay(Bridge.BusyPolicy.ANSWER_BUSY).tick();

        String receipt = fs.outbox.get("t-unknown.result.json");
        assertTrue(receipt.contains("E_UNKNOWN_OP"), receipt);
        assertTrue(fs.failed.containsKey("t-unknown.json"));
        assertTrue(logLines.stream().anyMatch(l -> l.contains("code=E_UNKNOWN_OP")), logLines.toString());
    }

    @Test
    void busyPolicyQueueDefersTheSecondTicket() {
        fs.inbox.put("t-a.json", ticket("t-a"));
        fs.inbox.put("t-b.json", ticket("t-b"));
        assertNull(relay(Bridge.BusyPolicy.QUEUE).tick(), "QUEUE leaves both tickets for later ticks");
        assertTrue(fs.outbox.isEmpty());
    }

    @Test
    void busyPolicyAnswerBusyStillAnswersEveryTicket() {
        fs.inbox.put("t-a.json", ticket("t-a"));
        fs.inbox.put("t-b.json", ticket("t-b"));
        Relay.BridgeRelay relay = relay(Bridge.BusyPolicy.ANSWER_BUSY);
        assertNotNull(relay.tick());
        assertNotNull(relay.tick());
        assertEquals(2, fs.outbox.size());
        assertEquals(2, fs.done.size());
    }

    @Test
    void drainAnswersEverythingQueued() {
        fs.inbox.put("t-a.json", ticket("t-a"));
        fs.inbox.put("t-b.json", ticket("t-b"));
        fs.inbox.put("t-c.json", ticket("t-c"));
        List<String> answered = relay(Bridge.BusyPolicy.QUEUE).drain();
        assertEquals(List.of("t-a", "t-b", "t-c"), answered);
        assertEquals(3, fs.outbox.size());
    }
}
