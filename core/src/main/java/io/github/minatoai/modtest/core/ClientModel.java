package io.github.minatoai.modtest.core;

import java.util.List;

/**
 * The client abstraction the executor drives.
 *
 * <p>The protocol core never talks to Minecraft directly: a mod adapter implements this interface
 * (the Forge glue in `forge/` does), and tests implement it with a fake. Everything the executor
 * can change about the game goes through here, which keeps the safety policy (see {@link Guard})
 * in one place.
 */
public interface ClientModel {

    // ---- state ----------------------------------------------------------
    double x();

    double y();

    double z();

    float yaw();

    float pitch();

    String dimension();

    String heldItemId();

    /** Teleport/rotate and run the settle loop; {@link #settled()} reports whether it converged. */
    void teleport(double x, double y, double z, float yaw, float pitch, int settleMs);

    boolean settled();

    // ---- inventory ------------------------------------------------------
    /** Slot contents by index; {@code ""} means empty. Armor slots are 36..39, offhand 40. */
    List<String> inventory();

    boolean usingItem();

    void releaseUsingItem();

    void selectSlot(int slot);

    void clickSlot(int slot, int button, String mode);

    void tossSlot(int slot, int count);

    /**
     * The open container's window id, or {@code -1} when no container is open.
     *
     * <p>Part of the core-observable surface of {@code inv.click}/{@code inv.toss}: a click must name
     * the window it targets, and a stale window id is a precondition failure rather than a silent
     * no-op. Defaults to {@code -1} so an adapter that has not wired it yet reports "no container".
     */
    default int windowId() {
        return -1;
    }

    /** Remaining item-cooldown ticks; {@code 0} when usable. Informational for {@code use.item}. */
    default int cooldownTicks() {
        return 0;
    }

    // ---- interaction ----------------------------------------------------
    /** Use the held item. Implementations may refuse (already using, no item, wrong state). */
    void useItem();

    /**
     * Use the item in the requested hand.
     *
     * <p>{@code use.item} takes a {@code hand} parameter, so an adapter that can honour it must
     * override this; the default keeps every existing implementation (and test double) working by
     * falling back to the main hand. Without this, {@code hand:"off"} would be accepted by the guard
     * and then silently dispatched from the wrong hand.
     */
    default void useItem(boolean offHand) {
        useItem();
    }

    /**
     * Whether the held item is currently on cooldown.
     *
     * <p>Separate from {@link #cooldownTicks()} because an adapter may know "on cooldown" without
     * knowing the exact remainder (Minecraft's {@code ItemCooldowns} exposes a percentage, not ticks).
     * The default is {@code false}: an adapter that cannot tell must not block a use it cannot judge.
     */
    default boolean itemOnCooldown() {
        return false;
    }

    boolean cellOccupied(int x, int y, int z);

    void placeBlock(int x, int y, int z, String block);

    // ---- telemetry ------------------------------------------------------
    record BenchSample(double fpsMedian, double frameMsP95, double onePercentLow) {
    }

    /**
     * The <b>byte-level facts</b> of one captured frame.
     *
     * <p>Deliberately carries no interpretation of the image: {@code shot.capture} may only report
     * "these bytes exist", never "the scene looks like X". {@code sha256} is the lowercase hex digest
     * of {@code bytes}.
     */
    record CapturedFrame(byte[] bytes, String format, int width, int height, String sha256, long tick,
                         String path) {
    }

    /**
     * One frame-time sampling window, as actually measured.
     *
     * <p>Deliberately carries the <b>window</b> ({@code warmupFrames}/{@code sampleFrames}/
     * {@code sampleCount}/{@code windowMs}), the unitless-plus-united values and the per-frame sample
     * file, because a bare "fps" number without a window is not interpretable.
     */
    record BenchResult(int warmupFrames, int sampleFrames, int sampleCount, long windowMs,
                       double fpsMedian, double frameMsP95, double onePercentLow, String samplesPath) {
    }

    /** Legacy screenshot entry point: returns a path only, with no byte facts attached. */
    String captureScreenshot(String name);

    BenchSample bench(int warmupFrames, int sampleFrames);

    /**
     * Capture one rendered frame and report its bytes. Default: refuse, because an adapter that has
     * not implemented it must not hand back an empty image as if it were a capture.
     */
    default CapturedFrame capture(String name) {
        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                "frame capture is not implemented by this client adapter");
    }

    /**
     * Sample this client's frame durations. Default: refuse, because a receipt must never carry
     * invented numbers.
     */
    default BenchResult benchWindow(int warmupFrames, int sampleFrames) {
        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                "frame-time sampling is not implemented by this client adapter");
    }

    void waitFrames(int frames);
}
