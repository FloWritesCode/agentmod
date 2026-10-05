package dev.agentmod.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

public final class Json {
	public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	public static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

	private Json() {
	}

	public static JsonObject parseObject(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		JsonElement element = JsonParser.parseString(text);
		return element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	public static JsonElement path(JsonElement root, String... keys) {
		JsonElement current = root;
		for (String key : keys) {
			if (current == null || !current.isJsonObject()) {
				return null;
			}
			current = current.getAsJsonObject().get(key);
		}
		return current == null || current.isJsonNull() ? null : current;
	}

	public static JsonObject obj(JsonElement root, String... keys) {
		JsonElement e = path(root, keys);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	public static JsonArray arr(JsonElement root, String... keys) {
		JsonElement e = path(root, keys);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	public static String str(JsonElement root, String... keys) {
		JsonElement e = path(root, keys);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	public static long lng(JsonElement root, long fallback, String... keys) {
		JsonElement e = path(root, keys);
		if (e instanceof JsonPrimitive p) {
			if (p.isNumber()) {
				return p.getAsLong();
			}
			if (p.isString()) {
				try {
					return Long.parseLong(p.getAsString());
				} catch (NumberFormatException ignored) {
					return fallback;
				}
			}
		}
		return fallback;
	}

	public static boolean bool(JsonElement root, String... keys) {
		JsonElement e = path(root, keys);
		return e instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
	}

	public static JsonObject object(Object... keyValues) {
		JsonObject o = new JsonObject();
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			String key = (String) keyValues[i];
			Object value = keyValues[i + 1];
			switch (value) {
				case null -> {
				}
				case JsonElement je -> o.add(key, je);
				case String s -> o.addProperty(key, s);
				case Number n -> o.addProperty(key, n);
				case Boolean b -> o.addProperty(key, b);
				default -> o.add(key, GSON.toJsonTree(value));
			}
		}
		return o;
	}

	public static JsonArray array(JsonElement... elements) {
		JsonArray a = new JsonArray();
		for (JsonElement e : elements) {
			a.add(e);
		}
		return a;
	}

	public static JsonArray strings(String... values) {
		JsonArray a = new JsonArray();
		for (String v : values) {
			a.add(v);
		}
		return a;
	}
}
