package io.github.minatoai.modtest.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** M3: the nine {@code expect} comparison operators (§4.3). */
class ExpectEngineTest {

    private final Executor.ExpectEngine engine = new Executor.ExpectEngine();

    private com.google.gson.JsonObject result() {
        com.google.gson.JsonObject inner = Json.object();
        inner.addProperty("yaw", 12.5);
        com.google.gson.JsonObject out = Json.object();
        out.add("pose", inner);
        out.addProperty("held", "minecraft:stone");
        out.addProperty("count", 3);
        return out;
    }

    private void expect(String path, String op, String rawValue) {
        com.google.gson.JsonObject rule = Json.object();
        rule.addProperty("op", op);
        rule.add("value", com.google.gson.JsonParser.parseString(rawValue));
        com.google.gson.JsonObject expect = Json.object();
        expect.add(path, rule);
        engine.evaluate(result(), expect);
    }

    private Protocol.ProtocolException failure(String path, String op, String rawValue) {
        return assertThrows(Protocol.ProtocolException.class, () -> expect(path, op, rawValue));
    }

    @Test
    void eqPassesAndFailsCorrectly() {
        assertDoesNotThrow(() -> expect("pose.yaw", "eq", "12.5"));
        assertEquals(Protocol.ErrorCode.E_ASSERT, failure("pose.yaw", "eq", "13").code());
    }

    @Test
    void nePassesAndFailsCorrectly() {
        assertDoesNotThrow(() -> expect("pose.yaw", "ne", "13"));
        assertEquals(Protocol.ErrorCode.E_ASSERT, failure("pose.yaw", "ne", "12.5").code());
    }

    @Test
    void gtAndGteWork() {
        assertDoesNotThrow(() -> expect("pose.yaw", "gt", "12"));
        assertDoesNotThrow(() -> expect("pose.yaw", "gte", "12.5"));
        assertEquals(Protocol.ErrorCode.E_ASSERT, failure("pose.yaw", "gt", "12.5").code());
    }

    @Test
    void ltAndLteWork() {
        assertDoesNotThrow(() -> expect("pose.yaw", "lt", "13"));
        assertDoesNotThrow(() -> expect("pose.yaw", "lte", "12.5"));
        assertEquals(Protocol.ErrorCode.E_ASSERT, failure("pose.yaw", "lt", "12.5").code());
    }

    @Test
    void existsChecksPresence() {
        assertDoesNotThrow(() -> expect("held", "exists", "true"));
        assertDoesNotThrow(() -> expect("pose.pitch", "exists", "false"));
    }

    @Test
    void matchesUsesRegex() {
        assertDoesNotThrow(() -> expect("held", "matches", "\"minecraft:.*\""));
        assertEquals(Protocol.ErrorCode.E_ASSERT, failure("held", "matches", "\"other:.*\"").code());
    }

    @Test
    void inChecksMembership() {
        assertDoesNotThrow(() -> expect("count", "in", "[1,2,3]"));
        assertEquals(Protocol.ErrorCode.E_ASSERT, failure("count", "in", "[9,10]").code());
    }

    @Test
    void failingAssertionNamesThePath() {
        Protocol.ProtocolException e = failure("pose.yaw", "eq", "999");
        assertEquals("pose.yaw", e.detail().get("failed").getAsString());
    }

    @Test
    void unknownOperatorIsUnsupported() {
        assertEquals(Protocol.ErrorCode.E_UNSUPPORTED, failure("count", "approximately", "3").code());
    }

    @Test
    void emptyExpectBlockIsANoOp() {
        assertDoesNotThrow(() -> engine.evaluate(result(), Json.object()));
        assertDoesNotThrow(() -> engine.evaluate(result(), null));
    }

    @Test
    void multipleAssertionsAreAllChecked() {
        com.google.gson.JsonObject expect = Json.object();
        com.google.gson.JsonObject eq = Json.object();
        eq.addProperty("op", "eq");
        eq.add("value", com.google.gson.JsonParser.parseString("12.5"));
        expect.add("pose.yaw", eq);
        com.google.gson.JsonObject exists = Json.object();
        exists.addProperty("op", "exists");
        expect.add("held", exists);
        assertDoesNotThrow(() -> engine.evaluate(result(), expect));
    }
}
