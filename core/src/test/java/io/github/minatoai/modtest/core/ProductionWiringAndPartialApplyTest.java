package io.github.minatoai.modtest.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 + P5 regression guards.
 *
 * <p><b>P4</b> real-machine: with {@code MODTEST_ALLOWED_HOSTS=127.0.0.1:25585} the injection was
 * still refused as {@code host 'unknown' is not whitelisted}, because the adapter never provided
 * {@link Guard.SessionState#serverAddress()} — so the allow branch ("declared host") was dead code
 * on a real client while the refusal branch worked. Four defects of this family have now been fixed
 * (3-arg writer, 7-arg config, no-arg policy, missing address), so the tests below pin down both
 * the *reachability* of the allow path and the *fail-closed* behaviour when the adapter forgets.
 *
 * <p><b>P5</b>: {@code pose.set} returned {@code ok:true} while the position was rubber-banded back
 * by the server. Requested ≠ applied, and the receipt now has to say which fields took effect.
 */
class ProductionWiringAndPartialApplyTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Bridge.Clock CLOCK = Bridge.Clock.fixed(NOW);

    private static Protocol.OpSpec inputOp() {
        return new Protocol.OpSpec("input.set", "in", Json.object(), Json.object(),
                List.of(), List.of(Protocol.SideEffect.PLAYER_INPUT), "forge-client", null, "1.0");
    }

    private static Guard.ActivationState armed() {
        return new Guard.ActivationState(true, new Guard.ActivationToken("tok", NOW + 60_000L));
    }

    // ---- P4: the deny side is explicit -------------------------------------------------------
    @Test
    void anAdapterThatNeverProvidesAnAddressIsRefusedExplicitly() {
        // Exactly the old MinecraftSessionState: the interface default, nothing overridden.
        Guard.SessionState noAddress = new Guard.SessionState() {
            public boolean hasIntegratedServer() {
                return false;
            }

            public boolean connectedToRemoteServer() {
                return true;
            }

            public boolean paused() {
                return false;
            }

            public boolean inWorld() {
                return true;
            }

            public boolean handshakeInProgress() {
                return false;
            }
            // serverAddress() intentionally not overridden -> null
        };
        assertEquals(null, noAddress.serverAddress());

        Guard.Decision d = new Guard.InputInjectionPolicy(Guard.HostWhitelist.of("127.0.0.1"),
                Guard.BuildVariant.GUARDED, "forge-client", () -> "minecraft:overworld")
                .decide(inputOp(), noAddress, armed(), CLOCK);

        assertFalse(d.allowed(), "an unavailable address must never be treated as allowed");
        assertTrue(d.reason().contains("address unavailable"), d.reason());
        assertTrue(d.reason().contains("cannot verify ownership"), d.reason());
        assertFalse(d.reason().contains("not whitelisted"),
                "the old message hid the real cause (a null address); it must now say so: " + d.reason());
    }

    @Test
    void aConnectedButUnresolvableSessionIsAlsoRefused() {
        Guard.Decision d = new Guard.InputInjectionPolicy(Guard.HostWhitelist.of("127.0.0.1"),
                Guard.BuildVariant.GUARDED, "forge-client", () -> "minecraft:overworld")
                .decide(inputOp(), Guard.SessionState.remote("unknown"), armed(), CLOCK);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("address unavailable"), d.reason());
    }

    // ---- P4: the allow side is REACHABLE (the dead code is alive) ----------------------------
    @Test
    void aDeclaredHostIsAllowedWhenTheAdapterProvidesTheAddress() {
        Guard.InputInjectionPolicy policy = new Guard.InputInjectionPolicy(
                Guard.HostWhitelist.of("127.0.0.1"), Guard.BuildVariant.GUARDED, "forge-client",
                () -> "minecraft:overworld");

        // The real-machine case: declared 127.0.0.1:25585, connected to 127.0.0.1:25585.
        Guard.Decision hit = policy.decide(inputOp(), Guard.SessionState.remote("127.0.0.1:25585"),
                armed(), CLOCK);
        assertTrue(hit.allowed(), "declared host must actually be usable: " + hit.reason());
        assertTrue(hit.reason().contains("whitelisted host"), hit.reason());

        // Port-agnostic matching, both directions, and bracketed IPv6.
        assertTrue(policy.decide(inputOp(), Guard.SessionState.remote("127.0.0.1"), armed(), CLOCK).allowed());
        assertTrue(new Guard.InputInjectionPolicy(Guard.HostWhitelist.of("localhost:25585"),
                Guard.BuildVariant.GUARDED, "forge-client", () -> "d")
                .decide(inputOp(), Guard.SessionState.remote("localhost"), armed(), CLOCK).allowed());
        assertTrue(new Guard.InputInjectionPolicy(Guard.HostWhitelist.of("[::1]"),
                Guard.BuildVariant.GUARDED, "forge-client", () -> "d")
                .decide(inputOp(), Guard.SessionState.remote("[::1]:25585"), armed(), CLOCK).allowed());

        // A different host is still refused, and the refusal is a whitelist miss.
        Guard.Decision miss = policy.decide(inputOp(), Guard.SessionState.remote("10.0.0.7:25585"),
                armed(), CLOCK);
        assertFalse(miss.allowed());
        assertTrue(miss.reason().contains("not whitelisted"), miss.reason());
    }

    @Test
    void portOnlyMatchingIsHostOnlySoAnUndeclaredPortCannotWidenAccess() {
        // Declaring the machine declares every port on it; a *different machine* is unaffected.
        Guard.HostWhitelist hosts = Guard.HostWhitelist.of("127.0.0.1");
        assertTrue(hosts.permitsHost("127.0.0.1:1"));
        assertTrue(hosts.permitsHost("127.0.0.1:25585"));
        assertFalse(hosts.permitsHost("127.0.0.11"));
        assertFalse(hosts.permitsHost("127.0.0.1.evil.example"));
    }

    // ---- P5: requested != applied ------------------------------------------------------------
    /** A fake client whose position is server-authoritative: teleport moves rotation only. */
    static class RubberBandingClient implements ClientModel {
        double x = 0, y = 64, z = 2.8755;
        float yaw = -180f, pitch = 5f;
        double teleports;

        @Override
        public double x() {
            return x;
        }

        @Override
        public double y() {
            return y;
        }

        @Override
        public double z() {
            return z;
        }

        @Override
        public float yaw() {
            return yaw;
        }

        @Override
        public float pitch() {
            return pitch;
        }

        @Override
        public String dimension() {
            return "minecraft:overworld";
        }

        @Override
        public String heldItemId() {
            return "";
        }

        @Override
        public void teleport(double nx, double ny, double nz, float nyaw, float npitch, int settleMs) {
            teleports++;          // position: rubber-banded straight back (server authority)
            yaw = nyaw;           // rotation: client-side, takes effect
            pitch = npitch;
        }

        @Override
        public boolean settled() {
            return true;
        }

        @Override
        public List<String> inventory() {
            return List.of();
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
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "n/a");
        }

        @Override
        public void tossSlot(int slot, int count) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "n/a");
        }

        @Override
        public void useItem() {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "n/a");
        }

        @Override
        public boolean cellOccupied(int bx, int by, int bz) {
            return false;
        }

        @Override
        public void useItemOnBlock(int bx, int by, int bz, String block) {
        }

        @Override
        public String captureScreenshot(String name) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "n/a");
        }

        @Override
        public BenchSample bench(int warmupFrames, int sampleFrames) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "n/a");
        }

        @Override
        public void waitFrames(int frames) {
        }
    }

    private static Executor.ExecContext ctx(ClientModel client, boolean singleplayer) {
        Bridge.BridgeConfig cfg = new Bridge.BridgeConfig(java.nio.file.Path.of("."), 500L, "exec-test",
                "1.0.0-alpha.1", true, Bridge.BusyPolicy.ANSWER_BUSY, 64);
        Guard.SessionState session = singleplayer ? Guard.SessionState.singleplayer()
                : Guard.SessionState.remote("127.0.0.1:25585");
        return new Executor.ExecContext(cfg, session, armed(), CLOCK,
                java.util.Map.of("allow-mutate", true), client, guard(session));
    }

    /** pose.set is a write: the handler must be given a wired, armed guard. */
    private static Guard.MutationGuard guard(Guard.SessionState session) {
        return new Guard.MutationGuard(
                new Guard.InputInjectionPolicy(Guard.HostWhitelist.of("127.0.0.1"),
                        Guard.BuildVariant.GUARDED, "forge-client", () -> "minecraft:overworld"),
                session, armed(), CLOCK, Guard.AuditSink.to(line -> {
        }));
    }

    private static JsonObject poseParams() {
        return com.google.gson.JsonParser.parseString(
                "{\"x\":2000,\"y\":121,\"z\":12,\"yaw\":0,\"pitch\":0}").getAsJsonObject();
    }

    @Test
    void poseSetReportsPositionAsSkippedWhenTheServerRubberBandsIt() {
        RubberBandingClient client = new RubberBandingClient();
        JsonObject out = new VanillaOps.PoseOps().handle(
                new Protocol.Ticket.Op("p1", "pose.set", poseParams(), null, null, null), ctx(client, false));

        assertEquals(1, client.teleports);
        JsonObject skipped = out.getAsJsonObject("notClientVerifiable");
        assertNotNull(skipped, "a partial application must be reportable: " + out);
        for (String f : List.of("x", "y", "z")) {
            assertTrue(skipped.has(f), f + " must be reported as notClientVerifiable: " + out);
            assertTrue(skipped.getAsJsonObject(f).has("requested"), out.toString());
            assertTrue(skipped.getAsJsonObject(f).has("observedAtReadback"), out.toString());
        }
        JsonObject applied = out.getAsJsonObject("applied");
        assertTrue(applied.has("yaw") && applied.has("pitch"),
                "client-side rotation does take effect and must be reported as applied: " + out);
        assertEquals("server", out.get("authority").getAsString());
        assertTrue(out.get("note").getAsString().contains("server owns the player position"), out.toString());
    }

    @Test
    void singlePlayerPositionIsStillReportedAsSkippedAndRotationAsApplied() {
        // Round 12 proved this the hard way: a single-player world's integrated server is
        // authoritative too, so even a client that holds the requested position may be reverted by
        // the authority later. Position is therefore never reported as applied; rotation is.
        RubberBandingClient client = new RubberBandingClient() {
            @Override
            public void teleport(double nx, double ny, double nz, float nyaw, float npitch, int settleMs) {
                x = nx;
                y = ny;
                z = nz;
                yaw = nyaw;
                pitch = npitch;
            }
        };
        JsonObject out = new VanillaOps.PoseOps().handle(
                new Protocol.Ticket.Op("p1", "pose.set", poseParams(), null, null, null), ctx(client, true));

        JsonObject applied = out.getAsJsonObject("applied");
        JsonObject skipped = out.getAsJsonObject("skipped");
        for (String f : List.of("x", "y", "z")) {
            assertFalse(applied.has(f), f + " must never be applied: " + out);
            assertEquals("server-authoritative position",
                    skipped.getAsJsonObject(f).get("reason").getAsString(), out.toString());
        }
        assertTrue(applied.has("yaw") && applied.has("pitch"),
                "rotation is client-authoritative and must be applied: " + out);
        assertEquals("client", out.get("authority").getAsString());
        assertTrue(out.get("note").getAsString().contains("client position readings cannot be authoritative"),
                out.toString());
    }
}
