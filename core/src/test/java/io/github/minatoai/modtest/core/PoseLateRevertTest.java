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
 * task-66 P5 hardening: position is never judged from a client reading.
 *
 * <p>The previous slice required two agreeing readings, which is <b>not</b> authority evidence: the
 * authority pulls the pose back in &lt; 0.57 s (round 11), while two readings are only ~50 ms apart, so
 * both can land before the revert and agree on the transient value (round 12: {@code confirmed:true},
 * five fields applied, {@code dz=-4e-15}). The fake below therefore returns the <b>requested</b>
 * values for the first two readings and only reverts after them — the timing that broke that logic —
 * and the assertions require position to be skipped anyway.
 */
class PoseLateRevertTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Bridge.Clock CLOCK = Bridge.Clock.fixed(NOW);

    /** Requested pose visible past both readings; the authority publishes its own pose at tick 6. */
    static class LateRevertClient implements ClientModel {
        private final double sx;
        private final double sy;
        private final double sz;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        private int elapsed;

        LateRevertClient(double sx, double sy, double sz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
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
            x = nx;                 // the transient value an in-window reading sees
            y = ny;
            z = nz;
            yaw = nyaw;             // client-authoritative: survives the authority's update
            pitch = npitch;
            elapsed = 0;
        }

        @Override
        public void waitFrames(int frames) {
            for (int i = 0; i < frames; i++) {
                elapsed++;
                if (elapsed >= 6) {   // later than BOTH readings of the previous implementation
                    x = sx;
                    y = sy;
                    z = sz;
                }
            }
        }

        @Override
        public boolean settled() {
            return elapsed >= 4;      // the settle window the previous implementation trusted
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

    private static final JsonObject REQUEST = JsonParser.parseString(
            "{\"x\":2000,\"y\":121,\"z\":12,\"yaw\":180,\"pitch\":0}").getAsJsonObject();

    private static JsonObject poseSet(ClientModel client, boolean singleplayer) {
        Bridge.BridgeConfig cfg = new Bridge.BridgeConfig(Path.of("."), 500L, "exec-test",
                "1.0.0-alpha.1", true, Bridge.BusyPolicy.ANSWER_BUSY, 64);
        Executor.ExecContext ctx = new Executor.ExecContext(cfg,
                singleplayer ? Guard.SessionState.singleplayer() : Guard.SessionState.remote("127.0.0.1:25585"),
                new Guard.ActivationState(true, new Guard.ActivationToken("tok", NOW + 60_000L)),
                CLOCK, Map.of("allow-mutate", true), client);
        return new VanillaOps.PoseOps().handle(
                new Protocol.Ticket.Op("p1", "pose.set", REQUEST, null, null, null), ctx);
    }

    /**
     * Position is never `applied` on either session type — but the reason differs, and that difference
     * is what P8 was about: single-player means "did not take effect" (the client watches the revert),
     * while a remote authority may well have applied it without the client being able to witness it.
     */
    private static void assertPositionNotApplied(JsonObject out, String authority, boolean remote) {
        JsonObject applied = out.getAsJsonObject("applied");
        JsonObject target = out.getAsJsonObject(remote ? "notClientVerifiable" : "skipped");
        for (String f : List.of("x", "y", "z")) {
            assertFalse(applied.has(f), f + " must never be applied (the server owns position): " + out);
            assertTrue(target.has(f), f + " must be reported as "
                    + (remote ? "notClientVerifiable" : "skipped") + ": " + out);
        }
        assertFalse(out.has("confirmed"),
                "the misleading 'confirmed' field must be gone: " + out);
        assertTrue(out.get("note").getAsString().contains("client position readings cannot be authoritative"),
                out.toString());
        assertEquals(authority, out.get("authority").getAsString());
        if (remote) {
            assertEquals(0, out.getAsJsonObject("skipped").size(),
                    "a remote position must NOT be reported as 'skipped' (= did not take effect): " + out);
            assertEquals("the server owns the position; the client cannot witness whether it applied",
                    out.getAsJsonObject("notClientVerifiable").getAsJsonObject("z").get("reason").getAsString(),
                    out.toString());
        } else {
            assertEquals(0, out.getAsJsonObject("notClientVerifiable").size(),
                    "single-player can observe the revert, so nothing is merely unverifiable: " + out);
            assertEquals("server-authoritative position",
                    out.getAsJsonObject("skipped").getAsJsonObject("z").get("reason").getAsString(),
                    out.toString());
        }
    }

    @Test
    void singlePlayerPositionIsSkippedBecauseTheClientWatchesItRevert() {
        // Single-player (round 12): the receipt said every field applied while z never moved.
        JsonObject out = poseSet(new LateRevertClient(3.0, 71.0, 2.851360022260494), true);
        assertPositionNotApplied(out, "client", false);
        // Rotation IS client-authoritative, so the requested 180° is legitimately applied.
        assertTrue(out.getAsJsonObject("applied").has("yaw"), out.toString());
    }

    @Test
    void remotePositionIsNotClientVerifiableNotSkipped() {
        // Round 14 (LAN, 2 clients + dedicated server): the remote position change DID apply and
        // persist (16.4 s without reverting, survived a reconnect, confirmed by the server log
        // z=17.609… -> 12.0). Reporting that as "skipped" hid a working feature; it must be reported
        // as a state the client cannot witness, which is a different statement.
        JsonObject out = poseSet(new LateRevertClient(-4.5, 70.0, 7.1018), false);
        assertPositionNotApplied(out, "server", true);
        assertTrue(out.getAsJsonObject("applied").has("yaw"), out.toString());
    }
}
