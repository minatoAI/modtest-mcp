package io.github.minatoai.modtest.forge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.minatoai.modtest.core.BlockIds;
import io.github.minatoai.modtest.core.ClientModel;
import io.github.minatoai.modtest.core.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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
 *       inside an op would deadlock the very thread that produces them;</li>
 *   <li><b>container reads carry a sync window</b> ({@link #containerSyncPending()}): right after a join,
 *       a world change, or a click this client dispatched, the menu may not have caught up, so core
 *       reports "cannot determine" instead of "the slot is empty" / "skipped" — qa-tester measured both
 *       wrong answers on a real client (P9).</li>
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

    // ---- container synchronisation window -------------------------------------------------------

    /**
     * How long after a join, a world change, or a click/toss this client may keep showing container
     * contents that the authority has already changed (and vice versa).
     *
     * <p>This exists because of a measured defect (P9): the first {@code inv.toss} after joining
     * reported "slot 0 is empty" while the item was still there a second later, and an {@code inv.click}
     * reported {@code verdict:"skipped"} while the click had in fact worked. Both are the same mistake —
     * treating one early read as a fact. This adapter therefore <b>cannot vouch</b> for its container
     * view inside the window, and core turns that into "cannot determine" instead of "empty"/"skipped".
     *
     * <p>The window is a heuristic (the real condition is "the server's answer has not arrived yet",
     * which vanilla exposes no flag for); it is set generously rather than tightly, because a false
     * "cannot determine" costs one extra read while a false "empty" costs a wrong decision.
     */
    private static final long CONTAINER_SYNC_WINDOW_MS = 3_000L;

    /** When the current level was first seen (join / dimension change), in {@code System.currentTimeMillis()}. */
    private static long levelFirstSeenMs = -1L;
    /** The level instance the timestamp above belongs to; identity comparison, so no equals() semantics. */
    private static Object stampedLevel;
    /** When this adapter last dispatched a container click/toss. */
    private static long lastContainerTouchMs = -1L;

    /** Records that a container-affecting action was just dispatched, opening the sync window. */
    private static void noteContainerTouch() {
        lastContainerTouchMs = System.currentTimeMillis();
    }

    @Override
    public boolean containerSyncPending() {
        long now = System.currentTimeMillis();
        if (mc.player == null || mc.level == null) {
            return true;   // nothing about the container is observable yet
        }
        if (stampedLevel != mc.level) {
            // First observation of this level: a join or a dimension change. Treat the moment it is
            // first *seen* as the start of the window, so the first ops after joining are covered even
            // though the level object itself may be older.
            stampedLevel = mc.level;
            levelFirstSeenMs = now;
        }
        boolean freshLevel = levelFirstSeenMs < 0 || now - levelFirstSeenMs < CONTAINER_SYNC_WINDOW_MS;
        boolean freshTouch = lastContainerTouchMs >= 0 && now - lastContainerTouchMs < CONTAINER_SYNC_WINDOW_MS;
        return freshLevel || freshTouch;
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

    /**
     * The off-hand item, so {@code use.item{hand:"off"}} reports the hand it actually acted on and
     * {@code state.query{what:["offhand"]}} can be used to verify the dispatch (qa-tester could not
     * verify an off-hand use before this existed).
     */
    @Override
    public String offHandItemId() {
        ItemStack off = player().getOffhandItem();
        return off.isEmpty() ? "" : net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getKey(off.getItem()).toString();
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

    /**
     * Select a hotbar slot <b>the way the hotbar keys do</b>: set the local index <i>and</i> tell the
     * server.
     *
     * <p>Audited as part of P11 (same family: a direct state write that skips the real path). Setting
     * {@code inventory.selected} alone leaves the server on the old slot — the server learns the carried
     * slot only from {@code ServerboundSetCarriedItemPacket} (its listener method is
     * {@code handleSetCarriedItem}), and the vanilla key path sends exactly that packet. That divergence
     * was also a correctness bug for the new {@code world.place}: the server decides <i>which item is in
     * hand</i> from its own view, so an unsynced selection would place the wrong item or refuse.
     */
    @Override
    public void selectSlot(int slot) {
        player().getInventory().selected = slot;
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
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
        noteContainerTouch();
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
            // Same rule as core's empty-slot refusal: inside the sync window this read cannot tell
            // "the slot is empty" from "the menu has not caught up".
            boolean unsynced = containerSyncPending();
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                    unsynced
                            ? "cannot determine whether slot " + slot + " is empty: the container has not "
                                    + "caught up with the authority yet"
                            : "slot " + slot + " is empty");
        }
        int button = count >= inSlot ? 1 : 0;
        mc.gameMode.handleInventoryMouseClick(clickWindow(p), slot, button, ClickType.THROW, p);
        noteContainerTouch();
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

    /**
     * The block id the client's own world currently shows at a position, or {@code ""} for air.
     *
     * <p>This is what makes {@code world.place}'s {@code placed} field a read-back instead of a
     * self-report (P10). It is the <b>client's</b> view: Minecraft applies the placement locally at
     * once ({@code ClientLevel.setBlockAndUpdate}) while the authority decides whether to keep it, so
     * core reports the observation but keeps the verdict {@code notClientVerifiable}.
     */
    @Override
    public String blockIdAt(int x, int y, int z) {
        var state = player().level().getBlockState(new BlockPos(x, y, z));
        if (state.isAir()) {
            return "";
        }
        return net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString();
    }

    /**
     * Place through the <b>real player interaction path</b> (P11).
     *
     * <p>The mod used to write the world directly ({@code ClientLevel#setBlockAndUpdate}). That skips the
     * interaction entirely: the server never runs its placement path, so Forge events, KubeJS
     * {@code BlockEvents.placed}, protection plugins and anti-cheat all see nothing — measured on a real
     * client, where {@code BlockEvents.placed} did not fire (the event type existed, the handler was
     * registered, and a positive control did fire, so this was the interaction, not the event).
     *
     * <p>This method instead does what a player's right-click does: take the item in the selected slot,
     * aim at the face of a neighbouring block, and call {@link MultiPlayerGameMode#useItemOn} — which
     * predicts locally and sends {@code ServerboundUseItemOnPacket}, so the server runs
     * {@code ServerPlayerGameMode.useItemOn} (registered as {@code handleUseItemOn} on the server
     * listener).
     *
     * <p>The consequences are enforced, not hidden: no item in the selected slot, a held item whose block
     * is not the requested one, a target that cannot be replaced, no supporting neighbour, or a target
     * outside {@link IForgePlayer#getBlockReach()} are all honest {@code E_PRECONDITION} refusals. The
     * block is never conjured into the world.
     */
    @Override
    public void useItemOnBlock(int x, int y, int z, String block) {
        LocalPlayer p = player();
        String id = BlockIds.normalize(block);

        ItemStack held = p.getMainHandItem();
        if (held.isEmpty()) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                    "no item in the selected slot: a real placement needs " + id + " in hand")
                    .with("reason", "empty-hand");
        }
        if (!(held.getItem() instanceof BlockItem blockItem)) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                    "the selected slot holds " + itemIdOf(held) + ", which is not a placeable block: "
                            + "world.place can only place the item the player is holding")
                    .with("reason", "held-item-not-a-block");
        }
        String heldBlockId = blockIdOf(blockItem.getBlock());
        if (!heldBlockId.equals(id)) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                    "the selected slot holds " + itemIdOf(held) + " (" + heldBlockId + "), not " + id
                            + ": a real placement can only place the item in hand — select the slot that "
                            + "holds " + id + " first")
                    .with("reason", "held-item-mismatch");
        }

        BlockPos target = new BlockPos(x, y, z);
        Level level = p.level();
        BlockState targetState = level.getBlockState(target);
        if (!targetState.canBeReplaced()) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                    "the target cell is occupied by " + blockIdOf(targetState.getBlock())
                            + " and cannot be replaced")
                    .with("reason", "target-not-replaceable");
        }

        // Aim at the face of a neighbouring solid block, exactly like a player standing next to it.
        double reach = p.getBlockReach();
        BlockPos support = null;
        Direction face = null;
        boolean anySolidNeighbour = false;
        for (Direction direction : Direction.values()) {
            BlockPos candidate = target.relative(direction.getOpposite());
            if (level.getBlockState(candidate).isAir()) {
                continue;
            }
            anySolidNeighbour = true;
            if (!p.canReach(Vec3.atCenterOf(candidate).relative(direction, 0.5), reach)) {
                continue;   // this face is too far; another one may still be in range
            }
            support = candidate;
            face = direction;
            break;
        }
        if (support == null) {
            throw new Protocol.ProtocolException(Protocol.ErrorCode.E_PRECONDITION,
                    anySolidNeighbour
                            ? "the target cell is out of block reach (" + reach + " blocks): a real "
                                    + "placement has to be aimed at from where the player stands"
                            : "the target cell has no neighbouring block to place against")
                    .with("reason", anySolidNeighbour ? "out-of-reach" : "no-support");
        }

        Vec3 hitPoint = Vec3.atCenterOf(support).relative(face, 0.5);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(hitPoint, face, support, false));
    }

    private static String itemIdOf(ItemStack stack) {
        ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? "(unregistered)" : key.toString();
    }

    private static String blockIdOf(Block block) {
        ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
        return key == null ? "(unregistered)" : key.toString();
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
