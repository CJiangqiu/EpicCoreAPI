package net.eca.util.health;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.eca.util.EcaLogger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable translation keys and arguments; localization happens only when a report is rendered. */
public record HealthReportText(String key, List<Object> arguments) {
    private static final String PREFIX = "health_report.eca.";
    private static final Map<String, Map<String, String>> LANGUAGES = new ConcurrentHashMap<>();
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?s|%%");

    public HealthReportText {
        arguments = Collections.unmodifiableList(Arrays.asList(arguments.toArray()));
    }

    public static HealthReportText tr(String key, Object... arguments) {
        return new HealthReportText(key, Arrays.asList(arguments));
    }

    public static String normalizeLanguage(String language) {
        String normalized = language == null ? "en_us" : language.toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_]{2,32}") ? normalized : "en_us";
    }

    public static String render(Object value, String language) {
        if (value instanceof HealthReportText text) {
            String fullKey = PREFIX + text.key();
            Map<String, String> fallback = LANGUAGES.computeIfAbsent("en_us", HealthReportText::load);
            Map<String, String> selected = LANGUAGES.computeIfAbsent(normalizeLanguage(language), HealthReportText::load);
            String pattern = selected.getOrDefault(fullKey, fallback.getOrDefault(fullKey, fullKey));
            Matcher matcher = PLACEHOLDER.matcher(pattern);
            StringBuilder result = new StringBuilder();
            int implicit = 0;
            while (matcher.find()) {
                String replacement;
                if (matcher.group().equals("%%")) replacement = "%";
                else {
                    int index;
                    try {
                        index = matcher.group(1) == null ? implicit++ : Integer.parseInt(matcher.group(1)) - 1;
                    } catch (NumberFormatException exception) {
                        index = -1;
                    }
                    replacement = index >= 0 && index < text.arguments().size()
                            ? render(text.arguments().get(index), language) : matcher.group();
                }
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(result);
            return result.toString();
        }
        if (value instanceof Iterable<?> values) {
            StringBuilder result = new StringBuilder();
            String separator = render(tr("list.separator"), language);
            for (Object element : values) {
                if (!result.isEmpty()) result.append(separator);
                result.append(render(element, language));
            }
            return result.toString();
        }
        return String.valueOf(value);
    }

    private static Map<String, String> load(String language) {
        // Server-written files cannot use a client's global Language singleton.
        try (InputStream stream = HealthReportText.class.getResourceAsStream("/assets/eca/lang/" + language + ".json")) {
            if (stream == null) return Map.of();
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
                Map<String, String> entries = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    if (entry.getKey().startsWith(PREFIX) && entry.getValue().isJsonPrimitive()
                            && entry.getValue().getAsJsonPrimitive().isString()) {
                        entries.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                return Map.copyOf(entries);
            }
        } catch (Exception exception) {
            EcaLogger.info("[HealthReport] language load failed: {} ({})", language, exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    /** Only an explicit language should be used for user-facing output. */
    @Override public String toString() { return render(this, "en_us"); }

    static final class Builder {
        private final String language;
        private final StringBuilder value = new StringBuilder(4096);

        Builder(String language) { this.language = language; }

        Builder append(Object part) {
            value.append(render(part, language));
            return this;
        }

        @Override public String toString() { return value.toString(); }
    }
}
