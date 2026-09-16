package io.github.minatoai.modtest.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1: {@code receipt.ops} must stay aligned with {@code ticket.ops} — same length, same order, same
 * ids — for randomly generated tickets. Deterministic seed, so a failure is reproducible.
 */
class ReceiptAlignmentPropertyTest {

    private static final String[] OPS = {
            "state.query", "pose.set", "inv.select", "inv.toss", "use.item", "world.place",
            "shot.capture", "bench.read", "wait.frames", "does.not.exist"
    };

    private Protocol.Ticket randomTicket(Random rnd, int size) {
        StringBuilder ops = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                ops.append(',');
            }
            String op = OPS[rnd.nextInt(OPS.length)];
            String onError = rnd.nextBoolean() ? "abort" : "continue";
            ops.append("{\"id\":\"op").append(i).append("\",\"op\":\"").append(op)
                    .append("\",\"on_error\":\"").append(onError).append('"');
            if ("state.query".equals(op)) {
                ops.append(",\"params\":{\"what\":[\"all\"]}");
            } else if ("pose.set".equals(op)) {
                ops.append(",\"params\":{\"x\":1,\"y\":64,\"z\":2,\"yaw\":").append(rnd.nextInt(360))
                        .append(",\"pitch\":0}");
            } else if ("inv.select".equals(op)) {
                ops.append(",\"params\":{\"slot\":").append(rnd.nextInt(4)).append('}');
            } else if ("inv.toss".equals(op)) {
                ops.append(",\"params\":{\"slot\":").append(rnd.nextInt(4)).append(",\"count\":1}");
            } else if ("world.place".equals(op)) {
                ops.append(",\"params\":{\"x\":").append(rnd.nextInt(3)).append(",\"y\":64,\"z\":0,")
                        .append("\"block\":\"minecraft:stone\"}");
            } else if ("shot.capture".equals(op)) {
                ops.append(",\"params\":{\"name\":\"s").append(i).append("\"}");
            } else if ("wait.frames".equals(op)) {
                ops.append(",\"params\":{\"frames\":").append(rnd.nextInt(5)).append('}');
            }
            ops.append('}');
        }
        String json = "{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"prop\",\"on_error\":\"abort\","
                + "\"ops\":[" + ops + "]}";
        return Protocol.Ticket.fromJson(Json.parseObject(json), "prop");
    }

    @Test
    void receiptStaysAlignedFor200RandomTickets() {
        Executor.OpCatalog catalog = VanillaOps.install(new Executor.OpCatalog("prop", "0.1.0", "prop-test"));
        Relay.TicketValidator validator = new Relay.TicketValidator(catalog);
        Bridge.BridgeConfig config = new Bridge.BridgeConfig(java.nio.file.Path.of("."), 500L, "prop", "0.1.0",
                true, Bridge.BusyPolicy.ANSWER_BUSY, 64);
        Executor.TicketExecutor executor = new Executor.TicketExecutor(config, catalog);
        Random rnd = new Random(20260917L);
        int tickets = 0;
        int skippedSeen = 0;
        int failuresSeen = 0;

        for (int round = 0; round < 200; round++) {
            int size = 1 + rnd.nextInt(6);
            Protocol.Ticket ticket = randomTicket(rnd, size);
            List<Protocol.Receipt.OpResult> results = new ArrayList<>();
            try {
                validator.validate(ticket);
            } catch (Protocol.ProtocolException ignored) {
                continue; // random tickets may reference the unknown op; validation is not under test here
            }
            FakeClient client = new FakeClient();
            Executor.ExecContext ctx = new Executor.ExecContext(config, Guard.SessionState.singleplayer(),
                    Guard.ActivationState.off(), Bridge.Clock.system(), java.util.Map.of("allow-mutate", true), client);
            Protocol.Receipt receipt = executor.execute(ticket, Executor.quietLog(), ctx);
            results.addAll(receipt.ops());
            tickets++;

            assertEquals(ticket.ops().size(), results.size(), "length must match (round " + round + ")");
            boolean aborted = false;
            for (int i = 0; i < results.size(); i++) {
                Protocol.Receipt.OpResult r = results.get(i);
                Protocol.Ticket.Op expected = ticket.ops().get(i);
                assertEquals(expected.id(), r.id(), "op id must line up at index " + i);
                assertEquals(expected.op(), r.op(), "op name must line up at index " + i);
                if (r.skipped()) {
                    skippedSeen++;
                    assertTrue(aborted, "an op may only be skipped after an abort (round " + round + ")");
                } else if (r.ok()) {
                    assertNotNull(r.result(), "successful op must carry a result");
                    assertTrue(r.error() == null, "successful op must not carry an error");
                } else {
                    failuresSeen++;
                    assertNotNull(r.error(), "failed op must carry a structured error");
                    assertNotNull(r.error().code(), "error must carry a stable code");
                    if ("abort".equals(ticket.onErrorFor(expected))) {
                        aborted = true;
                    }
                }
            }
            assertEquals(results.stream().allMatch(Protocol.Receipt.OpResult::ok), receipt.ok(),
                    "receipt.ok must equal the conjunction of op results");
            if (!receipt.ops().isEmpty()) {
                assertNotNull(receipt.executor().id());
                assertEquals(Protocol.ID, receipt.protocol());
                assertEquals("prop", receipt.ticket());
            }
        }
        assertTrue(tickets > 100, "expected most random tickets to be valid, got " + tickets);
        assertTrue(skippedSeen > 0, "expected the generator to exercise the skipped path");
        assertTrue(failuresSeen > 0, "expected the generator to exercise the failure path");
        System.out.println("alignment property: tickets=" + tickets + " skippedOps=" + skippedSeen
                + " failedOps=" + failuresSeen);
    }
}
