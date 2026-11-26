package com.xinhongru.cms.template;

import static com.xinhongru.cms.template.StaticScriptLiterals.tokens;

import com.xinhongru.cms.template.StaticScriptLiterals.Token;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

/** 只登记已知库契约和字面量调用的静态证据，不执行或推断任意JavaScript。 */
public final class CounterScriptInputs {
    private CounterScriptInputs() {}

    public record NumericPart(int start, int end, String value, String prefix, String suffix) {}

    public static boolean knownCounter(TemplateContentUnitsService.Source source) {
        return source != null
                && source.scriptInput() != null
                && ("jquery.countUp/text-v1".equals(source.scriptInput().reader())
                                && Set.of("text", "text-affix")
                                        .contains(source.scriptInput().input())
                        || "wormDigitalScroll/innerHTML-v1".equals(source.scriptInput().reader())
                                && "text".equals(source.scriptInput().input()));
    }

    /** reader声明的数值输入契约；普通/旧counter来源保持既有格式。 */
    public static String inputType(TemplateContentUnitsService.Source source) {
        return source != null
                        && source.scriptInput() != null
                        && "wormDigitalScroll/innerHTML-v1".equals(source.scriptInput().reader())
                ? com.xinhongru.content.model.CounterNumbers.DECIMAL_INPUT_TYPE
                : com.xinhongru.content.model.CounterNumbers.INPUT_TYPE;
    }

    /** 仅承认开发者显式选中的唯一数值区间；固定限定符继续保留在原始证据中。 */
    public static NumericPart numericPart(
            TemplateContentUnitsService.Source source,
            TemplateContentUnitsService.Reference reference) {
        if (!knownCounter(source)
                || !"SCRIPT_PENDING".equals(source.kind())
                || !"text-affix".equals(source.scriptInput().input())) return null;
        var matcher = java.util.regex.Pattern.compile("[0-9][0-9,.]*").matcher(source.rawValue());
        if (!matcher.find()) return null;
        int start = matcher.start(), end = matcher.end();
        String value = matcher.group();
        if (matcher.find()
                || start != reference.start()
                || end != reference.end()
                || (start == 0 && end == source.rawValue().length())
                || start > 20
                || source.rawValue().length() - end > 20
                || !com.xinhongru.content.model.CounterNumbers.supported(inputType(source), value))
            return null;
        return new NumericPart(
                start,
                end,
                value,
                source.rawValue().substring(0, start),
                source.rawValue().substring(end));
    }

    public static boolean counterReference(
            TemplateContentUnitsService.Source source,
            TemplateContentUnitsService.Reference reference) {
        return knownCounter(source)
                && ("TEXT".equals(source.kind())
                                && reference.start() == 0
                                && reference.end() == source.rawValue().length()
                                && com.xinhongru.content.model.CounterNumbers.supported(
                                        inputType(source), source.rawValue())
                        || numericPart(source, reference) != null);
    }

    private static final String COUNT_UP_SHA =
            "fc99e7ebd59b4822499e0b58c6366456ec4a99d8fe7816d1f445fa5f0e70c8e2";
    private static final String WORM_SHA =
            "964edacbdb65bd55ecb926f36794d48f1158e2dd47680bac95743ea4952b4371";
    private static final int FILE_LIMIT = 2 * 1024 * 1024;
    private static final int TOTAL_LIMIT = 4 * 1024 * 1024;

    public record ScriptDependency(String path, String sha256) {}

    public record ScriptInput(
            String reader,
            String input,
            String readExpression,
            String initialization,
            String initialDisplay,
            String reason,
            List<ScriptDependency> dependencies) {}

    public record Reading(
            String selector, String rawValue, ScriptInput scriptInput, boolean pending) {}

    public record Inspection(List<Reading> readings, List<String> warnings) {}

    private record File(String path, String text, String sha256, String problem) {}

    private record Call(String selector, File file, boolean worm) {}

    public static Inspection inspect(
            String sourcePath, String html, Function<String, byte[]> reader) {
        var document = Jsoup.parse(html);
        var warnings = new ArrayList<String>();
        var files = new ArrayList<File>();
        int count = 0, total = 0;
        for (Element script : document.select("script")) {
            if (++count > 20) {
                warnings.add("脚本超过20项，后续未取证，脚本统计入口仍待人工核对");
                break;
            }
            if (!script.attr("type").isBlank()
                    && !Set.of("text/javascript", "application/javascript", "module")
                            .contains(script.attr("type"))) continue;
            File file;
            if (script.hasAttr("src")) {
                String reference = script.attr("src");
                String path = reference.length() > 2048 ? null : localPath(sourcePath, reference);
                if (reference.length() > 2048)
                    file = new File("<script-" + count + ">", "", null, "脚本地址超过2048字，未读取");
                else if (path == null) file = new File(reference, "", null, "外部或越界脚本不读取");
                else {
                    try {
                        byte[] bytes = reader.apply(path);
                        if (bytes.length > FILE_LIMIT || total + bytes.length > TOTAL_LIMIT)
                            file = new File(path, "", null, "脚本超过单文件2MB或总4MB取证限制");
                        else {
                            total += bytes.length;
                            file =
                                    new File(
                                            path,
                                            new String(bytes, StandardCharsets.UTF_8),
                                            sha(bytes),
                                            null);
                        }
                    } catch (RuntimeException error) {
                        String message = String.valueOf(error.getMessage());
                        file =
                                new File(
                                        path,
                                        "",
                                        null,
                                        message.contains("限") || message.contains("超过")
                                                ? "脚本读取超限"
                                                : "本地脚本缺失或不可读");
                    }
                }
            } else {
                String text = script.data();
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                String path = sourcePath + "#inline-" + count;
                if (bytes.length > FILE_LIMIT || total + bytes.length > TOTAL_LIMIT)
                    file = new File(path, "", null, "内联脚本取证超限");
                else {
                    total += bytes.length;
                    file = new File(path, text, sha(bytes), null);
                }
            }
            files.add(file);
            if (file.problem() != null) warnings.add(file.problem() + "：" + file.path());
        }
        File vendor =
                files.stream()
                        .filter(f -> COUNT_UP_SHA.equals(f.sha256()))
                        .findFirst()
                        .orElse(null);
        File wormVendor =
                files.stream().filter(f -> WORM_SHA.equals(f.sha256())).findFirst().orElse(null);
        var calls = new ArrayList<Call>();
        for (File file : files) {
            if (COUNT_UP_SHA.equals(file.sha256()) || WORM_SHA.equals(file.sha256())) continue;
            var fileWarnings = new ArrayList<String>();
            List<Token> tokens = tokens(file.text(), fileWarnings);
            for (String warning : fileWarnings) warnings.add(warning + "：" + file.path());
            findWormCalls(tokens, file, calls, warnings);
            for (int i = 0; i + 6 < tokens.size() && calls.size() < 100; i++) {
                if (Set.of("$", "jQuery").contains(tokens.get(i).text())
                        && !tokens.get(i).quoted()
                        && tokens.get(i + 1).text().equals("(")
                        && tokens.get(i + 2).quoted()
                        && tokens.get(i + 3).text().equals(")")
                        && tokens.get(i + 4).text().equals(".")
                        && tokens.get(i + 5).text().equals("countUp")
                        && tokens.get(i + 6).text().equals("("))
                    calls.add(new Call(tokens.get(i + 2).text(), file, false));
            }
        }
        var eligible = Collections.newSetFromMap(new IdentityHashMap<Element, Boolean>());
        int scanned = 0;
        for (Element element : document.body().getAllElements()) {
            if (++scanned > 2000) break;
            int depth = 0;
            boolean safe = true;
            for (Element node = element; node != null; node = node.parent()) {
                if (++depth > 32
                        || (node.parent() != null && node.parent().childrenSize() > 2000)) {
                    safe = false;
                    break;
                }
            }
            if (safe) eligible.add(element);
        }
        var readings = new LinkedHashMap<String, Reading>();
        for (Call call : calls) {
            if (!call.selector().matches("[A-Za-z0-9_.#> ,\\-]{1,200}")) {
                warnings.add("统计初始化选择器不在可确认的字面量子集中，仍待处理");
                continue;
            }
            List<Element> targets;
            try {
                targets = document.select(call.selector());
            } catch (IllegalArgumentException invalid) {
                warnings.add("统计初始化选择器无法定位，仍待处理");
                continue;
            }
            for (Element element : targets) {
                if (!eligible.contains(element)
                        || readings.size() >= 100 && !readings.containsKey(element.cssSelector())) {
                    String limit = "统计来源超过100项或原DOM定位限制（2000节点/32层/2000同级），超出未取证";
                    if (!warnings.contains(limit)) warnings.add(limit);
                    continue;
                }
                String original =
                        element.childNodeSize() == 1
                                        && element.childNode(0) instanceof TextNode text
                                ? text.getWholeText()
                                : element.text();
                boolean truncated = original.length() > 10000;
                if (truncated) original = original.substring(0, 10000);
                String reason = null;
                boolean affixCandidate = false;
                if (truncated) reason = "统计目标原文超过10000字，截断证据不能绑定";
                else if ((call.worm() ? wormVendor : vendor) == null)
                    reason =
                            (call.worm() ? "wormDigitalScroll读取契约未确认：" : "countUp读取契约未确认：")
                                    + files.stream()
                                            .map(File::problem)
                                            .filter(Objects::nonNull)
                                            .findFirst()
                                            .orElse("插件版本未经验证");
                else if (element.childNodeSize() != 1
                        || !(element.childNode(0) instanceof TextNode))
                    reason = "脚本目标包含多个节点，须人工核对布局和读取范围";
                else if (!call.worm() && element.hasAttr("data-counterup-to"))
                    reason = "已知countUp缓存属性优先于文本；jQuery数值转换可使原插件报错，属性适配仍待处理";
                else if (call.worm()
                        && !com.xinhongru.content.model.CounterNumbers.supported(
                                com.xinhongru.content.model.CounterNumbers.DECIMAL_INPUT_TYPE,
                                original))
                    reason = "wormDigitalScroll仅支持已验证范围内的非负十进制纯文本数字；逗号、单位或HTML仍待处理";
                else if (!call.worm()
                        && !original.matches(
                                "(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")) {
                    reason = "原插件不支持目标中的限定符、单位或非规范数字，保留原文待人工适配";
                    affixCandidate = true;
                } else if (!com.xinhongru.content.model.CounterNumbers.supported(
                        call.worm()
                                ? com.xinhongru.content.model.CounterNumbers.DECIMAL_INPUT_TYPE
                                : com.xinhongru.content.model.CounterNumbers.INPUT_TYPE,
                        original)) reason = "统计原文超出已验证数值范围或精度，保留待处理，不能保证原插件动画";
                var dependencies =
                        files.stream()
                                .map(file -> new ScriptDependency(file.path(), file.sha256()))
                                .distinct()
                                .toList();
                String context =
                        reason == null
                                ? "已静态关联已验证"
                                        + (call.worm()
                                                ? "wormDigitalScroll innerHTML"
                                                : "countUp文本")
                                        + "reader；未执行脚本，业务含义须人工确认"
                                : reason;
                boolean verified = (call.worm() ? wormVendor : vendor) != null;
                var input =
                        new ScriptInput(
                                call.worm()
                                        ? (verified
                                                ? "wormDigitalScroll/innerHTML-v1"
                                                : "unverified-wormDigitalScroll")
                                        : (verified
                                                ? "jquery.countUp/text-v1"
                                                : "unverified-countUp"),
                                !verified
                                        ? "unconfirmed"
                                        : !call.worm() && element.hasAttr("data-counterup-to")
                                                ? "cached-attribute-unconfirmed"
                                                : affixCandidate ? "text-affix" : "text",
                                !verified
                                        ? null
                                        : call.worm()
                                                ? "el.innerHTML"
                                                : "e.data(\"counterupTo\", e.text())",
                                call.selector()
                                        + (call.worm()
                                                ? " -> new wormDigitalScroll({el: selector.get(i),"
                                                        + " time:1000})"
                                                : " -> countUp()"),
                                original,
                                context,
                                List.copyOf(dependencies));
                var previous = readings.get(element.cssSelector());
                if (previous != null
                        && !Objects.equals(previous.scriptInput().reader(), input.reader())) {
                    String conflict = "同一统计目标存在多种脚本读取契约，保留待处理，不覆盖既有约束";
                    input =
                            new ScriptInput(
                                    "conflicting-readers",
                                    "unconfirmed",
                                    null,
                                    input.initialization(),
                                    original,
                                    conflict,
                                    List.copyOf(dependencies));
                    reason = conflict;
                    warnings.add(conflict);
                }
                readings.put(
                        element.cssSelector(),
                        new Reading(element.cssSelector(), original, input, reason != null));
            }
        }
        for (Element canvas : document.select("canvas"))
            for (Element element : canvas.getAllElements()) {
                if (!eligible.contains(element) || readings.size() >= 100) break;
                String raw = element.outerHtml();
                String display = element.text();
                boolean truncated = raw.length() > 10000 || display.length() > 10000;
                if (raw.length() > 10000) raw = "<canvas>";
                if (display.length() > 10000) {
                    int end = 10000;
                    if (Character.isHighSurrogate(display.charAt(end - 1))) end--;
                    display = display.substring(0, end);
                }
                String reason = "Canvas/H5未发现稳定DOM数值入口，不能把回退显示值当目标；待人工适配绘制脚本";
                if (truncated) reason += "；Canvas原始证据或fallback文字超限已截断";
                var input =
                        new ScriptInput(
                                "unsupported-canvas", null, null, null, display, reason, List.of());
                readings.put(
                        element.cssSelector(),
                        new Reading(element.cssSelector(), raw, input, true));
            }
        return new Inspection(List.copyOf(readings.values()), List.copyOf(warnings));
    }

    /** 只接受样本的完整零起点单步循环，避免把孤立get(i)误认为遍历所有目标。 */
    private static void findWormCalls(
            List<Token> input, File file, List<Call> calls, List<String> warnings) {
        boolean found = false, candidate = false;
        for (Token token : input)
            if (!token.quoted() && "wormDigitalScroll".equals(token.text())) candidate = true;
        for (boolean comma : List.of(true, false)) {
            List<Token> pattern =
                    tokens(
                            "for(var"
                                + " __index__=0;__index__<$('__selector__').length;__index__++){new"
                                + " wormDigitalScroll({el:$('__selector__').get(__index__),time:1000"
                                    + (comma ? "," : "")
                                    + "});}",
                            new ArrayList<>());
            for (int start = 0;
                    start + pattern.size() <= input.size() && calls.size() < 100;
                    start++) {
                String selector = null, index = null;
                boolean matches = true;
                for (int j = 0; j < pattern.size(); j++) {
                    Token expected = pattern.get(j), actual = input.get(start + j);
                    if (expected.quoted() != actual.quoted()) {
                        matches = false;
                        break;
                    }
                    if ("__selector__".equals(expected.text())) {
                        if (selector == null) selector = actual.text();
                        else if (!selector.equals(actual.text())) {
                            matches = false;
                            break;
                        }
                    } else if ("__index__".equals(expected.text())) {
                        if (index == null && actual.text().matches("[A-Za-z_$][A-Za-z0-9_$]*"))
                            index = actual.text();
                        if (!actual.text().equals(index)) {
                            matches = false;
                            break;
                        }
                    } else if ("$".equals(expected.text())) {
                        if (!Set.of("$", "jQuery").contains(actual.text())) {
                            matches = false;
                            break;
                        }
                    } else if ("var".equals(expected.text())) {
                        if (!Set.of("var", "let").contains(actual.text())) {
                            matches = false;
                            break;
                        }
                    } else if (!expected.text().equals(actual.text())) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    calls.add(new Call(selector, file, true));
                    found = true;
                }
            }
        }
        if (candidate && !found)
            warnings.add("wormDigitalScroll初始化不在已验证字面量循环子集中，仍待人工处理：" + file.path());
    }

    private static String localPath(String source, String reference) {
        try {
            var uri = java.net.URI.create(reference.trim());
            if (uri.getScheme() != null
                    || uri.getRawAuthority() != null
                    || uri.getPath() == null
                    || uri.getPath().isBlank()) return null;
            String value = uri.getPath();
            Path base = Path.of(source).getParent();
            Path resolved =
                    (value.startsWith("/")
                                    ? Path.of(value.substring(1))
                                    : (base == null ? Path.of(value) : base.resolve(value)))
                            .normalize();
            String path = resolved.toString().replace('\\', '/');
            if (resolved.isAbsolute()
                    || path.equals("..")
                    || path.startsWith("../")
                    || path.contains(":")
                    || path.indexOf(0) >= 0) return null;
            return path;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
