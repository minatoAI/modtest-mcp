package io.github.minatoai.modtest.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** JSON helpers. The whole protocol speaks {@link JsonObject}; no POJO binding is used. */
public final class Json {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private Json() {
    }

    public static JsonObject parseObject(String text) {
        JsonElement el = JsonParser.parseString(text);
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        return el.getAsJsonObject();
    }

    public static String pretty(JsonObject o) {
        return GSON.toJson(o);
    }

    public static JsonObject object() {
        return new JsonObject();
    }

    public static JsonArray array() {
        return new JsonArray();
    }

    /** {@code a.b.c} lookup; returns null when any segment is missing. */
    public static JsonElement path(JsonObject root, String dotted) {
        JsonElement cur = root;
        for (String seg : dotted.split("\\.")) {
            if (cur == null || !cur.isJsonObject()) {
                return null;
            }
            cur = cur.getAsJsonObject().get(seg);
        }
        return cur;
    }

    public static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsString();
    }

    public static int intOr(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsInt();
    }

    public static boolean boolOr(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsBoolean();
    }

    public static JsonObject objOrNull(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray arrOrNull(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }
}
