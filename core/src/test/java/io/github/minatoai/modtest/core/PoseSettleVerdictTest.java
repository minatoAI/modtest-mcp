package io.github.minatoai.modtest.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5 rework (task-65): the applied/skipped verdict MUST come from a <b>settled</b> read-back.
 *
 * <p>Real-machine evidence: {@code pose.set{z:12}} reported five applied fields and
 * {@code settled:true} while a follow-up read showed the position had never moved — single-player
 * (the integrated server is authoritative too) and multiplayer alike. The old code read the pose
 * immediately after {@code teleport()}, i.e. the transient local value.
 *
 * <p>{@link #anInstantaneousReadBackWouldHaveSaidAppliedButSettledSaysSkipped()} is deliberately
 * written so that it <b>fails against the old implementation</b>: the fake applies the requested pose
 * at once (so an early read sees it) and reverts it on the next tick, exactly like the real client.
 */
class PoseSettleVerdictTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Bridge.Clock CLOCK = Bridge.Clock.fixed(NOW);

    /** Teleport is visible locally at once; the authoritative pose is restored on the next tick. */
    static class LateRevertingClient implements ClientModel {
        private final double sx;
        private final double sy;
        private final double sz;
        private final boolean everSettles;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        private int pendingTicks;

        LateRevertingClient(double sx, double sy, double sz, boolean everSettles) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.everSettles = everSettles;
            this.x = sx;
            this.y = sy;
            this.z = sz;
        }

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
            x = nx;                       // transient local pose — an early read-back sees THIS
            y = ny;
            z = nz;
            yaw = nyaw;                   // rotation is client-side, so it survives
            pitch = npitch;
            pendingTicks = 1;
        }

        @Override
        public void waitFrames(int frames) {
            for (int i = 0; i < frames; i++) {
                if (pendingTicks > 0) {
                    pendingTicks--;
                    if (pendingTicks == 0) {
                        x = sx;           // the authority publishes its own position again
                        y = sy;
                        z = sz;
                    }
                }
            }
        }

        @Override
        public boolean settled() {
            return everSettles && pendingTicks == 0;
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
        public void placeBlock(int bx, int by, int bz, String block) {
        }

        @Override
        public String captureScreenshot(String name) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "n/a");
        }

        @Override
        public BenchSample bench(int warmupFrames, int sampleFrames) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED, "n/a");
        }
    }

    private static JsonObject params(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** x=2000/y=121/z=12 is deliberately far from any authoritative value used below. */
    private static final JsonObject REQUEST = params("{\"x\":2000,\"y\":121,\"z\":12,\"yaw\":0,\"pitch\":0}");

    private static JsonObject poseSet(ClientModel client, boolean singleplayer) {
        Bridge.BridgeConfig cfg = new Bridge.BridgeConfig(Path.of("."), 500L, "exec-test",
                "1.0.0-alpha.1", true, Bridge.BusyPolicy.ANSWER_BUSY, 64);
        Executor.ExecContext ctx = new Executor.ExecContext(cfg,
                singleplayer ? Guard.SessionState.singleplayer() : Guard.SessionState.remote("127.0.0.1:25585"),
                new Guard.ActivationState(true, new Guard.ActivationToken("tok", NOW + 60_000L)),
                CLOCK, Map.of("allow-mutate", true), client);
        return new VanillaOps.PoseOps().handle(new Protocol.Ticket.Op("p1", "pose.set", REQUEST, null, null, null),
                ctx);
    }

    // ---- the anti-regression test -------------------------------------------------------------
    @Test
    void anInstantaneousReadBackWouldHaveSaidAppliedButSettledSaysSkipped() {
        // Authoritative pose is where the player really is; the requested one never sticks.
        LateRevertingClient client = new LateRevertingClient(-4.5, 70.0, 7.1018, true);
        JsonObject out = poseSet(client, false);

        assertEquals(true, out.get("settled").getAsBoolean(), out.toString());
        JsonObject applied = out.getAsJsonObject("applied");
        JsonObject skipped = out.getAsJsonObject("notClientVerifiable");
        for (String f : List.of("x", "y", "z")) {
            assertFalse(applied.has(f), f + " must NOT be reported as applied: " + out);
            assertTrue(skipped.has(f), f + " must be reported as notClientVerifiable (a remote authority"
                    + " owns it and the client cannot witness it): " + out);
            assertTrue(skipped.getAsJsonObject(f).has("observedAtReadback"), out.toString());
            assertTrue(skipped.getAsJsonObject(f).has("requested"), out.toString());
        }
        // The transient value read immediately after teleport() WAS the requested one, so the old
        // implementation reported these three as applied. Reading after the settle loop is what
        // makes this assertion true — and it is the whole point of the test.
        assertTrue(applied.has("yaw") && applied.has("pitch"),
                "client-side rotation does survive and must stay applied: " + out);
    }

    @Test
    void theSinglePlayerBranchRevertsTooAndIsReportedHonestly() {
        // Same rubber-band, but on a single-player session: the INTEGRATED server is authoritative.
        JsonObject out = poseSet(new LateRevertingClient(0.0, 64.0, 9.781176179073594, true), true);

        assertEquals("client", out.get("authority").getAsString());
        assertFalse(out.getAsJsonObject("applied").has("z"), "single-player is not special: " + out);
        assertTrue(out.getAsJsonObject("skipped").has("z"), out.toString());
        assertEquals(9.781176179073594, out.getAsJsonObject("pose").get("z").getAsDouble(), 1e-9);
        assertTrue(out.get("note").getAsString().contains("integrated server is authoritative"), out.toString());
    }

    @Test
    void aPosethatNeverSettlesReportsNothingAsApplied() {
        JsonObject out = poseSet(new LateRevertingClient(-4.5, 70.0, 7.1018, false), false);

        assertEquals(false, out.get("settled").getAsBoolean(), out.toString());
        assertEquals(0, out.getAsJsonObject("applied").size(),
                "settled=false must not report any field as applied: " + out);
        JsonObject skipped = out.getAsJsonObject("skipped");
        assertEquals(5, skipped.size(), out.toString());
        for (String f : List.of("x", "y", "z", "yaw", "pitch")) {
            assertEquals("not settled", skipped.getAsJsonObject(f).get("reason").getAsString(), out.toString());
        }
        assertTrue(out.get("note").getAsString().contains("did not settle"), out.toString());
    }

    // ---- mechanical self-consistency ---------------------------------------------------------
    @Test
    void authorityAndNoteCannotContradictTheVerdict() {
        record Case(boolean singleplayer, boolean settles, double z) {
        }
        for (Case c : List.of(new Case(true, true, 9.781176179073594), new Case(false, true, 7.1018),
                new Case(true, false, 2.0), new Case(false, false, 2.0))) {
            JsonObject out = poseSet(new LateRevertingClient(-4.5, 70.0, c.z(), c.settles()), c.singleplayer());
            boolean settled = out.get("settled").getAsBoolean();
            JsonObject applied = out.getAsJsonObject("applied");
            JsonObject skipped = out.getAsJsonObject("skipped");
            String note = out.get("note").getAsString();
            String authority = out.get("authority").getAsString();

            assertEquals(c.singleplayer(), authority.equals("client"), out.toString());
            if (!settled) {
                assertEquals(0, applied.size(), "unsettled ⇒ nothing applied: " + out);
                assertTrue(note.contains("did not settle"), note);
            } else {
                // Every requested field must be accounted for in exactly ONE of the three states, so
                // "did not take effect" (skipped) can never be confused with "the client cannot
                // witness it" (notClientVerifiable).
                JsonObject unverifiable = out.getAsJsonObject("notClientVerifiable");
                assertEquals(5, applied.size() + skipped.size() + unverifiable.size(), out.toString());
                for (String f : List.of("x", "y", "z", "yaw", "pitch")) {
                    int states = (applied.has(f) ? 1 : 0) + (skipped.has(f) ? 1 : 0)
                            + (unverifiable.has(f) ? 1 : 0);
                    assertEquals(1, states, f + " must appear in exactly one state: " + out);
                }
                assertEquals(applied.size() == 5, note.contains("every requested field took effect"), note);
                if (applied.size() < 5) {
                    assertTrue(note.contains("not applied: ") || note.contains("not client-verifiable"), note);
                }
            }
        }
    }
}
