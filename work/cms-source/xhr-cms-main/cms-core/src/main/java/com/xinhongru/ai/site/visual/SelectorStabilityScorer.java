package com.xinhongru.ai.site.visual;

import org.jsoup.nodes.Element;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class SelectorStabilityScorer {
    private static final List<String> STABLE_DATA_ATTRIBUTES =
            List.of(
                    "data-cms-field",
                    "data-field",
                    "data-bind",
                    "data-binding",
                    "data-testid",
                    "data-test",
                    "data-id",
                    "data-action",
                    "data-key",
                    "data-name");
    private static final Pattern GENERATED_CLASS =
            Pattern.compile(
                    "(^|[-_])([a-f0-9]{6,}|\\d{4,}|css-[a-z0-9]+)($|[-_])",
                    Pattern.CASE_INSENSITIVE);

    public int score(Element element, String selector, String stableSelector, int repeatIndex) {
        int score = score(selector, stableSelector, repeatIndex);
        if (element != null
                && hasStableDataAttribute(element)
                && containsStableDataAttribute(
                        normalize(stableSelector).toLowerCase(Locale.ROOT))) {
            score = Math.max(score, 94);
        }
        return clamp(score);
    }

    public int score(String selector, String stableSelector, int repeatIndex) {
        String primary = normalize(stableSelector);
        String fallback = normalize(selector);
        String evaluated = primary.isBlank() ? fallback : primary;
        if (evaluated.isBlank()) {
            return 0;
        }
        String lower = evaluated.toLowerCase(Locale.ROOT);
        int score;
        if (lower.startsWith("#") && !looksGenerated(lower)) {
            score = 100;
        } else if (containsStableDataAttribute(lower)) {
            score = 95;
        } else if (lower.contains("[aria-label=") || lower.contains("[name=")) {
            score = 88;
        } else if (hasUsefulClassSelector(evaluated)) {
            score = lower.contains(">") ? 68 : 78;
        } else {
            score = 58;
        }
        int depthPenalty = count(evaluated, '>');
        int nthPenalty = countOccurrences(lower, ":nth-");
        score -= depthPenalty * 4;
        score -= nthPenalty * 10;
        if (looksGenerated(lower)) {
            score -= 18;
        }
        if (repeatIndex > 0) {
            score -= Math.min(25, repeatIndex * 5);
        }
        return clamp(score);
    }

    public boolean hasStableDataAttribute(Element element) {
        return STABLE_DATA_ATTRIBUTES.stream()
                .anyMatch(attribute -> !element.attr(attribute).isBlank());
    }

    public List<String> stableDataAttributes() {
        return STABLE_DATA_ATTRIBUTES;
    }

    private boolean containsStableDataAttribute(String selector) {
        return STABLE_DATA_ATTRIBUTES.stream()
                .anyMatch(attribute -> selector.contains("[" + attribute + "="));
    }

    private boolean hasUsefulClassSelector(String selector) {
        return Arrays.stream(selector.split("[>\\s]+"))
                .anyMatch(
                        part ->
                                part.chars().filter(ch -> ch == '.').count() >= 1
                                        && !looksGenerated(part));
    }

    private boolean looksGenerated(String selector) {
        return GENERATED_CLASS.matcher(selector).find();
    }

    private int count(String value, char needle) {
        int total = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == needle) {
                total++;
            }
        }
        return total;
    }

    private int countOccurrences(String value, String needle) {
        int total = 0;
        int from = 0;
        while (from < value.length()) {
            int index = value.indexOf(needle, from);
            if (index < 0) {
                return total;
            }
            total++;
            from = index + needle.length();
        }
        return total;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
