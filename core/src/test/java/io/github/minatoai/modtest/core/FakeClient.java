package io.github.minatoai.modtest.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test double for {@link ClientModel}: enough state to make every executor rule observable.
 *
 * <p>Not {@code final}: individual tests subclass it to script one behaviour (a capture that times
 * out, a bench window that never fills) without rebuilding the whole adapter.
 */
class FakeClient implements ClientModel {
    double x;
    double y;
    double z = 64;
    float yaw;
    float pitch;
    String dimension = "minecraft:overworld";
    String held = "minecraft:stone";
    /** The off-hand item, so {@code use.item{hand:"off"}} and {@code state.query} can be checked. */
    String offHand = "";
    /**
     * Whether this client's container view may still be catching up with the authority.
     *
     * <p>{@code false} by default: the fake's state <i>is</i> exact, so a test that wants the P9 race
     * window sets this to {@code true} explicitly.
     */
    boolean containerSyncPending;
    boolean settled = true;
    boolean using;
    boolean useThrows;
    final List<String> slots = new ArrayList<>(List.of("minecraft:stone", "", "", "minecraft:torch"));
    final Set<String> occupied = new HashSet<>();
    final Set<String> placed = new HashSet<>();
    /** Block ids the fake world reports, keyed {@code "x,y,z"}; an unrecorded cell is air. */
    final java.util.Map<String, String> blocks = new java.util.HashMap<>();
    final List<String> screenshots = new ArrayList<>();
    int waitedFrames;
    int releaseCount;
    int teleports;

    /** A negative window id means "no container is open" — the same convention the protocol uses. */
    int windowId = -1;
    int cooldownTicks;
    /** Set when the held item is on cooldown but the adapter cannot say for how many ticks. */
    boolean onCooldown;
    int clickCalls;
    int tossCalls;
    int useCalls;
    int selectCalls;
    /** Placements that went through the player-interaction entry (what P11 requires). */
    int useItemOnCalls;
    /** Placements that wrote the world directly (what P11 forbids). */
    int directPlaceCalls;
    /** Which hand each accepted {@code use.item} asked for. */
    final List<Boolean> useOffHand = new ArrayList<>();

    /**
     * What {@link #clickSlot} does. {@code true} means "the click really changed the menu" (the
     * single-player case); {@code false} models a click the client dispatched but cannot observe.
     */
    boolean clickApplies = true;

    /** When set, {@link #tossSlot} drops this many instead of {@code count} (a partial toss). */
    Integer tossAppliesAtMost;

    /** Scripted capture/bench behaviour; {@code null} keeps the defaults below. */
    ClientModel.CapturedFrame nextFrame;
    ClientModel.BenchResult nextBench;

    FakeClient() {
    }

    /** A client with {@code slotCount} slots, so container-slot tests have a realistic inventory. */
    FakeClient(int slotCount) {
        slots.clear();
        for (int i = 0; i < slotCount; i++) {
            slots.add("");
        }
        slots.set(0, "minecraft:stone");
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
        return dimension;
    }

    @Override
    public String heldItemId() {
        return held;
    }

    @Override
    public String offHandItemId() {
        return offHand;
    }

    @Override
    public boolean containerSyncPending() {
        return containerSyncPending;
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
        selectCalls++;
        if (slot < 0 || slot >= slots.size()) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_EXEC, "slot out of range: " + slot);
        }
    }

    @Override
    public void clickSlot(int slot, int button, String mode) {
        clickCalls++;
        if (slot < 0 || slot >= slots.size()) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_EXEC, "slot out of range: " + slot);
        }
        if (!clickApplies) {
            return;   // the click was dispatched, but this client's menu shows nothing for it
        }
        // A scripted, observable effect: picking up from a non-empty slot empties it. The handler has
        // to read that change out of the client, never assume it from the request.
        if ("pickup".equals(mode) && !slots.get(slot).isEmpty()) {
            slots.set(slot, "");
        }
    }

    @Override
    public void tossSlot(int slot, int count) {
        tossCalls++;
        if (slot < 0 || slot >= slots.size() || slots.get(slot).isEmpty()) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_EXEC, "cannot toss from empty slot " + slot);
        }
        if (tossAppliesAtMost != null && tossAppliesAtMost <= 0) {
            return;   // the server refused the toss; the client's slot is unchanged
        }
        slots.set(slot, "");
    }

    @Override
    public void useItem() {
        useItem(false);
    }

    @Override
    public void useItem(boolean offHand) {
        useCalls++;
        useOffHand.add(offHand);
        if (useThrows) {
            throw new IllegalStateException("held item cannot be used");
        }
        using = true;
        cooldownTicks = Math.max(cooldownTicks, 5);
    }

    @Override
    public boolean itemOnCooldown() {
        return onCooldown;
    }

    @Override
    public boolean cellOccupied(int cx, int cy, int cz) {
        return occupied.contains(cx + "," + cy + "," + cz);
    }

    /**
     * The player-interaction placement entry — what {@code world.place} must use.
     *
     * <p>Modelled honestly: the fake requires the block to be the item in the selected slot, exactly as
     * the real interaction path does, and refuses otherwise.
     */
    @Override
    public void useItemOnBlock(int cx, int cy, int cz, String block) {
        useItemOnCalls++;
        if (held == null || held.isBlank()) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                    "no item in the selected slot: a real placement needs " + block + " in hand")
                    .with("reason", "empty-hand");
        }
        if (!held.equals(block)) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                    "the selected slot holds " + held + ", not " + block)
                    .with("reason", "held-item-mismatch");
        }
        // Recorded as an observed block, not as pre-existing occupancy: `cellOccupied` models the world
        // *before* the op, while `blockIdAt` reports what the client sees now (P10's read-back).
        blocks.put(cx + "," + cy + "," + cz, block);
        placed.add(cx + "," + cy + "," + cz + "=" + block);
    }

    /**
     * The legacy direct world write (P11). Production no longer has this path — {@link ClientModel}
     * refuses it by default and the Forge adapter does not implement it — but the fake keeps it so a test
     * can prove the op does <b>not</b> use it, and so the old behaviour can be reproduced on purpose.
     */
    @Override
    @SuppressWarnings("deprecation")   // deliberate: this is the bypass the P11 test must detect
    public void placeBlock(int cx, int cy, int cz, String block) {
        directPlaceCalls++;
        blocks.put(cx + "," + cy + "," + cz, block);
        placed.add(cx + "," + cy + "," + cz + "=" + block);
    }

    /**
     * The fake world does report block ids: a cell it never saw a placement for is air, which is a
     * reportable observation ({@code ""}), not "cannot witness" ({@code null}). Tests that model an
     * adapter with no block query override this to return {@code null}.
     */
    @Override
    public String blockIdAt(int cx, int cy, int cz) {
        String b = blocks.get(cx + "," + cy + "," + cz);
        return b == null ? "" : b;
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

    /** A tiny but real PNG byte string, so byte counts and digests are genuine, not made up. */
    static final byte[] SAMPLE_PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R',
            0, 0, 0, 2, 0, 0, 0, 3, 8, 6, 0, 0, 0, 0, 0, 0, 0};

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public CapturedFrame capture(String name) {
        if (nextFrame != null) {
            screenshots.add(name);
            return nextFrame;
        }
        screenshots.add(name);
        return new CapturedFrame(SAMPLE_PNG, "png", 2, 3, sha256Hex(SAMPLE_PNG), 4242L,
                "screenshots/" + name + ".png");
    }

    @Override
    public BenchResult benchWindow(int warmupFrames, int sampleFrames) {
        if (nextBench != null) {
            return nextBench;
        }
        int samples = Math.min(sampleFrames, 120);
        return new BenchResult(warmupFrames, sampleFrames, samples, 2_000L,
                144.5, 8.25, 96.0, "bench/samples-" + samples + ".csv");
    }

    @Override
    public void waitFrames(int frames) {
        waitedFrames += frames;
    }

    @Override
    public int windowId() {
        return windowId;
    }

    @Override
    public int cooldownTicks() {
        return cooldownTicks;
    }
}
