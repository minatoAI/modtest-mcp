package io.github.minatoai.modtest.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The wire model of {@code modtest-bridge/1.0} (see docs/PROTOCOL.md).
 *
 * <p>Tickets and receipts are parsed and produced by hand so that every field rule in the
 * specification has exactly one implementation and one test.
 */
public final class Protocol {
    public static final String ID = "modtest-bridge/1.0";
    public static final String DEFAULT_TRIAL = "t1";
    public static final int DEFAULT_TIMEOUT_MS = 10_000;

    private Protocol() {
    }

    /** Stable error codes, §4.1 of the specification. */
    public enum ErrorCode {
        E_PROTOCOL, E_BAD_TICKET, E_BAD_OP_ID, E_UNKNOWN_OP, E_BAD_PARAMS,
        E_PRECONDITION, E_TIMEOUT, E_BUSY, E_EXEC, E_ASSERT, E_UNSUPPORTED,
        // ---- non-failure terminations (the mineflayer superseded/stopped/no-path/stuck family) ----
        // The task did not complete, and that is NOT a defect: it was replaced, stopped, or could not be
        // carried out at all. Callers MUST switch on the code (or use nonFailureTermination()) rather than
        // treating every ok:false as a harness failure. Additive only: no existing code changed meaning,
        // and no new error *field* was introduced for the distinction.
        E_SUPERSEDED, E_STOPPED, E_NO_PATH, E_STUCK;

        /**
         * Whether this code means "the task ended without completing, and that is not a failure".
         *
         * <ul>
         *   <li>{@code E_SUPERSEDED} — a newer request replaced this one, e.g. another input command
         *       arrived while an {@code input.stop} was waiting for a safe point. Retrying is usually
         *       wrong: the newer request owns the player now.</li>
         *   <li>{@code E_STOPPED} — the movement asked about had already been stopped (or never started),
         *       so there was nothing to stop. Not an error, and not a success to claim twice.</li>
         *   <li>{@code E_NO_PATH} — no route to the target exists. <b>Reserved</b>: declared now for the
         *       movement planner ({@code walk.within}), but nothing produces it yet.</li>
         *   <li>{@code E_STUCK} — movement stopped making progress. <b>Reserved</b>, as above.</li>
         * </ul>
         */
        public boolean nonFailureTermination() {
            return this == E_SUPERSEDED || this == E_STOPPED || this == E_NO_PATH || this == E_STUCK;
        }
    }

    /** §6.4 side-effect vocabulary. Anything but NONE/TELEMETRY_RECORDING is mutating. */
    public enum SideEffect {
        NONE, TELEMETRY_RECORDING, PLAYER_INPUT, PLAYER_INVENTORY, PLAYER_STATE,
        WORLD_BLOCKS, WORLD_ENTITIES, SERVER_COMMAND, RENDER_PIPELINE;

        public boolean isMutating() {
            return this != NONE && this != TELEMETRY_RECORDING;
        }

        /**
         * Parses either spelling of the vocabulary: the canonical wire form (the enum name, e.g.
         * {@code TELEMETRY_RECORDING}) or the dotted lowercase documentation form
         * (e.g. {@code telemetry.recording}). Separators are ignored and the match is
         * case-insensitive, so {@code telemetry_recording} and {@code Telemetry.Recording} work too.
         *
         * <p>Before this, only the enum name was accepted: the values {@code PROTOCOL.md §6.4} told
         * implementers to send could not be parsed by the protocol's own parser, so a value copied out
         * of the specification was rejected with {@code E_BAD_PARAMS}. Accepting both spellings removes
         * the trap; {@link #wireName()} is what {@link OpSpec#toJson()} emits.
         */
        public static SideEffect parse(String raw) {
            if (raw != null) {
                String normalized = raw.trim().replace("_", "").replace(".", "");
                for (SideEffect s : values()) {
                    if (s.name().replace("_", "").equalsIgnoreCase(normalized)) {
                        return s;
                    }
                }
            }
            throw new ProtocolException(ErrorCode.E_BAD_PARAMS, "unknown sideEffect: " + raw);
        }

        /** The canonical wire form an executor emits: the enum name (e.g. {@code TELEMETRY_RECORDING}). */
        public String wireName() {
            return name();
        }

        /** The dotted lowercase form this specification documents (e.g. {@code telemetry.recording}). */
        public String docName() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '.');
        }
    }

    /** A declared precondition (§6.3). */
    public record Precondition(String kind, JsonObject payload) {
        public static Precondition of(String kind) {
            return new Precondition(kind, null);
        }

        public static Precondition of(String kind, String key, String value) {
            JsonObject p = Json.object();
            p.addProperty(key, value);
            return new Precondition(kind, p);
        }
    }

    /** A machine-parsable op description (§6.2). */
    public record OpSpec(String name, String title, JsonObject paramsSchema, JsonObject resultSchema,
                         List<Precondition> preconditions, List<SideEffect> sideEffects,
                         String executorId, JsonObject divergence, String since) {

        public boolean mutating() {
            return sideEffects.stream().anyMatch(SideEffect::isMutating);
        }

        public JsonObject toJson() {
            JsonObject o = Json.object();
            o.addProperty("name", name);
            if (title != null) {
                o.addProperty("title", title);
            }
            o.add("paramsSchema", paramsSchema == null ? Json.object() : paramsSchema);
            o.add("resultSchema", resultSchema == null ? Json.object() : resultSchema);
            JsonArray pre = Json.array();
            for (Precondition p : preconditions) {
                JsonObject e = Json.object();
                e.addProperty("kind", p.kind());
                if (p.payload() != null) {
                    p.payload().entrySet().forEach(en -> e.add(en.getKey(), en.getValue()));
                }
                pre.add(e);
            }
            o.add("preconditions", pre);
            JsonArray se = Json.array();
            for (SideEffect s : sideEffects) {
                se.add(s.name());
            }
            o.add("sideEffects", se);
            o.addProperty("executorId", executorId);
            if (divergence != null) {
                o.add("divergence", divergence);
            }
            if (since != null) {
                o.addProperty("since", since);
            }
            return o;
        }
    }

    /** A ticket (§3). */
    public record Ticket(String protocol, String ticket, String trial, int timeoutMs, String onError, List<Op> ops) {

        public record Op(String id, String op, JsonObject params, JsonObject expect, Integer timeoutMs, String onError) {
        }

        public static final Set<String> ON_ERROR = Set.of("abort", "continue");
        private static final Set<String> TICKET_FIELDS =
                Set.of("protocol", "ticket", "trial", "comment", "created_utc", "timeout_ms", "on_error", "ops");
        private static final Set<String> OP_FIELDS =
                Set.of("id", "op", "params", "expect", "timeout_ms", "on_error");

        /** Parses a ticket; {@code fileName} is the inbox file name (without .json). */
        public static Ticket fromJson(JsonObject o, String fileName) {
            String protocol = Json.str(o, "protocol", null);
            if (!ID.equals(protocol)) {
                throw new ProtocolException(ErrorCode.E_PROTOCOL, "unsupported protocol: " + protocol)
                        .with("supported", ID);
            }
            Set<String> unknown = new LinkedHashSet<>(o.keySet());
            unknown.removeAll(TICKET_FIELDS);
            if (!unknown.isEmpty()) {
                throw new ProtocolException(ErrorCode.E_BAD_TICKET, "unknown ticket field(s): " + unknown);
            }
            String name = Json.str(o, "ticket", null);
            if (name == null || !Names.isValid(name)) {
                throw new ProtocolException(ErrorCode.E_BAD_TICKET, "bad ticket name: " + name);
            }
            if (!name.equals(fileName)) {
                throw new ProtocolException(ErrorCode.E_BAD_TICKET,
                        "ticket '" + name + "' does not match file name '" + fileName + "'");
            }
            JsonArray rawOps = Json.arrOrNull(o, "ops");
            if (rawOps == null || rawOps.isEmpty()) {
                throw new ProtocolException(ErrorCode.E_BAD_TICKET, "ops must be a non-empty array");
            }
            String onError = Json.str(o, "on_error", "abort");
            if (!ON_ERROR.contains(onError)) {
                throw new ProtocolException(ErrorCode.E_BAD_TICKET, "bad on_error: " + onError);
            }
            int timeout = Json.intOr(o, "timeout_ms", DEFAULT_TIMEOUT_MS);
            List<Op> ops = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            int index = 0;
            for (JsonElement el : rawOps) {
                index++;
                if (!el.isJsonObject()) {
                    throw new ProtocolException(ErrorCode.E_BAD_TICKET, "ops[" + (index - 1) + "] must be an object");
                }
                JsonObject raw = el.getAsJsonObject();
                Set<String> opUnknown = new LinkedHashSet<>(raw.keySet());
                opUnknown.removeAll(OP_FIELDS);
                if (!opUnknown.isEmpty()) {
                    throw new ProtocolException(ErrorCode.E_BAD_PARAMS,
                            "ops[" + (index - 1) + "] unknown field(s): " + opUnknown);
                }
                String opName = Json.str(raw, "op", null);
                if (opName == null || opName.isBlank()) {
                    throw new ProtocolException(ErrorCode.E_BAD_PARAMS, "ops[" + (index - 1) + "].op must be a string");
                }
                String id = Json.str(raw, "id", null);
                if (id == null) {
                    id = "op" + index;
                }
                if (!Names.isValid(id)) {
                    throw new ProtocolException(ErrorCode.E_BAD_OP_ID, "bad op id: " + id);
                }
                if (!ids.add(id)) {
                    throw new ProtocolException(ErrorCode.E_BAD_OP_ID, "duplicate op id: " + id);
                }
                String opOnError = Json.str(raw, "on_error", null);
                if (opOnError != null && !ON_ERROR.contains(opOnError)) {
                    throw new ProtocolException(ErrorCode.E_BAD_PARAMS, "bad on_error: " + opOnError);
                }
                Integer opTimeout = raw.has("timeout_ms") && !raw.get("timeout_ms").isJsonNull()
                        ? raw.get("timeout_ms").getAsInt() : null;
                ops.add(new Op(id, opName, Json.objOrNull(raw, "params"), Json.objOrNull(raw, "expect"),
                        opTimeout, opOnError));
            }
            return new Ticket(protocol, name, Json.str(o, "trial", DEFAULT_TRIAL), timeout, onError, List.copyOf(ops));
        }

        public String onErrorFor(Op op) {
            return op.onError() != null ? op.onError() : onError;
        }
    }

    /** A receipt (§4). */
    public record Receipt(String protocol, String ticket, String trial, boolean ok, ExecutorInfo executor,
                          String startedUtc, String finishedUtc, long durationMs, Error error, List<OpResult> ops) {

        public record ExecutorInfo(String id, String version, String impl) {
        }

        public record Error(String code, String message, JsonObject detail) {
            public static Error of(ErrorCode code, String message) {
                return new Error(code.name(), message, null);
            }
        }

        public record OpResult(String id, String op, boolean ok, JsonObject result, Error error,
                               boolean skipped, long durationMs) {
            public static OpResult ok(String id, String op, JsonObject result, long durationMs) {
                return new OpResult(id, op, true, result, null, false, durationMs);
            }

            public static OpResult failed(String id, String op, Error error, long durationMs) {
                return new OpResult(id, op, false, null, error, false, durationMs);
            }

            public static OpResult skipped(String id, String op) {
                return new OpResult(id, op, false, null, null, true, 0L);
            }
        }

        public JsonObject toJson() {
            JsonObject o = Json.object();
            o.addProperty("protocol", protocol);
            o.addProperty("ticket", ticket);
            o.addProperty("trial", trial);
            o.addProperty("ok", ok);
            JsonObject ex = Json.object();
            ex.addProperty("id", executor.id());
            ex.addProperty("version", executor.version());
            if (executor.impl() != null) {
                ex.addProperty("impl", executor.impl());
            }
            o.add("executor", ex);
            o.addProperty("started_utc", startedUtc);
            o.addProperty("finished_utc", finishedUtc);
            o.addProperty("duration_ms", durationMs);
            if (error != null) {
                o.add("error", errorJson(error));
            }
            JsonArray ops = Json.array();
            for (OpResult r : this.ops) {
                JsonObject e = Json.object();
                e.addProperty("id", r.id());
                e.addProperty("op", r.op());
                e.addProperty("ok", r.ok());
                if (r.skipped()) {
                    e.addProperty("skipped", true);
                }
                if (r.result() != null) {
                    e.add("result", r.result());
                }
                if (r.error() != null) {
                    e.add("error", errorJson(r.error()));
                }
                e.addProperty("duration_ms", r.durationMs());
                ops.add(e);
            }
            o.add("ops", ops);
            return o;
        }

        private static JsonObject errorJson(Error e) {
            JsonObject j = Json.object();
            j.addProperty("code", e.code());
            j.addProperty("message", e.message());
            if (e.detail() != null) {
                j.add("detail", e.detail());
            }
            return j;
        }
    }

    /** Ticket/op id rule (§2): {@code ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$}. */
    public static final class Names {
        private Names() {
        }

        public static boolean isValid(String s) {
            return s != null && s.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
        }
    }

    /** Protocol failure carrying a stable code and optional JSON detail. */
    public static class ProtocolException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final ErrorCode code;
        private JsonObject detail;

        public ProtocolException(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public ErrorCode code() {
            return code;
        }

        public JsonObject detail() {
            return detail;
        }

        public ProtocolException with(String key, String value) {
            if (detail == null) {
                detail = Json.object();
            }
            detail.addProperty(key, value);
            return this;
        }

        public Receipt.Error toError() {
            return new Receipt.Error(code.name(), getMessage(), detail);
        }
    }
}
