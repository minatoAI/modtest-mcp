package io.github.minatoai.modtest.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three write ops that existed before task-70 — {@code inv.select}, {@code pose.set},
 * {@code world.place} — must pass the <b>same</b> single guard point as {@code input.set} and the
 * task-70 batch.
 *
 * <p>They did not. They were gated only by the executor's {@code allow-mutate} flag and the host
 * precondition, so a write could happen with <b>no activation token and no audit line</b> — the same
 * "every write must be accountable" promise that {@code inv.click}/{@code inv.toss} broke in a
 * different way. Each op is asserted on all three paths here, because each path is a different lie:
 * an allowance with no line hides a write, a refusal with a line buries the real ones, and a no-op that
 * still writes is a write nobody authorised.
 */
class LegacyWriteOpsGuardTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Bridge.Clock CLOCK = Bridge.Clock.fixed(NOW);
    private static final String TOKEN_VALUE = "tok-legacy-secret";

    private static final List<String> OPS = List.of("inv.select", "pose.set", "world.place");

    private static Executor.OpCatalog catalog() {
        return VanillaOps.install(new Executor.OpCatalog("legacy-test", "0.1.0", "legacy-guard-test"));
    }

    private static Bridge.BridgeConfig config() {
        return new Bridge.BridgeConfig(Path.of("."), 500L, "legacy-test", "0.1.0", true,
                Bridge.BusyPolicy.ANSWER_BUSY, 64, Guard.HostWhitelist.empty());
    }

    private static Guard.ActivationState armed() {
        return new Guard.ActivationState(true, new Guard.ActivationToken(TOKEN_VALUE, NOW + 60_000L));
    }

    private static Executor.ExecContext ctx(FakeClient client, Guard.SessionState session,
                                            Guard.ActivationState activation, List<String> audit,
                                            boolean wired) {
        Map<String, Boolean> flags = Map.of("allow-mutate", true);
        if (!wired) {
            // Exactly what an adapter that never built a guard hands over: the fail-closed default.
            return new Executor.ExecContext(config(), session, activation, CLOCK, flags, client);
        }
        return new Executor.ExecContext(config(), session, activation, CLOCK, flags, client,
                new Guard.MutationGuard(
                        new Guard.InputInjectionPolicy(Guard.HostWhitelist.empty(),
                                Guard.BuildVariant.GUARDED, "legacy-test", () -> "minecraft:overworld"),
                        session, activation, CLOCK, Guard.AuditSink.to(audit::add)));
    }

    private static Protocol.Receipt run(String op, FakeClient client, Executor.ExecContext ctx) {
        Protocol.Ticket ticket = Protocol.Ticket.fromJson(Json.parseObject(
                "{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"legacy\",\"ops\":["
                        + paramsFor(op) + "]}"), "legacy");
        return new Executor.TicketExecutor(config(), catalog()).execute(ticket, Executor.quietLog(), ctx);
    }

    private static String paramsFor(String op) {
        return switch (op) {
            case "inv.select" -> "{\"id\":\"a\",\"op\":\"inv.select\",\"params\":{\"slot\":1}}";
            case "pose.set" -> "{\"id\":\"a\",\"op\":\"pose.set\","
                    + "\"params\":{\"x\":1,\"y\":64,\"z\":2,\"yaw\":0,\"pitch\":0}}";
            default -> "{\"id\":\"a\",\"op\":\"world.place\","
                    + "\"params\":{\"x\":1,\"y\":64,\"z\":2,\"block\":\"minecraft:stone\"}}";
        };
    }

    /** How many times the op actually reached the client. */
    private static int clientWrites(FakeClient client) {
        return client.selectCalls + client.teleports + client.placed.size();
    }

    @Test
    void everyLegacyWriteOpIsAllowedAndLeavesExactlyOneAuditLine() {
        for (String op : OPS) {
            FakeClient client = new FakeClient();
            List<String> audit = new ArrayList<>();
            Protocol.Receipt r = run(op, client,
                    ctx(client, Guard.SessionState.singleplayer(), armed(), audit, true));

            assertTrue(r.ops().get(0).ok(), op + ": " + r.ops().get(0).error());
            assertEquals(1, clientWrites(client), op + " must really reach the client once");
            assertEquals(1, audit.size(), op + " must leave exactly one audit line: " + audit);

            String line = audit.get(0);
            assertTrue(line.startsWith("ALLOWED-MUTATION"), line);
            assertTrue(line.contains("executor=legacy-test"), "who acted: " + line);
            assertTrue(line.contains("host=singleplayer"), "which host: " + line);
            assertTrue(line.contains("at=" + NOW), "when: " + line);
            assertTrue(line.contains("op=" + op), "which op: " + line);
            String fingerprint = new Guard.ActivationToken(TOKEN_VALUE, 0L).fingerprint();
            assertEquals(8, fingerprint.length());
            assertTrue(line.contains("token=" + fingerprint), line);
            assertFalse(line.contains(TOKEN_VALUE), "the token VALUE must never be logged: " + line);
        }
    }

    @Test
    void everyLegacyWriteOpIsRefusedWhenTheGuardRefusesAndLeavesNoTrace() {
        for (String op : OPS) {
            FakeClient client = new FakeClient();
            List<String> audit = new ArrayList<>();
            // Armed off: tier 2 refuses even in a single-player world.
            Protocol.Receipt r = run(op, client, ctx(client, Guard.SessionState.singleplayer(),
                    Guard.ActivationState.off(), audit, true));

            Protocol.Receipt.OpResult result = r.ops().get(0);
            assertFalse(result.ok(), op);
            assertEquals("E_PRECONDITION", result.error().code(), op);
            assertTrue(result.error().message().contains("off by default"), result.error().message());
            assertEquals(0, clientWrites(client), op + " must not touch the client when refused");
            assertTrue(audit.isEmpty(), op + " refusal must leave no trace at all: " + audit);
        }
    }

    @Test
    void everyLegacyWriteOpOnANoOpSessionWritesNothingAndLeavesNoTrace() {
        // The guard's no-op branch: allowed, but there is nothing to do.
        Guard.SessionState paused = Guard.SessionState.of(true, false, true, true, false, null);
        for (String op : OPS) {
            FakeClient client = new FakeClient();
            List<String> audit = new ArrayList<>();
            Protocol.Receipt r = run(op, client, ctx(client, paused, armed(), audit, true));

            Protocol.Receipt.OpResult result = r.ops().get(0);
            assertTrue(result.ok(), op + ": " + result.error());
            assertEquals("skipped", result.result().get("verdict").getAsString(), op + ": " + result.result());
            assertEquals(0, clientWrites(client), op + " must not write on a guard no-op");
            assertTrue(audit.isEmpty(), op + " no-op is not an allowance: " + audit);
        }
    }

    @Test
    void everyLegacyWriteOpFailsClosedWithoutAWiredGuard() {
        for (String op : OPS) {
            FakeClient client = new FakeClient();
            Protocol.Receipt r = run(op, client, ctx(client, Guard.SessionState.singleplayer(),
                    armed(), new ArrayList<>(), false));

            assertEquals("E_PRECONDITION", r.ops().get(0).error().code(), op);
            assertTrue(r.ops().get(0).error().message().contains("not wired"), r.ops().get(0).error().message());
            assertEquals(0, clientWrites(client), op + " must not be written without a guard");
        }
    }
}
