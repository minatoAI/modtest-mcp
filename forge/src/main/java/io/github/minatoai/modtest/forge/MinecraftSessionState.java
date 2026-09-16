package io.github.minatoai.modtest.forge;

import io.github.minatoai.modtest.core.Guard;
import net.minecraft.client.Minecraft;

/** Reads the *real* client state so {@link Guard.InputInjectionPolicy} can decide. */
public final class MinecraftSessionState implements Guard.SessionState {
    private final Minecraft mc;

    public MinecraftSessionState(Minecraft mc) {
        this.mc = mc;
    }

    @Override
    public boolean hasIntegratedServer() {
        return mc.hasSingleplayerServer();
    }

    @Override
    public boolean connectedToRemoteServer() {
        // Any non-integrated connection (including LAN-opened worlds) is treated as remote for the
        // purpose of input injection: the guard must fail closed, never open.
        return mc.getCurrentServer() != null || (!mc.hasSingleplayerServer() && mc.level != null);
    }

    @Override
    public boolean paused() {
        return mc.isPaused();
    }

    @Override
    public boolean inWorld() {
        return mc.level != null && mc.player != null;
    }

    @Override
    public boolean handshakeInProgress() {
        return mc.getConnection() == null;
    }
}
