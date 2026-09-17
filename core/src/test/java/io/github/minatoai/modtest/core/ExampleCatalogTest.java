package io.github.minatoai.modtest.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code examples/catalog.example.json} from drifting away from the catalog the executor
 * actually publishes.
 *
 * <p>The example is hand-written on purpose (it is documentation, not generated), and it had already
 * drifted: its {@code bench.read} advertised the pre-task-70 snake_case field names, {@code "none"}
 * side effects and a 600-frame default that no implementation used — while every test stayed green,
 * because nothing cross-checked the file at all.
 *
 * <p>The rule is mechanical, and applies only to ops the example shares with the real catalog: side
 * effects, preconditions, parameter and result field names, required-ness and defaults must agree.
 * Titles, {@code llmSummary}, {@code divergence} and illustrative extras may differ — the example is a
 * sample, not a copy of the vanilla catalog.
 */
class ExampleCatalogTest {

    private static Executor.OpCatalog catalog() {
        return VanillaOps.install(new Executor.OpCatalog("example-test", "0.1.0", "example-catalog-test"));
    }

    private static JsonObject example() throws IOException {
        return Json.parseObject(Files.readString(locate(), StandardCharsets.UTF_8));
    }

    /** Gradle runs tests with the module dir as the working directory; tolerate either layout. */
    private static Path locate() {
        for (Path candidate : List.of(Path.of("examples", "catalog.example.json"),
                Path.of("..", "examples", "catalog.example.json"))) {
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
        }
        throw new AssertionError("examples/catalog.example.json not found from "
                + Path.of("").toAbsolutePath());
    }

    private static Set<String> names(JsonArray array) {
        Set<String> out = new LinkedHashSet<>();
        if (array != null) {
            for (JsonElement e : array) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    private static Set<String> propertyNames(JsonObject schema) {
        Set<String> out = new LinkedHashSet<>();
        if (schema != null && schema.has("properties")) {
            out.addAll(schema.getAsJsonObject("properties").keySet());
        }
        return out;
    }

    /**
     * Side effects as the protocol's own vocabulary, tolerating the two spellings currently in the
     * tree: {@code OpSpec.toJson()} emits the enum names ({@code NONE}, {@code TELEMETRY_RECORDING}),
     * while PROTOCOL §6.4 documents dotted lowercase ({@code none}, {@code telemetry.recording}) and
     * {@code SideEffect.parse} only accepts the former.
     *
     * <p>The comparison is semantic on purpose. The spelling discrepancy is a real, separately reported
     * issue about the catalog output; it is not something this test should silently cement either way.
     */
    private static Set<Protocol.SideEffect> sideEffects(JsonArray array) {
        Set<Protocol.SideEffect> out = new LinkedHashSet<>();
        if (array != null) {
            for (JsonElement element : array) {
                String raw = element.getAsString();
                out.add(Protocol.SideEffect.parse(
                        raw.toUpperCase(java.util.Locale.ROOT).replace('.', '_')));
            }
        }
        return out;
    }

    private static JsonObject exampleOp(JsonObject root, String name) {
        for (JsonElement element : root.getAsJsonArray("ops")) {
            JsonObject op = element.getAsJsonObject();
            if (name.equals(op.get("name").getAsString())) {
                return op;
            }
        }
        throw new AssertionError("the example does not document " + name);
    }

    @Test
    void everySharedOpAgreesWithTheRealCatalogOnItsContract() throws IOException {
        JsonObject root = example();
        assertEquals(Protocol.ID, root.get("protocol").getAsString());
        JsonArray ops = root.getAsJsonArray("ops");
        assertNotNull(ops, "the example must carry an ops array");

        int shared = 0;
        for (JsonElement element : ops) {
            JsonObject ex = element.getAsJsonObject();
            String name = ex.get("name").getAsString();
            Protocol.OpSpec spec = catalog().lookup(name);
            if (spec == null) {
                continue;   // the example may illustrate ops this catalog does not ship
            }
            shared++;
            JsonObject real = spec.toJson();

            assertEquals(sideEffects(real.getAsJsonArray("sideEffects")),
                    sideEffects(ex.getAsJsonArray("sideEffects")),
                    name + ": the example's side effects must match the real op's — a wrong side effect "
                            + "misstates what the op can change");
            assertEquals(real.getAsJsonArray("preconditions"), ex.getAsJsonArray("preconditions"),
                    name + ": preconditions must match");

            JsonObject realParams = real.getAsJsonObject("paramsSchema");
            JsonObject exParams = ex.getAsJsonObject("paramsSchema");
            assertTrue(propertyNames(exParams).containsAll(propertyNames(realParams)),
                    name + ": the example is missing a parameter the real op declares: "
                            + propertyNames(realParams) + " vs " + propertyNames(exParams));
            assertEquals(names(realParams.getAsJsonArray("required")),
                    names(exParams.getAsJsonArray("required")),
                    name + ": required parameters must match (a default must not become a requirement)");

            // The result field names are the drift that was actually found (fps_median vs fpsMedian),
            // so when the real schema declares any, the example must declare exactly those.
            JsonObject realResult = real.getAsJsonObject("resultSchema");
            if (!propertyNames(realResult).isEmpty()) {
                assertEquals(propertyNames(realResult), propertyNames(ex.getAsJsonObject("resultSchema")),
                        name + ": the example's result fields must be the fields the receipt really "
                                + "carries");
            }
        }
        assertTrue(shared >= 4, "expected the example to share ops with the real catalog, shared=" + shared);
    }

    @Test
    void theBenchDefaultsInTheExampleAreTheOnesTheImplementationUses() throws IOException {
        // The real paramsSchema does not carry defaults, so they are pinned here: these are the numbers
        // the implementation and PROTOCOL §6.2c use. Changing the implementation's default without
        // changing the example (and this test) is exactly the drift this class exists to catch.
        JsonObject params = exampleOp(example(), "bench.read")
                .getAsJsonObject("paramsSchema").getAsJsonObject("properties");
        assertEquals(60, params.getAsJsonObject("warmup_frames").get("default").getAsInt());
        assertEquals(300, params.getAsJsonObject("sample_frames").get("default").getAsInt());
    }
}
