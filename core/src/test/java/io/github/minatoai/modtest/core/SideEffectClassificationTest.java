package io.github.minatoai.modtest.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 must be an <b>allow-list of read-only effects with everything else denied</b>, never "here is
 * the list of effects we currently believe are writes".
 *
 * <p>Why this class exists. The first task-70 revision classified with a private list of <i>mutating</i>
 * values and allowed anything absent from it. That fails <i>open</i>: a side-effect value added to the
 * vocabulary later is absent from the list, so it would be waved through tier 1 — ungated, unaudited,
 * with nothing failing. The count assertions elsewhere
 * ({@code SideEffect.values().length == 9}) are not a classification: bumping 9 to 10 is exactly what
 * an author adding a value does, and it would leave the new value read-only.
 *
 * <p>These tests pin the allow-list <b>by name</b> (so granting an effect tier 1 is a deliberate edit
 * here) and walk every value of the vocabulary (so the production rule is proven to be
 * "absent from the read-only list ⇒ write").
 */
class SideEffectClassificationTest {

    private static final long NOW = 1_700_000_000_000L;

    private static Protocol.OpSpec probe(Protocol.SideEffect... effects) {
        return new Protocol.OpSpec("probe", "probe", Json.object(), Json.object(), List.of(),
                List.of(effects), "classification-test", null, "1.0");
    }

    private static Guard.InputInjectionPolicy policy() {
        return new Guard.InputInjectionPolicy(Guard.HostWhitelist.of("127.0.0.1"),
                Guard.BuildVariant.GUARDED, "classification-test", () -> "minecraft:overworld");
    }

    private static Set<Protocol.SideEffect> declaredReadOnly() {
        Set<Protocol.SideEffect> readOnly = new LinkedHashSet<>();
        readOnly.add(Protocol.SideEffect.NONE);
        readOnly.add(Protocol.SideEffect.TELEMETRY_RECORDING);
        readOnly.add(Protocol.SideEffect.RENDER_PIPELINE);
        return readOnly;
    }

    @Test
    void theReadOnlyEffectsAreAnExplicitAllowList() {
        assertEquals(declaredReadOnly(), Guard.InputInjectionPolicy.READ_ONLY_EFFECTS,
                "tier 1 is an allow-list of read-only effects: an effect in this set runs ungated, so "
                        + "adding one is a safety decision that must be made here, explicitly");
    }

    @Test
    void everyValueOfTheVocabularyIsClassifiedByTheReadOnlyAllowList() {
        Set<Protocol.SideEffect> readOnly = Guard.InputInjectionPolicy.READ_ONLY_EFFECTS;
        Set<Protocol.SideEffect> walked = new LinkedHashSet<>();
        Guard.InputInjectionPolicy policy = policy();

        for (Protocol.SideEffect effect : Protocol.SideEffect.values()) {
            walked.add(effect);
            Protocol.OpSpec op = probe(effect);
            boolean mutating = Guard.InputInjectionPolicy.mutatesThePlayer(op);

            assertEquals(!readOnly.contains(effect), mutating,
                    effect + " must be classified by the read-only allow-list. A value in neither list "
                            + "would otherwise count as read-only and run through tier 1 unguarded.");

            Guard.Decision decision = policy.decide(op, Guard.SessionState.singleplayer(),
                    Guard.ActivationState.off(), Bridge.Clock.fixed(NOW));
            if (mutating) {
                assertFalse(decision.allowed(),
                        effect + " is a write and must be gated: " + decision.reason());
            } else {
                assertTrue(decision.allowed(),
                        effect + " is read-only and must not be gated: " + decision.reason());
            }
        }

        // Walking the enum, not a hand-written list, so a value added later cannot be skipped silently.
        assertEquals(new LinkedHashSet<>(List.of(Protocol.SideEffect.values())), walked,
                "every value of the vocabulary must have been classified");
    }

    @Test
    void theClassificationDidNotMoveForAnyExistingValue() {
        // The fix inverted the rule (mutating list -> read-only allow-list). Every value that already
        // existed must keep exactly the verdict it had, so this batch changes no existing behaviour.
        for (Protocol.SideEffect effect : Protocol.SideEffect.values()) {
            assertEquals(!declaredReadOnly().contains(effect),
                    Guard.InputInjectionPolicy.mutatesThePlayer(probe(effect)),
                    effect.name() + " changed classification");
        }
        for (Protocol.SideEffect write : List.of(
                Protocol.SideEffect.PLAYER_INPUT, Protocol.SideEffect.PLAYER_INVENTORY,
                Protocol.SideEffect.PLAYER_STATE, Protocol.SideEffect.WORLD_BLOCKS,
                Protocol.SideEffect.WORLD_ENTITIES, Protocol.SideEffect.SERVER_COMMAND)) {
            assertTrue(Guard.InputInjectionPolicy.mutatesThePlayer(probe(write)),
                    write + " must stay a write");
        }
    }
}
