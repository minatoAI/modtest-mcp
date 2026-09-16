package io.github.minatoai.modtest.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Test double for {@link ClientModel}: enough state to make every executor rule observable. */
final class FakeClient implements ClientModel {
    double x;
    double y;
    double z = 64;
    float yaw;
    float pitch;
    String dimension = "minecraft:overworld";
    String held = "minecraft:stone";
    boolean settled = true;
    boolean using;
    boolean useThrows;
    final List<String> slots = new ArrayList<>(List.of("minecraft:stone", "", "", "minecraft:torch"));
    final Set<String> occupied = new HashSet<>();
    final Set<String> placed = new HashSet<>();
    final List<String> screenshots = new ArrayList<>();
    int waitedFrames;
    int releaseCount;
    int teleports;

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
        return dimension;
    }

    @Override
    public String heldItemId() {
        return held;
    }

    @Override
    public void teleport(double nx, double ny, double nz, float nyaw, float npitch, int settleMs) {
        teleports++;
        x = nx;
        y = ny;
        z = nz;
        yaw = nyaw;
        pitch = npitch;
    }

    @Override
    public boolean settled() {
        return settled;
    }

    @Override
    public List<String> inventory() {
        return List.copyOf(slots);
    }

    @Override
    public boolean usingItem() {
        return using;
    }

    @Override
    public void releaseUsingItem() {
        releaseCount++;
        using = false;
    }

    @Override
    public void selectSlot(int slot) {
        if (slot < 0 || slot >= slots.size()) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_EXEC, "slot out of range: " + slot);
        }
    }

    @Override
    public void clickSlot(int slot, int button, String mode) {
        if (slot < 0 || slot >= slots.size()) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_EXEC, "slot out of range: " + slot);
        }
    }

    @Override
    public void tossSlot(int slot, int count) {
        if (slot < 0 || slot >= slots.size() || slots.get(slot).isEmpty()) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_EXEC, "cannot toss from empty slot " + slot);
        }
        slots.set(slot, "");
    }

    @Override
    public void useItem() {
        if (useThrows) {
            throw new IllegalStateException("held item cannot be used");
        }
        using = true;
    }

    @Override
    public boolean cellOccupied(int cx, int cy, int cz) {
        return occupied.contains(cx + "," + cy + "," + cz);
    }

    @Override
    public void placeBlock(int cx, int cy, int cz, String block) {
        placed.add(cx + "," + cy + "," + cz + "=" + block);
    }

    @Override
    public String captureScreenshot(String name) {
        screenshots.add(name);
        return "screenshots/" + name + ".png";
    }

    @Override
    public BenchSample bench(int warmupFrames, int sampleFrames) {
        return new BenchSample(144.5, 8.25, 96.0);
    }

    @Override
    public void waitFrames(int frames) {
        waitedFrames += frames;
    }
}
