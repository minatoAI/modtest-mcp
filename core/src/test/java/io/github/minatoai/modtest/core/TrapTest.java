package io.github.minatoai.modtest.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 trap negatives — the "same cell, same item" family of examples.
 *
 * <p>Each trap is a refusal we must be able to prove: an op that looks harmless must still fail when
 * the world or the player is not in the state it assumes. These are the executable counterparts of
 * the rig's negative-example discipline (place into an occupied cell; swapping an identical item
 * must not bypass an armor guard; using an item twice).
 */
class TrapTest {

    private final Executor.OpCatalog catalog =
            VanillaOps.install(new Executor.OpCatalog("trap", "0.1.0", "trap-test"));

    private Protocol.Receipt run(String ops, FakeClient client) {
        Protocol.Ticket t = Protocol.Ticket.fromJson(Json.parseObject(
                "{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"trap\",\"ops\":[" + ops + "]}"), "trap");
        Bridge.BridgeConfig config = new Bridge.BridgeConfig(Path.of("."), 500L, "trap", "0.1.0", true,
                Bridge.BusyPolicy.ANSWER_BUSY, 64);
        Executor.ExecContext ctx = new Executor.ExecContext(config, Guard.SessionState.singleplayer(),
                Guard.ActivationState.off(), Bridge.Clock.system(), Map.of("allow-mutate", true), client,
                trapGuard());
        return new Executor.TicketExecutor(config, catalog).execute(t, Executor.quietLog(), ctx);
    }

    /**
     * A wired, armed write-op guard, so these tests exercise the <i>trap</i> they are named after and
     * not the fail-closed path of a context without a guard (which has its own tests).
     */
    private static Guard.MutationGuard trapGuard() {
        return new Guard.MutationGuard(
                new Guard.InputInjectionPolicy(Guard.HostWhitelist.of("127.0.0.1"),
                        Guard.BuildVariant.GUARDED, "trap", () -> "minecraft:overworld"),
                Guard.SessionState.singleplayer(),
                new Guard.ActivationState(true,
                        new Guard.ActivationToken("trap-token", Long.MAX_VALUE)),
                Bridge.Clock.system(), line -> {
        }, "ALLOWED-MUTATION");
    }

    @Test
    void placingIntoAnOccupiedCellIsRefused() {
        FakeClient client = new FakeClient();
        client.occupied.add("3,64,-2");
        Protocol.Receipt r = run("{\"op\":\"world.place\",\"params\":{\"x\":3,\"y\":64,\"z\":-2,"
                + "\"block\":\"minecraft:stone\"}}", client);

        assertFalse(r.ok());
        assertEquals("E_EXEC", r.ops().get(0).error().code());
        assertTrue(r.ops().get(0).error().message().contains("occupied"));
        assertTrue(client.placed.isEmpty(), "the block must not be placed");
    }

    @Test
    void placingIntoAFreeCellWorks() {
        FakeClient client = new FakeClient();
        Protocol.Receipt r = run("{\"op\":\"world.place\",\"params\":{\"x\":3,\"y\":64,\"z\":-2,"
                + "\"block\":\"minecraft:stone\"}}", client);

        assertTrue(r.ok(), r.ops().toString());
        assertEquals(1, client.placed.size());
    }

    @Test
    void armorSlotClickIsRefusedEvenWhenTheItemIsIdentical() {
        FakeClient client = new FakeClient();
        // Slot 36 is an armor slot; the incoming item is the same id as what is already there, so an
        // identity check would let it through. The guard is unconditional on purpose.
        client.slots.set(0, "minecraft:diamond_helmet");
        run("{\"op\":\"inv.select\",\"params\":{\"slot\":0}}", client);
        Protocol.Receipt r = run("{\"op\":\"inv.click\",\"params\":{\"slot\":36,\"button\":0,"
                + "\"mode\":\"quick_move\"}}", client);

        assertFalse(r.ok());
        assertEquals("E_PRECONDITION", r.ops().get(0).error().code());
        assertTrue(r.ops().get(0).error().message().contains("armor"));
    }

    @Test
    void usingAnItemWhileAlreadyUsingIsRefused() {
        FakeClient client = new FakeClient();
        client.using = true;
        Protocol.Receipt r = run("{\"op\":\"use.item\"}", client);

        assertFalse(r.ok());
        assertEquals("E_PRECONDITION", r.ops().get(0).error().code());
        assertTrue(r.ops().get(0).error().message().contains("already using"));
    }

    @Test
    void beforeOpHookReleasesTheUsingItemBeforeAnInventoryMutation() {
        FakeClient client = new FakeClient();
        client.using = true;
        Protocol.Receipt r = run("{\"op\":\"inv.select\",\"params\":{\"slot\":1}}", client);

        assertTrue(r.ok(), r.ops().toString());
        assertEquals(1, client.releaseCount, "the table-driven hook must release the using item first");
        assertFalse(client.using);
        assertTrue(r.ops().get(0).result().has("before_op"), "the hook note belongs in the receipt");
        assertEquals("using-released-before-inv.select",
                r.ops().get(0).result().getAsJsonArray("before_op").get(0).getAsString());
    }

    @Test
    void hooksAreTableDrivenNotHardcoded() {
        VanillaOps.OpHooks custom = new VanillaOps.OpHooks().on("bench.", (op, ctx) -> "bench-hook-ran");
        Bridge.BridgeConfig config = new Bridge.BridgeConfig(Path.of("."), 500L, "trap", "0.1.0", false,
                Bridge.BusyPolicy.ANSWER_BUSY, 64);
        Executor.ExecContext ctx = new Executor.ExecContext(config, Guard.SessionState.singleplayer(),
                Guard.ActivationState.off(), Bridge.Clock.system(), Map.of(), new FakeClient());
        Protocol.Ticket t = Protocol.Ticket.fromJson(Json.parseObject(
                "{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"trap\",\"ops\":[{\"op\":\"bench.read\"}]}"),
                "trap");
        Protocol.Receipt r = new Executor.TicketExecutor(config, catalog, custom)
                .execute(t, Executor.quietLog(), ctx);

        assertTrue(r.ok());
        assertEquals("bench-hook-ran", r.ops().get(0).result().getAsJsonArray("before_op").get(0).getAsString(),
                "a provider can register its own hooks without touching the executor");
    }

    @Test
    void tossingFromAnEmptySlotFails() {
        FakeClient client = new FakeClient();
        Protocol.Receipt r = run("{\"op\":\"inv.toss\",\"params\":{\"slot\":1,\"count\":1}}", client);
        assertFalse(r.ok());
        // "the slot is empty" is a state the op assumes, so the refusal is E_PRECONDITION — not the
        // E_EXEC the pre-task-70 adapter threw, and never a silent success with delta 0.
        assertEquals("E_PRECONDITION", r.ops().get(0).error().code());
        assertTrue(r.ops().get(0).error().message().contains("empty"), r.ops().get(0).error().message());
        assertEquals(0, client.tossCalls, "a refused toss must not reach the client");
    }

    @Test
    void hooksApplyToEveryOpSharingThePrefix() {
        List<String> ops = List.of("inv.select", "inv.click", "inv.toss");
        for (String op : ops) {
            FakeClient client = new FakeClient();
            client.using = true;
            String params = op.endsWith("select") ? "{\"slot\":1}"
                    : op.endsWith("click") ? "{\"slot\":1,\"button\":0,\"mode\":\"pick\"}"
                    : "{\"slot\":0,\"count\":1}";
            Protocol.Receipt r = run("{\"op\":\"" + op + "\",\"params\":" + params + "}", client);
            assertEquals(1, client.releaseCount, op + " must trigger the shared before-op hook");
            assertTrue(r.ok() || r.ops().get(0).error() != null, op + " must still be reported");
        }
    }
}
