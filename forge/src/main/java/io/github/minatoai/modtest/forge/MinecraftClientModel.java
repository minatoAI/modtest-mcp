package io.github.minatoai.modtest.forge;

import io.github.minatoai.modtest.core.ClientModel;
import io.github.minatoai.modtest.core.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Forge implementation of {@link ClientModel}: everything the executor may change about the client
 * goes through here. Kept deliberately thin — the rules live in core, not in the adapter.
 */
public final class MinecraftClientModel implements ClientModel {
    private final Minecraft mc;

    public MinecraftClientModel(Minecraft mc) {
        this.mc = mc;
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
        return held.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM
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

    @Override
    public List<String> inventory() {
        List<String> out = new ArrayList<>();
        LocalPlayer p = player();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            out.add(s.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(s.getItem()).toString());
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

    @Override
    public void clickSlot(int slot, int button, String mode) {
        // Deliberately not implemented in Stage 2: container clicks need the menu-handling path and
        // a real-machine test. Refusing is better than guessing.
        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                "inv.click is not wired yet (needs a real-machine pass)");
    }

    @Override
    public void tossSlot(int slot, int count) {
        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                "inv.toss is not wired yet (needs a real-machine pass)");
    }

    @Override
    public void useItem() {
        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                "use.item is not wired yet (needs a real-machine pass)");
    }

    @Override
    public boolean cellOccupied(int x, int y, int z) {
        return !player().level().getBlockState(new BlockPos(x, y, z)).isAir();
    }

    @Override
    public void placeBlock(int x, int y, int z, String block) {
        player().level().setBlockAndUpdate(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
    }

    @Override
    public String captureScreenshot(String name) {
        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                "shot.capture is not wired yet (needs a real-machine pass)");
    }

    @Override
    public BenchSample bench(int warmupFrames, int sampleFrames) {
        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNSUPPORTED,
                "bench.read is not wired yet (needs a real-machine pass)");
    }

    @Override
    public void waitFrames(int frames) {
        // Tick-based waiting is driven by the relay loop; nothing to do synchronously here.
    }
}
