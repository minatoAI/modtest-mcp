package io.github.minatoai.modtest.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The specification and the parser must agree on the side-effect vocabulary, mechanically.
 *
 * <p>They did not. {@code OpSpec.toJson()} emitted the enum name ({@code TELEMETRY_RECORDING}) while
 * {@code PROTOCOL.md §6.4} documented dotted lowercase ({@code telemetry.recording}) — and
 * {@code SideEffect.parse} accepted only the former, so a value copied straight out of the
 * specification was rejected with {@code E_BAD_PARAMS}. Documentation drifting from the parser is a
 * silent defect: nothing failed, because nothing compared them.
 *
 * <p>These tests compare them. The vocabulary section of the document is scanned for its backticked
 * tokens, every one of them must parse, and the set must be exactly the two spellings of every value —
 * so a row added to the table without teaching the parser, and a value added to the enum without a
 * documented row, both fail here.
 */
class SideEffectWireFormTest {

    /** Both spellings this specification documents, in §6.4's two columns. */
    private static final Pattern DOCUMENTED_TOKEN =
            Pattern.compile("`([A-Z][A-Z_]{1,}|[a-z]+(?:\\.[a-z]+)?)`");

    private static String protocolMarkdown() throws IOException {
        return Files.readString(locateProtocol(), StandardCharsets.UTF_8);
    }

    /** Gradle runs tests with the module dir as the working directory; tolerate either layout. */
    private static Path locateProtocol() {
        for (Path candidate : List.of(Path.of("docs", "PROTOCOL.md"),
                Path.of("..", "docs", "PROTOCOL.md"))) {
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
        }
        throw new AssertionError("docs/PROTOCOL.md not found from " + Path.of("").toAbsolutePath());
    }

    /** The §6.4 section: from its heading to the horizontal rule that closes it. */
    private static String sideEffectSection(String markdown) {
        int start = markdown.indexOf("### 6.4");
        assertTrue(start >= 0, "PROTOCOL.md no longer has a §6.4 section");
        int end = markdown.indexOf("\n---", start);
        assertTrue(end > start, "§6.4 is not terminated by a horizontal rule");
        return markdown.substring(start, end);
    }

    private static Set<String> expectedSpellings() {
        Set<String> expected = new LinkedHashSet<>();
        for (Protocol.SideEffect effect : Protocol.SideEffect.values()) {
            expected.add(effect.wireName());
            expected.add(effect.docName());
        }
        return expected;
    }

    @Test
    void bothDocumentedSpellingsParseToTheSameValue() {
        for (Protocol.SideEffect effect : Protocol.SideEffect.values()) {
            assertEquals(effect, Protocol.SideEffect.parse(effect.wireName()), effect.wireName());
            assertEquals(effect, Protocol.SideEffect.parse(effect.docName()), effect.docName());
            assertEquals(effect, Protocol.SideEffect.parse(effect.docName().toUpperCase(Locale.ROOT)),
                    "case-insensitive dotted form");
            assertEquals(effect, Protocol.SideEffect.parse(effect.docName().replace('.', '_')),
                    "underscore form");
            assertEquals(effect, Protocol.SideEffect.parse("  " + effect.docName() + "  "), "trimmed");
        }
    }

    @Test
    void theParserRefusesAValueOutsideTheVocabulary() {
        Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                () -> Protocol.SideEffect.parse("player.teleport"));
        assertEquals(Protocol.ErrorCode.E_BAD_PARAMS, e.code());
    }

    @Test
    void theCatalogEmitsTheCanonicalWireForm() {
        for (Protocol.SideEffect effect : Protocol.SideEffect.values()) {
            Protocol.OpSpec spec = new Protocol.OpSpec("probe", "probe", Json.object(), Json.object(),
                    List.of(), List.of(effect), "wire-form-test", null, "1.0");
            assertEquals(effect.wireName(),
                    spec.toJson().getAsJsonArray("sideEffects").get(0).getAsString(),
                    "toJson must emit the canonical wire form, and parse must accept it");
        }
    }

    @Test
    void everySpellingInTheVocabularySectionIsAcceptedAndDocumented() throws IOException {
        String section = sideEffectSection(protocolMarkdown());
        Set<String> documented = new LinkedHashSet<>();
        Matcher matcher = DOCUMENTED_TOKEN.matcher(section);
        while (matcher.find()) {
            documented.add(matcher.group(1));
        }
        assertFalse(documented.isEmpty(), "no backticked vocabulary tokens found in §6.4");

        for (String token : documented) {
            try {
                Protocol.SideEffect.parse(token);
            } catch (Protocol.ProtocolException e) {
                fail("§6.4 documents '" + token + "' but the parser rejects it: " + e.getMessage());
            }
        }
        assertEquals(expectedSpellings(), documented,
                "§6.4 must document exactly the wire and dotted spelling of every value (a value added "
                        + "to the enum without a row, or a row the parser cannot read, fails here)");
    }
}
