package io.github.minatoai.modtest.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code world.place} block-id contract: the parameter is honoured, not substituted. */
class BlockIdsTest {

    @Test
    void acceptsNamespacedIds() {
        assertEquals("minecraft:stone", BlockIds.normalize("minecraft:stone"));
        assertEquals("minecraft:oak_planks", BlockIds.normalize("minecraft:oak_planks"));
        assertEquals("taclight:light_block", BlockIds.normalize("taclight:light_block"));
    }

    @Test
    void assumesTheMinecraftNamespaceAndLowerCases() {
        assertEquals("minecraft:stone", BlockIds.normalize("STONE"));
        assertEquals("minecraft:stone", BlockIds.normalize("  Stone "));
    }

    @Test
    void rejectsMalformedIdsWithBadParams() {
        for (String raw : new String[]{null, "", "   ", "a:b:c", "Mine Craft:stone", "minecraft:", ":stone",
                "minecraft:Stone!", "mine craft:stone"}) {
            Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                    () -> BlockIds.normalize(raw), "should reject: " + raw);
            assertEquals(Protocol.ErrorCode.E_BAD_PARAMS, e.code());
        }
    }

    @Test
    void defaultBlockIsStoneSoTheOldBehaviourStaysExpressible() {
        assertEquals("minecraft:stone", BlockIds.DEFAULT_BLOCK);
        assertTrue(BlockIds.normalize(BlockIds.DEFAULT_BLOCK).equals("minecraft:stone"));
    }
}
