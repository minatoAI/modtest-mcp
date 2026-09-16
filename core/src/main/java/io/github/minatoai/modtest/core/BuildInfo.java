package io.github.minatoai.modtest.core;

import java.util.jar.Manifest;

/** Self-identification: which artifact is this, and is its guard on? */
public final class BuildInfo {
    private static final String COMPONENT = "modtest-harness-core";

    private BuildInfo() {
    }

    /** {@code modtest-harness-core 0.1.0 guard=GUARDED} */
    public static String describe() {
        return COMPONENT + " " + version() + " guard=" + Guard.BuildVariant.current();
    }

    public static String version() {
        try (java.io.InputStream in = BuildInfo.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in != null) {
                String v = new Manifest(in).getMainAttributes().getValue("Implementation-Version");
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "dev";
    }

    /** True when this artifact enforces the injection policy. Stamped into the manifest at build time. */
    public static boolean guarded() {
        return Guard.BuildVariant.current() == Guard.BuildVariant.GUARDED;
    }
}
