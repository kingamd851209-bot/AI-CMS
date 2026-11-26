package com.xinhongru.cms.template;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 从有限的 JavaScript 字面量中读取静态证据。 */
public final class StaticScriptLiterals {
    private StaticScriptLiterals() {}

    record Token(String text, boolean quoted) {}

    public record SvgInsertion(String selector, String markup) {}

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern SELECTOR = Pattern.compile("[.#][A-Za-z][A-Za-z0-9_-]*");
    private static final List<Token> SVG_INSERTION =
            tokens(
                    "var __name__ = '__svg__'; $(function () { $('.__target__').html(__name__);"
                        + " });",
                    new ArrayList<>());

    public static SvgInsertion svgInsertion(String script) {
        if (!script.contains("<svg") || !script.contains(".html")) return null;
        List<Token> input = tokens(script, new ArrayList<>());
        int index = 0;
        String name = null, markup = null, selector = null;
        for (Token expected : SVG_INSERTION) {
            if (expected.text().equals(";")
                    && (index == input.size() || !input.get(index).equals(expected))) continue;
            if (index == input.size()) return null;
            Token actual = input.get(index++);
            if (actual.quoted() != expected.quoted()) return null;
            switch (expected.text()) {
                case "__name__" -> {
                    if (name == null && IDENTIFIER.matcher(actual.text()).matches())
                        name = actual.text();
                    if (!actual.text().equals(name)) return null;
                }
                case "__svg__" -> markup = actual.text();
                case ".__target__" -> {
                    if (!SELECTOR.matcher(actual.text()).matches()) return null;
                    selector = actual.text();
                }
                default -> {
                    if (!actual.equals(expected)) return null;
                }
            }
        }
        return index == input.size() && markup != null && markup.startsWith("<svg")
                ? new SvgInsertion(selector, markup)
                : null;
    }

    /** 只剔除注释和字符串示例并登记字面量调用；不是JS语义解析器。 */
    static List<Token> tokens(String script, List<String> warnings) {
        var tokens = new ArrayList<Token>();
        for (int i = 0; i < script.length(); ) {
            if (tokens.size() >= 20000) {
                warnings.add("脚本词法取证超过20000token，后续未分析");
                break;
            }
            char c = script.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '/') {
                i = script.indexOf('\n', i + 2);
                if (i < 0) break;
                continue;
            }
            if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                if (end < 0) break;
                i = end + 2;
                continue;
            }
            if (c == '/') {
                // 正则字面量内也可能出现看似调用的文本；除法歧义保守跳过，不推断JS语义。
                boolean closed = false, characterClass = false;
                i++;
                while (i < script.length()) {
                    char next = script.charAt(i++);
                    if (next == '\\' && i < script.length()) {
                        i++;
                        continue;
                    }
                    if (next == '\n' || next == '\r') break;
                    if (next == '[') characterClass = true;
                    else if (next == ']') characterClass = false;
                    else if (next == '/' && !characterClass) {
                        closed = true;
                        break;
                    }
                }
                tokens.add(new Token("<opaque-regex-or-division>", false));
                if (!closed && !warnings.contains("除法或未闭合正则片段未取证，内部不作为初始化证据"))
                    warnings.add("除法或未闭合正则片段未取证，内部不作为初始化证据");
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                var value = new StringBuilder();
                boolean escaped = false, closed = false;
                i++;
                while (i < script.length()) {
                    char next = script.charAt(i++);
                    if (next == quote) {
                        closed = true;
                        break;
                    }
                    if (next == '\\' && i < script.length()) {
                        escaped = true;
                        i++;
                    } else value.append(next);
                }
                tokens.add(
                        new Token(
                                quote != '`' && !escaped && closed ? value.toString() : "", true));
                continue;
            }
            if (Character.isJavaIdentifierStart(c) || c == '$') {
                int start = i++;
                while (i < script.length()
                        && (Character.isJavaIdentifierPart(script.charAt(i))
                                || script.charAt(i) == '$')) i++;
                tokens.add(new Token(script.substring(start, i), false));
            } else {
                tokens.add(new Token(String.valueOf(c), false));
                i++;
            }
        }
        return tokens;
    }
}
