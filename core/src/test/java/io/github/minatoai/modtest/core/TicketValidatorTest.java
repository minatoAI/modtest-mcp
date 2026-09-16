package io.github.minatoai.modtest.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M1: every {@code E_*} code that validation can produce has a case here. */
class TicketValidatorTest {

    private final Executor.OpCatalog catalog = VanillaOps.install(
            new Executor.OpCatalog("test-executor", "0.1.0", "unit-test"));

    private Protocol.Ticket parse(String json, String fileName) {
        return Protocol.Ticket.fromJson(Json.parseObject(json), fileName);
    }

    private Protocol.ProtocolException validate(String json, String fileName) {
        Protocol.Ticket t = parse(json, fileName);
        new Relay.TicketValidator(catalog).validate(t);
        return null;
    }

    @Test
    void acceptsAValidTicket() {
        Protocol.Ticket t = parse("""
                {"protocol":"modtest-bridge/1.0","ticket":"t1","trial":"t3","timeout_ms":5000,
                 "ops":[{"op":"state.query","params":{"what":["all"]}}]}
                """, "t1");
        assertEquals("t1", t.ticket());
        assertEquals("t3", t.trial());
        assertEquals(5000, t.timeoutMs());
        assertEquals("op1", t.ops().get(0).id(), "missing op id is defaulted to op<N>");
        assertEquals("abort", t.onError());
        new Relay.TicketValidator(catalog).validate(t);
    }

    @Test
    void rejectsUnsupportedProtocolWithEProtocol() {
        Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                () -> parse("{\"protocol\":\"other/9.9\",\"ticket\":\"t1\",\"ops\":[{\"op\":\"x\"}]}", "t1"));
        assertEquals(Protocol.ErrorCode.E_PROTOCOL, e.code());
        assertNotNull(e.detail(), "detail.supported must name what we do speak");
        assertEquals(Protocol.ID, e.detail().get("supported").getAsString());
    }

    @Test
    void rejectsUnknownTicketField() {
        Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                () -> parse("{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"t1\",\"oops\":1,"
                        + "\"ops\":[{\"op\":\"state.query\"}]}", "t1"));
        assertEquals(Protocol.ErrorCode.E_BAD_TICKET, e.code());
    }

    @Test
    void rejectsFileNameMismatch() {
        Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                () -> parse("{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"t1\","
                        + "\"ops\":[{\"op\":\"state.query\"}]}", "other-file"));
        assertEquals(Protocol.ErrorCode.E_BAD_TICKET, e.code());
    }

    @Test
    void rejectsPathTraversalTicketName() {
        Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                () -> parse("{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"../evil\","
                        + "\"ops\":[{\"op\":\"state.query\"}]}", "../evil"));
        assertEquals(Protocol.ErrorCode.E_BAD_TICKET, e.code());
    }

    @Test
    void rejectsEmptyOps() {
        Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                () -> parse("{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"t1\",\"ops\":[]}", "t1"));
        assertEquals(Protocol.ErrorCode.E_BAD_TICKET, e.code());
    }

    @Test
    void rejectsDuplicateOpIdWithEBadOpId() {
        Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                () -> parse("{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"t1\",\"ops\":["
                        + "{\"id\":\"a\",\"op\":\"state.query\"},{\"id\":\"a\",\"op\":\"state.query\"}]}", "t1"));
        assertEquals(Protocol.ErrorCode.E_BAD_OP_ID, e.code());
    }

    @Test
    void rejectsUnknownOpFieldWithEBadParams() {
        Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                () -> parse("{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"t1\","
                        + "\"ops\":[{\"op\":\"state.query\",\"bogus\":true}]}", "t1"));
        assertEquals(Protocol.ErrorCode.E_BAD_PARAMS, e.code());
    }

    @Test
    void rejectsUnknownOpNameWithEUnknownOp() {
        Protocol.Ticket t = parse("{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"t1\","
                + "\"ops\":[{\"op\":\"world.delete\"}]}", "t1");
        Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                () -> new Relay.TicketValidator(catalog).validate(t));
        assertEquals(Protocol.ErrorCode.E_UNKNOWN_OP, e.code());
    }

    @Test
    void rejectsMissingRequiredParamWithEBadParams() {
        Protocol.Ticket t = parse("{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"t1\","
                + "\"ops\":[{\"op\":\"state.query\"}]}", "t1");
        Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                () -> new Relay.TicketValidator(catalog).validate(t));
        assertEquals(Protocol.ErrorCode.E_BAD_PARAMS, e.code());
        assertTrue(e.getMessage().contains("what"), e.getMessage());
    }

    @Test
    void rejectsUndeclaredParamWhenAdditionalPropertiesIsFalse() {
        Protocol.Ticket t = parse("{\"protocol\":\"modtest-bridge/1.0\",\"ticket\":\"t1\","
                + "\"ops\":[{\"op\":\"state.query\",\"params\":{\"what\":[\"all\"],\"extra\":1}}]}", "t1");
        Protocol.ProtocolException e = assertThrows(Protocol.ProtocolException.class,
                () -> new Relay.TicketValidator(catalog).validate(t));
        assertEquals(Protocol.ErrorCode.E_BAD_PARAMS, e.code());
    }

    @Test
    void catalogJsonIsMachineParsable() {
        JsonObject cat = catalog.catalogJson();
        assertEquals(Protocol.ID, cat.get("protocol").getAsString());
        assertEquals(10, cat.getAsJsonArray("ops").size(), "ten vanilla ops registered");
        JsonObject first = cat.getAsJsonArray("ops").get(0).getAsJsonObject();
        assertTrue(first.has("paramsSchema") && first.has("resultSchema")
                && first.has("preconditions") && first.has("sideEffects"));
    }

    @Test
    void sideEffectVocabularyClassifiesMutating() {
        assertTrue(Protocol.SideEffect.PLAYER_INPUT.isMutating());
        assertTrue(Protocol.SideEffect.WORLD_BLOCKS.isMutating());
        assertTrue(!Protocol.SideEffect.NONE.isMutating());
        assertTrue(!Protocol.SideEffect.TELEMETRY_RECORDING.isMutating(),
                "recording inside the bridge dir does not touch the game");
        assertEquals(9, Protocol.SideEffect.values().length);
        assertEquals(9, Executor.ExpectEngine.OPERATORS.size(), "nine comparison operators");
        assertEquals(11, Protocol.ErrorCode.values().length, "eleven stable error codes");
        assertTrue(List.of("eq", "ne", "gt", "gte", "lt", "lte", "exists", "matches", "in")
                .containsAll(Executor.ExpectEngine.OPERATORS));
    }
}
