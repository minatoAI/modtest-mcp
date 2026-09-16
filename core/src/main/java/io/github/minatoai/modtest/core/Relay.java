package io.github.minatoai.modtest.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * M1 core-relay: validate → execute → answer, with the inbox/outbox rules of §7.
 *
 * <p>The relay never decides what an op *means*; it enforces the wire contract and delegates to
 * {@link Executor.OpHandler} implementations through {@link Executor.TicketExecutor}.
 */
public final class Relay {

    /** Structured log line per executed op (§7.2 requirement 5). */
    public interface OpLog extends Consumer<String> {
    }

    /** Validates a ticket against the catalog before anything is executed. */
    public static final class TicketValidator {
        private final Executor.OpCatalog catalog;

        public TicketValidator(Executor.OpCatalog catalog) {
            this.catalog = catalog;
        }

        /** Throws {@link Protocol.ProtocolException} with the code required by §4.1. */
        public void validate(Protocol.Ticket ticket) {
            for (Protocol.Ticket.Op op : ticket.ops()) {
                Protocol.OpSpec spec = catalog.lookup(op.op());
                if (spec == null) {
                    throw new Protocol.ProtocolException(Protocol.ErrorCode.E_UNKNOWN_OP,
                            "unknown op: " + op.op()).with("known", catalog.names().toString());
                }
                validateParams(spec, op);
            }
        }

        private void validateParams(Protocol.OpSpec spec, Protocol.Ticket.Op op) {
            JsonObject schema = spec.paramsSchema();
            JsonObject params = op.params() == null ? Json.object() : op.params();
            if (schema == null) {
                return;
            }
            JsonArray required = Json.arrOrNull(schema, "required");
            if (required != null) {
                for (JsonElement r : required) {
                    if (!params.has(r.getAsString())) {
                        throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                                "op " + op.id() + ": missing required param '" + r.getAsString() + "'")
                                .with("path", "ops." + op.id() + ".params." + r.getAsString());
                    }
                }
            }
            if (Boolean.FALSE.equals(schema.has("additionalProperties")
                    ? schema.get("additionalProperties").getAsBoolean() : null)) {
                JsonObject props = Json.objOrNull(schema, "properties");
                List<String> extra = new ArrayList<>();
                for (String key : params.keySet()) {
                    if (props == null || !props.has(key)) {
                        extra.add(key);
                    }
                }
                if (!extra.isEmpty()) {
                    throw new Protocol.ProtocolException(Protocol.ErrorCode.E_BAD_PARAMS,
                            "op " + op.id() + ": unknown param(s) " + extra);
                }
            }
        }
    }

    /** Receipt storage: one receipt per ticket, archived before being replaced (§4). */
    public static final class ReceiptStore {
        private final Bridge.BridgeFs fs;
        private final Bridge.Clock clock;

        public ReceiptStore(Bridge.BridgeFs fs, Bridge.Clock clock) {
            this.fs = fs;
            this.clock = clock;
        }

        public void store(String ticketName, Protocol.Receipt receipt) {
            if (fs.hasReceipt(ticketName)) {
                fs.archiveReceipt(ticketName, clock.nowMs());
            }
            fs.writeReceiptAtomic(ticketName, Json.pretty(receipt.toJson()));
        }
    }

    /** One poll loop iteration; deterministic and therefore unit-testable. */
    public static final class BridgeRelay {
        private final Bridge.BridgeConfig config;
        private final Bridge.BridgeFs fs;
        private final TicketValidator validator;
        private final Executor.TicketExecutor executor;
        private final ReceiptStore receipts;
        private final Bridge.Clock clock;
        private final OpLog log;
        private final java.util.function.Supplier<Executor.ExecContext> contexts;

        public BridgeRelay(Bridge.BridgeConfig config, Bridge.BridgeFs fs, TicketValidator validator,
                           Executor.TicketExecutor executor, ReceiptStore receipts, Bridge.Clock clock, OpLog log,
                           java.util.function.Supplier<Executor.ExecContext> contexts) {
            this.config = config;
            this.fs = fs;
            this.validator = validator;
            this.executor = executor;
            this.receipts = receipts;
            this.clock = clock;
            this.log = log;
            this.contexts = contexts;
        }

        /**
         * Processes at most one ticket.
         *
         * @return the ticket name that was answered, or {@code null} when the inbox was empty or
         *         the busy policy deferred the work.
         */
        public String tick() {
            List<String> names = fs.listTicketNames();
            if (names.isEmpty()) {
                return null;
            }
            // §7.1 busy policy: a ticket that already has a receipt is never re-executed.
            String first = names.get(0);
            for (String name : names) {
                if (!fs.hasReceipt(name)) {
                    first = name;
                    break;
                }
            }
            if (fs.hasReceipt(first)) {
                return null;
            }
            if (config.busyPolicy() == Bridge.BusyPolicy.QUEUE && names.size() > 1) {
                return null;
            }
            return executeOne(first);
        }

        /** Answers every queued ticket; used when the executor is idle for a whole cycle. */
        public List<String> drain() {
            List<String> answered = new ArrayList<>();
            for (String name : fs.listTicketNames()) {
                if (!fs.hasReceipt(name)) {
                    answered.add(executeOne(name));
                }
            }
            return answered;
        }

        private String executeOne(String ticketName) {
            long start = clock.nowMs();
            Protocol.Ticket ticket;
            try {
                String raw = fs.readTicket(ticketName);
                ticket = Protocol.Ticket.fromJson(Json.parseObject(raw), ticketName);
                validator.validate(ticket);
            } catch (Protocol.ProtocolException e) {
                reject(ticketName, e.toError());
                log.accept("ticket=" + ticketName + " result=rejected code=" + e.code() + " message=" + e.getMessage());
                return ticketName;
            } catch (RuntimeException e) {
                // §7.3: unreadable/bad JSON is skipped, never executed, and left for inspection.
                reject(ticketName, Protocol.Receipt.Error.of(Protocol.ErrorCode.E_BAD_TICKET, "unreadable ticket"));
                log.accept("ticket=" + ticketName + " result=unreadable error=" + e.getClass().getSimpleName());
                return ticketName;
            }
            Protocol.Receipt receipt = executor.execute(ticket, log, contexts.get());
            receipts.store(ticketName, receipt);
            fs.moveTo(ticketName, receipt.ok() ? "done" : "failed");
            log.accept("ticket=" + ticketName + " result=" + (receipt.ok() ? "ok" : "fail")
                    + " ops=" + receipt.ops().size() + " durMs=" + (clock.nowMs() - start));
            return ticketName;
        }

        /** Answers a ticket that failed validation: the receipt carries the *specific* code. */
        private void reject(String ticketName, Protocol.Receipt.Error error) {
            Protocol.Receipt receipt = new Protocol.Receipt(Protocol.ID, ticketName, Protocol.DEFAULT_TRIAL, false,
                    new Protocol.Receipt.ExecutorInfo(config.executorId(), config.executorVersion(), null),
                    null, null, 0L, error, List.of());
            receipts.store(ticketName, receipt);
            fs.moveTo(ticketName, "failed");
        }
    }
}
