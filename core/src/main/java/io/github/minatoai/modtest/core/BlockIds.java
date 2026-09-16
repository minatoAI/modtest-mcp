package io.github.minatoai.modtest.core;

import java.util.Locale;

/**
 * Block-id handling for {@code world.place}.
 *
 * <p>Kept in core (not in the Forge adapter) so the contract is testable without Minecraft: the
 * adapter resolves the returned id through its own registry, and a malformed id is rejected here
 * with {@code E_BAD_PARAMS} instead of silently becoming something else.
 */
public final class BlockIds {
    public static final String DEFAULT_BLOCK = "minecraft:stone";

    private BlockIds() {
    }

    /** Accepts {@code namespace:path} or a bare path (implicit {@code minecraft:}); lower-cases it. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw bad(raw, "block id is required");
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        String[] parts = id.split(":", -1);
        if (parts.length != 2
                || !parts[0].matches("[a-z0-9_.-]+")
                || !parts[1].matches("[a-z0-9_./-]+")) {
            throw bad(raw, "malformed block id");
        }
        return id;
    }

    private static Protocol.ProtocolException bad(String raw, String why) {
        return (Protocol.ProtocolException) new Protocol.ProtocolException(
                Protocol.ErrorCode.E_BAD_PARAMS, why + ": " + raw).with("path", "params.block");
    }
}
