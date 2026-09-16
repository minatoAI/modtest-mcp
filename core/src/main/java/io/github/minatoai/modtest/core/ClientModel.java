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

    // ---- interaction ----------------------------------------------------
    /** Use the held item. Implementations may refuse (already using, no item, wrong state). */
    void useItem();

    boolean cellOccupied(int x, int y, int z);

    void placeBlock(int x, int y, int z, String block);

    // ---- telemetry ------------------------------------------------------
    record BenchSample(double fpsMedian, double frameMsP95, double onePercentLow) {
    }

    String captureScreenshot(String name);

    BenchSample bench(int warmupFrames, int sampleFrames);

    void waitFrames(int frames);
}
