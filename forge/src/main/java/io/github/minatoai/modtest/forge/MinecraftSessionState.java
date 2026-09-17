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

    /**
     * The address the client actually connected to — the value the guard's host allow-list consumes.
     *
     * <p>Without this override the interface default (null) made "declared host ⇒ allow" dead code on
     * a real client: {@code permitsHost(null)} can never match, so a self-hosted dev server stayed
     * refused even with {@code MODTEST_ALLOWED_HOSTS} set.
     *
     * <p>Return values by connection state:
     * <ul>
     *   <li><b>single-player</b> → {@code null}: the policy allows single-player before consulting the
     *       allow-list, so no address is needed or invented.</li>
     *   <li><b>multiplayer/LAN</b> → the typed address, e.g. {@code 127.0.0.1:25585}; matching is
     *       host-only, so the port is ignored (declaring a host declares the machine).</li>
     *   <li><b>connected with no address available</b> → {@code "unknown"}: the policy turns that into
     *       an explicit "address unavailable — cannot verify ownership" refusal. Never guessed, never
     *       treated as an allowance.</li>
     * </ul>
     */
    @Override
    public String serverAddress() {
        var server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return server.ip;
        }
        if (mc.hasSingleplayerServer() && !connectedToRemoteServer()) {
            return null;
        }
        return "unknown";
    }
}
