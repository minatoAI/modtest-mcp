package io.github.minatoai.modtest.forge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.minatoai.modtest.core.ClientModel;
import io.github.minatoai.modtest.core.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Forge implementation of {@link ClientModel}: everything the executor may change about the client
 * goes through here. Kept deliberately thin — the rules live in core, not in the adapter.
 *
 * <p>Three things are adapter decisions worth naming:
 * <ul>
 *   <li><b>Container clicks go through vanilla's own menu path</b>
 *       ({@code MultiPlayerGameMode.handleInventoryMouseClick}) with the {@link ClickType} the ticket
 *       named, so the client sends exactly the click a player would;</li>
 *   <li><b>{@code inv.toss} uses the drop click ({@link ClickType#THROW})</b> — the same request the
 *       drop key produces — never {@code player.drop(...)}, which removes items locally while the
 *       authority drops its own copy (the client and the server then disagree);</li>
 *   <li><b>frame telemetry is fed by a per-frame hook</b> ({@link #recordFrame}) and read from a ring
 *       buffer, because the relay runs ON the client/render thread: waiting for future frames from
 *       inside an op would deadlock the very thread that produces them.</li>
 * </ul>
 */
public final class MinecraftClientModel implements ClientModel {
    private final Minecraft mc;
    /** Where {@code shot.capture} writes its PNG, or {@code null} to keep the bytes in memory only. */
    private final Path recordingDir;

    public MinecraftClientModel(Minecraft mc) {
        this(mc, null);
    }

    public MinecraftClientModel(Minecraft mc, Path recordingDir) {
        this.mc = mc;
        this.recordingDir = recordingDir;
    }

    private LocalPlayer player() {
        LocalPlayer p = mc.player;
        if (p == null) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION, "no client player");
        }
        return p;
    }

    @Override
    public double x() {
        return player().getX();
    }

    @Override
    public double y() {
        return player().getY();
    }

    @Override
    public double z() {
        return player().getZ();
    }

    @Override
    public float yaw() {
        return player().getYRot();
    }

    @Override
    public float pitch() {
        return player().getXRot();
    }

    @Override
    public String dimension() {
        return player().level().dimension().location().toString();
    }

    @Override
    public String heldItemId() {
        ItemStack held = player().getMainHandItem();
        return held.isEmpty() ? "" : net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getKey(held.getItem()).toString();
    }

    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch, int settleMs) {
        player().moveTo(x, y, z, yaw, pitch);
        player().setYRot(yaw);
        player().setXRot(pitch);
    }

    @Override
    public boolean settled() {
        return true;
    }

    /**
     * Slot contents by index, with the stack size when it is more than one.
     *
     * <p>The count matters: {@code inv.toss} is judged from the drop it can observe, so an inventory
     * that reported every stack as one item would turn a whole-stack drop into {@code observedDelta:1}
     * and understate a correct toss. {@code heldItemId()} deliberately stays a bare id — the
     * {@code item} precondition compares it for equality.
     */
    @Override
    public List<String> inventory() {
        List<String> out = new ArrayList<>();
        LocalPlayer p = player();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) {
                out.add("");
                continue;
            }
            String id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem()).toString();
            out.add(s.getCount() > 1 ? id + " x" + s.getCount() : id);
        }
        return out;
    }

    @Override
    public boolean usingItem() {
        return player().isUsingItem();
    }

    @Override
    public void releaseUsingItem() {
        player().releaseUsingItem();
    }

    @Override
    public void selectSlot(int slot) {
        player().getInventory().selected = slot;
    }

    /**
     * The open container's window id, or {@code -1} when only the always-present player inventory menu
     * is open.
     *
     * <p>Reporting {@code 0} for the inventory menu would tell core "a container is open" and let a
     * click target any slot; {@code -1} is the convention core uses for "no container", and it still
     * permits the hotbar/armor slots.
     */
    @Override
    public int windowId() {
        LocalPlayer p = mc.player;
        if (p == null || p.containerMenu == null || p.containerMenu == p.inventoryMenu) {
            return -1;
        }
        return p.containerMenu.containerId;
    }

    /** The window a click must name: the open container, or 0 for the player inventory. */
    private int clickWindow(LocalPlayer p) {
        AbstractContainerMenu menu = p.containerMenu;
        if (menu == null) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION, "no open menu");
        }
        return menu == p.inventoryMenu ? 0 : menu.containerId;
    }

    @Override
    public void clickSlot(int slot, int button, String mode) {
        LocalPlayer p = player();
        mc.gameMode.handleInventoryMouseClick(clickWindow(p), slot, button, clickType(mode), p);
    }

    /** The closed ClickType vocabulary of {@code inv.click}, mapped one-to-one. */
    private static ClickType clickType(String mode) {
        return switch (mode) {
            case "pickup" -> ClickType.PICKUP;
            case "quick_move" -> ClickType.QUICK_MOVE;
            case "swap" -> ClickType.SWAP;
            case "clone" -> ClickType.CLONE;
            case "throw" -> ClickType.THROW;
            case "quick_craft" -> ClickType.QUICK_CRAFT;
            case "pickup_all" -> ClickType.PICKUP_ALL;
            default -> throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                    "unknown click mode: " + mode);
        };
    }

    /**
     * Drop items with the vanilla drop click ({@link ClickType#THROW}): button 1 is the whole stack,
     * button 0 is one item. A {@code count} that is neither one nor the whole stack therefore lands as
     * a partial drop, which core reports as {@code partial:true} rather than pretending otherwise.
     */
    @Override
    public void tossSlot(int slot, int count) {
        LocalPlayer p = player();
        int inSlot = stackCount(p, slot);
        if (inSlot <= 0) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                    "slot " + slot + " is empty");
        }
        int button = count >= inSlot ? 1 : 0;
        mc.gameMode.handleInventoryMouseClick(clickWindow(p), slot, button, ClickType.THROW, p);
    }

    private static int stackCount(LocalPlayer p, int slot) {
        AbstractContainerMenu menu = p.containerMenu;
        if (menu == null || slot < 0 || slot >= menu.slots.size()) {
            return 0;
        }
        return menu.getSlot(slot).getItem().getCount();
    }

    @Override
    public void useItem() {
        useItem(false);
    }

    @Override
    public void useItem(boolean offHand) {
        LocalPlayer p = player();
        mc.gameMode.useItem(p, offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
    }

    /**
     * {@code 0}: Minecraft's {@code ItemCooldowns} exposes the remaining <i>fraction</i>, not ticks, so
     * this adapter cannot answer in ticks and does not invent a number. {@link #itemOnCooldown()}
     * carries what it does know.
     */
    @Override
    public int cooldownTicks() {
        return 0;
    }

    @Override
    public boolean itemOnCooldown() {
        LocalPlayer p = mc.player;
        if (p == null) {
            return false;
        }
        ItemStack held = p.getMainHandItem();
        return !held.isEmpty() && p.getCooldowns().isOnCooldown(held.getItem());
    }

    @Override
    public boolean cellOccupied(int x, int y, int z) {
        return !player().level().getBlockState(new BlockPos(x, y, z)).isAir();
    }

    @Override
    public void placeBlock(int x, int y, int z, String block) {
        // Honour the requested block instead of silently placing stone: the ticket contract says
        // `world.place {block}`, so an unknown id is an error, not a substitution.
        String id = io.github.minatoai.modtest.core.BlockIds.normalize(block);
        net.minecraft.world.level.block.Block target =
                net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
                        net.minecraft.resources.ResourceLocation.tryParse(id));
        if (target == null || target == Blocks.AIR) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                    "unknown block: " + id);
        }
        player().level().setBlockAndUpdate(new BlockPos(x, y, z), target.defaultBlockState());
    }

    // ---- frame telemetry ------------------------------------------------------------------------

    /** How many frame durations are kept; enough for the largest window core allows (900) plus slack. */
    private static final int MAX_FRAMES = 1200;
    private static final double[] FRAME_MS = new double[MAX_FRAMES];
    private static int frameHead;
    private static int frameCount;
    private static long lastFrameNanos;

    /**
     * Records one rendered frame's duration. Called from the mod's per-frame event on the render
     * thread; the buffer is what {@link #benchWindow} reads, so no op ever waits for a future frame.
     */
    public static void recordFrame(long nowNanos) {
        synchronized (FRAME_MS) {
            if (lastFrameNanos != 0 && nowNanos > lastFrameNanos) {
                FRAME_MS[frameHead] = (nowNanos - lastFrameNanos) / 1_000_000.0;
                frameHead = (frameHead + 1) % MAX_FRAMES;
                if (frameCount < MAX_FRAMES) {
                    frameCount++;
                }
            }
            lastFrameNanos = nowNanos;
        }
    }

    /** The most recent {@code want} frame durations, oldest first. */
    private static double[] recentFrames(int want) {
        synchronized (FRAME_MS) {
            int n = Math.min(want, frameCount);
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                out[i] = FRAME_MS[(frameHead - n + i + MAX_FRAMES) % MAX_FRAMES];
            }
            return out;
        }
    }

    /**
     * The most recent rendered frames, measured.
     *
     * <p>This is where the adapter diverges from a literal reading of {@code bench.read}: it reports the
     * window of frames that have <b>already been rendered</b>, because the op runs on the render thread
     * and waiting for future frames would deadlock. A request for more frames than have been observed
     * is reported with a shorter {@code sampleCount} (core says so in the note); a request when nothing
     * has been rendered at all is {@code E_PRECONDITION}, never invented numbers.
     */
    @Override
    public BenchResult benchWindow(int warmupFrames, int sampleFrames) {
        double[] frames = recentFrames(sampleFrames);
        if (frames.length == 0) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                    "no rendered frames have been observed yet (minimised, paused, or not in a world)");
        }
        double[] sorted = frames.clone();
        Arrays.sort(sorted);
        double windowMs = 0;
        for (double f : frames) {
            windowMs += f;
        }
        double median = sorted[sorted.length / 2];
        double p95 = percentile(sorted, 0.95);
        double p99 = percentile(sorted, 0.99);
        String samplesPath = writeSamples(frames);
        return new BenchResult(warmupFrames, sampleFrames, frames.length, Math.round(windowMs),
                1000.0 / Math.max(median, 1.0e-6), p95, 1000.0 / Math.max(p99, 1.0e-6), samplesPath);
    }

    private static double percentile(double[] sorted, double q) {
        int index = Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1);
        return sorted[Math.max(0, index)];
    }

    /** The per-frame samples, so the numbers can be re-checked. {@code null} when no dir was given. */
    private String writeSamples(double[] frames) {
        if (recordingDir == null) {
            return null;
        }
        try {
            Files.createDirectories(recordingDir);
            Path file = recordingDir.resolve("bench-" + System.currentTimeMillis() + ".csv");
            StringBuilder csv = new StringBuilder("frame,frame_ms\n");
            for (int i = 0; i < frames.length; i++) {
                csv.append(i).append(',').append(frames[i]).append('\n');
            }
            Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            // The measurement itself is still valid; only its paper trail is missing, and the receipt
            // says so by carrying no path.
            return null;
        }
    }

    /**
     * One rendered frame's bytes, read from the framebuffer <b>on this thread</b>.
     *
     * <p>{@link Screenshot#takeScreenshot} is the frame-internal read (the relay runs from the per-tick
     * event, i.e. on the render thread) and returns the image synchronously: there is no waiting and no
     * frame-external {@code Screenshot.grab(...)} file path involved. A client that is not rendering has
     * no render target and is refused; a failure to read the pixels is {@code E_EXEC}, never a blank
     * image reported as success.
     */
    @Override
    public CapturedFrame capture(String name) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget target = minecraft.getMainRenderTarget();
        if (target == null || target.width <= 0 || target.height <= 0) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                    "no render target is available (minimised, paused, or not in a world)");
        }
        NativeImage image;
        try {
            image = Screenshot.takeScreenshot(target);
        } catch (RuntimeException e) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_EXEC,
                    "frame capture failed: " + e);
        }
        if (image == null) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                    "no frame is available to capture (minimised, paused, or not in a world)");
        }
        try {
            byte[] bytes;
            try {
                bytes = image.asByteArray();
            } catch (IOException e) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_EXEC,
                        "the captured frame could not be encoded: " + e);
            }
            if (bytes == null || bytes.length == 0) {
                throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                        "the captured frame produced no bytes");
            }
            return new CapturedFrame(bytes, "png", image.getWidth(), image.getHeight(), sha256(bytes),
                    tick(), writePng(name, bytes));
        } finally {
            image.close();
        }
    }

    /** Writes the PNG under the recording dir, sanitising the ticket-supplied name into one segment. */
    private String writePng(String name, byte[] bytes) {
        if (recordingDir == null) {
            return null;
        }
        String safe = name == null ? "shot" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) {
            safe = "shot";
        }
        try {
            Files.createDirectories(recordingDir);
            Path file = recordingDir.resolve(safe.endsWith(".png") ? safe : safe + ".png");
            Files.write(file, bytes);
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            return null;
        }
    }

    private long tick() {
        return mc.level == null ? 0L : mc.level.getGameTime();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_EXEC, "sha256 unavailable: " + e);
        }
    }

    /**
     * Legacy entry point, replaced by {@link #capture}. Kept refusing rather than returning a path with
     * no byte facts attached, which is exactly the over-claim {@code shot.capture} forbids.
     */
    @Override
    public String captureScreenshot(String name) {
        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                "the legacy screenshot path is gone: shot.capture reports bytes via capture(String)");
    }

    /** Legacy entry point, replaced by {@link #benchWindow}. */
    @Override
    public BenchSample bench(int warmupFrames, int sampleFrames) {
        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                "the legacy bench path is gone: bench.read uses benchWindow(int, int)");
    }

    @Override
    public void waitFrames(int frames) {
        // Deliberately nothing: the relay runs on the render thread, so "waiting" here would block the
        // tick loop that calls back into the relay (and the frame hook that feeds benchWindow).
    }
}
