package io.github.minatoai.modtest.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Bridge plumbing: clock, configuration, file-system port (§2/§7 of the specification). */
public final class Bridge {
    private Bridge() {
    }

    /** Injectable clock — token expiry and timeouts are testable without sleeping. */
    public interface Clock {
        long nowMs();

        static Clock system() {
            return System::currentTimeMillis;
        }

        static Clock fixed(long nowMs) {
            return () -> nowMs;
        }
    }

    /** §7.1: what to do when a second ticket arrives while one is being processed. */
    public enum BusyPolicy {
        /** Answer the extra ticket with E_BUSY and leave it for a later poll (default). */
        ANSWER_BUSY,
        /** Ignore the extra ticket this poll; it is picked up on a later tick. */
        QUEUE
    }

    /** Injected configuration; there is no hardcoded path and no default credential anywhere. */
    public record BridgeConfig(Path dir, long pollIntervalMs, String executorId, String executorVersion,
                               boolean allowMutate, BusyPolicy busyPolicy, int maxOpsPerTicket) {

        public static final String ENV_DIR = "MODTEST_AGENT_DIR";
        public static final String ENV_ALLOW_MUTATE = "MODTEST_ALLOW_MUTATE";
        public static final String ENV_EXECUTOR_ID = "MODTEST_EXECUTOR_ID";

        public static BridgeConfig fromEnv(Map<String, String> env, String defaultExecutorId, String version) {
            String dir = env.getOrDefault(ENV_DIR, "./.modtest-agent");
            boolean allowMutate = Boolean.parseBoolean(env.getOrDefault(ENV_ALLOW_MUTATE, "false"));
            String id = env.getOrDefault(ENV_EXECUTOR_ID, defaultExecutorId);
            return new BridgeConfig(Path.of(dir), 500L, id, version, allowMutate, BusyPolicy.ANSWER_BUSY, 64);
        }
    }

    /** Port over the bridge directory so tests can run without touching a real disk. */
    public interface BridgeFs {
        List<String> listTicketNames();

        String readTicket(String ticketName);

        void writeTicketTemp(String ticketName, String json);

        void commitTicket(String ticketName);

        boolean hasReceipt(String ticketName);

        void writeReceiptAtomic(String ticketName, String json);

        void archiveReceipt(String ticketName, long stamp);

        void moveTo(String ticketName, String subdir);

        List<String> listReceiptNames();
    }

    /** Real implementation (atomic publish: write {@code .json.tmp} then rename). */
    public static final class NioBridgeFs implements BridgeFs {
        private final Path dir;

        public NioBridgeFs(Path dir) {
            this.dir = dir;
        }

        private Path sub(String name) {
            Path p = dir.resolve(name);
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new UncheckedIo(e);
            }
            return p;
        }

        public Path root() {
            return dir;
        }

        @Override
        public List<String> listTicketNames() {
            return list(sub("inbox"), false);
        }

        @Override
        public String readTicket(String ticketName) {
            try {
                return Files.readString(sub("inbox").resolve(ticketName + ".json"), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIo(e);
            }
        }

        @Override
        public void writeTicketTemp(String ticketName, String json) {
            try {
                Files.writeString(sub("inbox").resolve(ticketName + ".json.tmp"), json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIo(e);
            }
        }

        @Override
        public void commitTicket(String ticketName) {
            try {
                Path tmp = sub("inbox").resolve(ticketName + ".json.tmp");
                Files.move(tmp, sub("inbox").resolve(ticketName + ".json"),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIo(e);
            }
        }

        @Override
        public boolean hasReceipt(String ticketName) {
            return Files.isRegularFile(sub("outbox").resolve(ticketName + ".result.json"));
        }

        @Override
        public void writeReceiptAtomic(String ticketName, String json) {
            try {
                Path out = sub("outbox");
                Path tmp = out.resolve(ticketName + ".result.json.tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, out.resolve(ticketName + ".result.json"),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIo(e);
            }
        }

        @Override
        public void archiveReceipt(String ticketName, long stamp) {
            try {
                Path out = sub("outbox");
                Path current = out.resolve(ticketName + ".result.json");
                if (Files.isRegularFile(current)) {
                    Files.createDirectories(out.resolve("archive"));
                    Files.move(current, out.resolve("archive").resolve(ticketName + "." + stamp + ".result.json"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIo(e);
            }
        }

        @Override
        public void moveTo(String ticketName, String subdir) {
            try {
                Path from = sub("inbox").resolve(ticketName + ".json");
                if (Files.isRegularFile(from)) {
                    Files.move(from, sub(subdir).resolve(ticketName + ".json"), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIo(e);
            }
        }

        @Override
        public List<String> listReceiptNames() {
            return list(sub("outbox"), true);
        }

        private List<String> list(Path p, boolean receipts) {
            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path f : ds) {
                    String n = f.getFileName().toString();
                    if (receipts) {
                        if (n.endsWith(".result.json")) {
                            names.add(n);
                        }
                    } else if (n.endsWith(".json")) {
                        names.add(n.substring(0, n.length() - 5));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIo(e);
            }
            names.sort(Comparator.naturalOrder());
            return names;
        }
    }

    /** In-memory implementation used by tests. */
    public static final class MemoryBridgeFs implements BridgeFs {
        public final Map<String, String> inbox = new java.util.LinkedHashMap<>();
        public final Map<String, String> outbox = new java.util.LinkedHashMap<>();
        public final Map<String, String> done = new java.util.LinkedHashMap<>();
        public final Map<String, String> failed = new java.util.LinkedHashMap<>();
        public final List<Long> archiveStamps = new ArrayList<>();

        @Override
        public List<String> listTicketNames() {
            List<String> names = new ArrayList<>();
            for (String key : inbox.keySet()) {
                if (key.endsWith(".json")) {
                    names.add(key.substring(0, key.length() - 5));
                }
            }
            names.sort(Comparator.naturalOrder());
            return names;
        }

        @Override
        public String readTicket(String ticketName) {
            return inbox.get(ticketName + ".json");
        }

        @Override
        public void writeTicketTemp(String ticketName, String json) {
            inbox.put(ticketName + ".json.tmp", json);
        }

        @Override
        public void commitTicket(String ticketName) {
            String tmp = inbox.remove(ticketName + ".json.tmp");
            inbox.put(ticketName + ".json", tmp);
        }

        @Override
        public boolean hasReceipt(String ticketName) {
            return outbox.containsKey(ticketName + ".result.json");
        }

        @Override
        public void writeReceiptAtomic(String ticketName, String json) {
            outbox.put(ticketName + ".result.json", json);
        }

        @Override
        public void archiveReceipt(String ticketName, long stamp) {
            if (outbox.remove(ticketName + ".result.json") != null) {
                archiveStamps.add(stamp);
            }
        }

        @Override
        public void moveTo(String ticketName, String subdir) {
            String json = inbox.remove(ticketName + ".json");
            if (json != null) {
                ("done".equals(subdir) ? done : failed).put(ticketName + ".json", json);
            }
        }

        @Override
        public List<String> listReceiptNames() {
            List<String> names = new ArrayList<>(outbox.keySet());
            names.sort(Comparator.naturalOrder());
            return names;
        }
    }

    /** Wraps IO failures so relay code has a single unchecked error path. */
    public static final class UncheckedIo extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UncheckedIo(IOException cause) {
            super(cause);
        }
    }
}
