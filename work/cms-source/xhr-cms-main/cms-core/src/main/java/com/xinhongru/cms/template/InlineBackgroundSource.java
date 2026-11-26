package com.xinhongru.cms.template;

import java.net.URI;
import java.util.regex.Pattern;

/** 识别可独立替换的单条本地背景样式。 */
public final class InlineBackgroundSource {
    private static final Pattern SINGLE_BACKGROUND =
            Pattern.compile(
                    "(?i)^[ \t]*background-image[ \t]*:[ \t]*url\\(([^()]*)\\)[ \t]*;?[ \t]*$");

    private InlineBackgroundSource() {}

    public static boolean isBindable(String style, String url) {
        if (style == null
                || url == null
                || url.isBlank()
                || url.length() > 2048
                || style.indexOf('\\') >= 0
                || url.contains("'")
                || url.contains("\"")
                || url.codePoints().anyMatch(Character::isISOControl)) return false;
        var matched = SINGLE_BACKGROUND.matcher(style);
        if (!matched.matches()) return false;
        String value = matched.group(1).strip();
        if (value.length() >= 2
                && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'")))
            value = value.substring(1, value.length() - 1);
        if (!url.equals(value)) return false;
        try {
            var uri = URI.create(url);
            return !uri.isAbsolute()
                    && !url.startsWith("//")
                    && uri.getRawAuthority() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && uri.getPath() != null
                    && !uri.getPath().isBlank();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
