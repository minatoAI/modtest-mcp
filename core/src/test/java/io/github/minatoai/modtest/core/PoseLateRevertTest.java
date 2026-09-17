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
 * slice 1 of task-66: the pose verdict must be confirmed by TWO independent readings.
 *
 * <p>Real-machine measurement: the position set by {@code pose.set} is pulled back by the authority
 * within ~0.5 s, i.e. <b>after</b> the settle window the previous version read in — so a single
 * read-back still caught the transient value and reported "applied". The fake below reverts on tick 5,
 * later than any single read the previous implementation took, so this test <b>fails against it</b>.
 */
class PoseLateRevertTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Bridge.Clock CLOCK = Bridge.Clock.fixed(NOW);

    /** Transient pose visible until tick 5, then the authority publishes its own pose again. */
    static class LateRevertClient implements ClientModel {
        private final double sx;
        private final double sy;
        private final double sz;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        private int transientTicks;
        private int elapsed;
        private final boolean sticks;

        LateRevertClient(double sx, double sy, double sz, boolean sticks) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.sticks = sticks;
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
            x = nx;
            y = ny;
            z = nz;
            yaw = nyaw;
            pitch = npitch;
            transientTicks = sticks ? 0 : 5;   // the authority pulls the position back at tick 5
        }

        @Override
        public void waitFrames(int frames) {
            for (int i = 0; i < frames; i++) {
                elapsed++;
                if (transientTicks > 0 && --transientTicks == 0) {
                    x = sx;
                    y = sy;
                    z = sz;   // rotation is client-side and survives
                }
            }
        }

        @Override
        public boolean settled() {
            return elapsed >= 4;   // the settle window the previous implementation trusted
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

    /** x=3/y=71/z=12 sticks; the (2000,121,12) request below does not. */
    private static final JsonObject REQUEST = JsonParser.parseString(
            "{\"x\":2000,\"y\":121,\"z\":12,\"yaw\":0,\"pitch\":0}").getAsJsonObject();

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

    @Test
    void aRevertAfterTheSettleWindowIsNotReportedAsApplied() {
        // Single-player: the integrated server is authoritative too (real run D).
        JsonObject out = poseSet(new LateRevertClient(3.0, 71.0, 10.699965724359156, false), true);

        assertEquals(false, out.get("confirmed").getAsBoolean(),
                "a value that changed between the two readings is not confirmed: " + out);
        JsonObject applied = out.getAsJsonObject("applied");
        JsonObject skipped = out.getAsJsonObject("skipped");
        for (String f : List.of("x", "y", "z")) {
            assertFalse(applied.has(f), f + " must not be applied (single reading was the transient one): " + out);
            assertTrue(skipped.has(f), out.toString());
            assertEquals("not confirmed stable", skipped.getAsJsonObject(f).get("reason").getAsString(),
                    out.toString());
            assertTrue(skipped.getAsJsonObject(f).has("firstReading"), out.toString());
        }
        assertTrue(out.get("note").getAsString().contains("two independent readings agree"), out.toString());
    }

    @Test
    void aPoseConfirmedByTwoEqualReadingsIsReportedAsApplied() {
        // The request matches the authoritative pose, so nothing pulls it back: two readings agree.
        JsonObject request = JsonParser.parseString(
                "{\"x\":3,\"y\":71,\"z\":12,\"yaw\":0,\"pitch\":0}").getAsJsonObject();
        LateRevertClient client = new LateRevertClient(3.0, 71.0, 12.0, true);
        Bridge.BridgeConfig cfg = new Bridge.BridgeConfig(Path.of("."), 500L, "exec-test",
                "1.0.0-alpha.1", true, Bridge.BusyPolicy.ANSWER_BUSY, 64);
        JsonObject out = new VanillaOps.PoseOps().handle(new Protocol.Ticket.Op("p1", "pose.set", request,
                        null, null, null),
                new Executor.ExecContext(cfg, Guard.SessionState.singleplayer(),
                        new Guard.ActivationState(true, new Guard.ActivationToken("tok", NOW + 60_000L)),
                        CLOCK, Map.of("allow-mutate", true), client));

        assertEquals(true, out.get("confirmed").getAsBoolean(), out.toString());
        assertEquals(5, out.getAsJsonObject("applied").size(), out.toString());
        assertEquals(0, out.getAsJsonObject("skipped").size(), out.toString());
        assertTrue(out.get("note").getAsString().contains("every requested field took effect"), out.toString());
    }
}
