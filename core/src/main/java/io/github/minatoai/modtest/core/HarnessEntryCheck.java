package io.github.minatoai.modtest.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Release rail: assert that a product artifact contains no harness entries.
 *
 * <p>Usage: {@code java -cp <this.jar> io.github.minatoai.modtest.core.HarnessEntryCheck <jar>}
 *
 * <p>Prints one summary line and exits 1 when harness entries are present. The harness itself is a
 * dev-only artifact, so pointing this at the harness jar is expected to fail — that is how the
 * check proves it can detect something.
 */
public final class HarnessEntryCheck {
    /** Package prefixes that must never appear in a product (released) artifact. */
    public static final List<String> HARNESS_PREFIXES = List.of(
            "io/github/minatoai/modtest/",
            "dev/modtest/",
            "modtest-agent"
    );

    private HarnessEntryCheck() {
    }

    public static List<String> harnessEntries(List<String> entryNames) {
        List<String> hits = new ArrayList<>();
        for (String name : entryNames) {
            for (String prefix : HARNESS_PREFIXES) {
                if (name.startsWith(prefix)) {
                    hits.add(name);
                    break;
                }
            }
        }
        return hits;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: HarnessEntryCheck <jar>");
            System.exit(2);
            return;
        }
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(args[0])) {
            zip.stream().map(ZipEntry::getName).forEach(names::add);
        }
        List<String> harness = harnessEntries(names);
        int product = 0;
        for (String n : names) {
            if (!n.endsWith("/") && !n.startsWith("META-INF/")) {
                product++;
            }
        }
        System.out.println("jar=" + args[0]);
        System.out.println("entries=" + names.size() + " productEntries=" + product
                + " harnessEntries=" + harness.size());
        for (String h : harness) {
            System.out.println("  HARNESS: " + h);
        }
        if (harness.isEmpty()) {
            System.out.println("PRODUCT-JAR-CLEAN");
        } else {
            System.out.println("PRODUCT-JAR-CONTAINS-HARNESS");
            System.exit(1);
        }
    }
}
