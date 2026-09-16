package io.github.minatoai.modtest.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M1: atomic receipts, archive-before-overwrite, and the "never re-execute" rule. */
class ReceiptStoreTest {

    private Protocol.Receipt receipt(String ticket, boolean ok) {
        return new Protocol.Receipt(Protocol.ID, ticket, "t1", ok,
                new Protocol.Receipt.ExecutorInfo("test", "0.1.0", null), "s", "f", 1L, null,
                List.of(Protocol.Receipt.OpResult.ok("op1", "state.query", Json.object(), 0L)));
    }

    @Test
    void overwritingArchivesInsteadOfLosingTheOldReceipt() {
        Bridge.MemoryBridgeFs fs = new Bridge.MemoryBridgeFs();
        Bridge.Clock clock = Bridge.Clock.fixed(1_700_000_000_000L);
        Relay.ReceiptStore store = new Relay.ReceiptStore(fs, clock);

        store.store("t1", receipt("t1", true));
        assertEquals(1, fs.outbox.size());
        store.store("t1", receipt("t1", false));

        assertEquals(1, fs.outbox.size(), "still exactly one live receipt");
        assertEquals(1, fs.archiveStamps.size(), "the previous receipt must have been archived");
        assertEquals(1_700_000_000_000L, fs.archiveStamps.get(0));
        assertFalse(Json.parseObject(fs.outbox.get("t1.result.json")).get("ok").getAsBoolean());
    }

    @Test
    void nioStoreWritesAtomicallyAndLeavesNoTempFile(@TempDir Path dir) throws IOException {
        Bridge.NioBridgeFs fs = new Bridge.NioBridgeFs(dir);
        Relay.ReceiptStore store = new Relay.ReceiptStore(fs, Bridge.Clock.fixed(1L));
        store.store("t-atomic", receipt("t-atomic", true));

        Path out = dir.resolve("outbox");
        assertTrue(Files.isRegularFile(out.resolve("t-atomic.result.json")));
        try (var stream = Files.list(out)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "no .json.tmp may survive an atomic write");
        }
        String text = Files.readString(out.resolve("t-atomic.result.json"), StandardCharsets.UTF_8);
        assertEquals(Protocol.ID, Json.parseObject(text).get("protocol").getAsString());
    }

    @Test
    void inboxListingIgnoresTempAndNonJsonFiles(@TempDir Path dir) throws IOException {
        Bridge.NioBridgeFs fs = new Bridge.NioBridgeFs(dir);
        fs.writeTicketTemp("half-written", "{}");
        assertTrue(fs.listTicketNames().isEmpty(), "a .json.tmp must never be picked up");

        Files.writeString(dir.resolve("inbox").resolve("notes.txt"), "not a ticket");
        assertEquals(List.of(), fs.listTicketNames());
    }
}
