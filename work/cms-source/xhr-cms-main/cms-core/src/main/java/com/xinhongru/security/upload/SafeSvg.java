package com.xinhongru.security.upload;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** 只接受静态 SVG 图形，禁止脚本、外部资源和 XML 实体。 */
public final class SafeSvg {
    private static final Set<String> ELEMENTS =
            Set.of(
                    "svg",
                    "g",
                    "defs",
                    "path",
                    "rect",
                    "circle",
                    "ellipse",
                    "line",
                    "polyline",
                    "polygon",
                    "text",
                    "tspan",
                    "title",
                    "desc",
                    "linearGradient",
                    "radialGradient",
                    "stop",
                    "clipPath",
                    "mask",
                    "pattern",
                    "use",
                    "symbol",
                    "style",
                    "filter",
                    "feGaussianBlur",
                    "feOffset",
                    "feBlend",
                    "feColorMatrix",
                    "feMerge",
                    "feMergeNode",
                    "feFlood",
                    "feComposite");
    private static final Pattern URL =
            Pattern.compile("url\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private SafeSvg() {}

    public static byte[] read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
        if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("SVG不能超过2MB");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(
                    new DefaultHandler() {
                        @Override
                        public void fatalError(SAXParseException failure) throws SAXException {
                            throw failure;
                        }
                    });
            var document = builder.parse(new ByteArrayInputStream(bytes));
            Element root = document.getDocumentElement();
            if (!"svg".equals(root.getLocalName())) throw new IllegalArgumentException("SVG根元素无效");
            for (Node child = document.getFirstChild();
                    child != null;
                    child = child.getNextSibling()) {
                if (child.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE)
                    throw new IllegalArgumentException("SVG不允许处理指令");
            }
            validate(root, 0);
            var transformers = TransformerFactory.newInstance();
            transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var output = new ByteArrayOutputStream();
            transformers
                    .newTransformer()
                    .transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("SVG格式无效或包含不安全内容", failure);
        }
    }

    private static void validate(Element element, int depth) {
        if (depth > 128
                || !ELEMENTS.contains(element.getLocalName())
                || !"http://www.w3.org/2000/svg".equals(element.getNamespaceURI()))
            throw new IllegalArgumentException("SVG包含不支持的元素");
        var attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attr = attributes.item(i);
            String name = attr.getLocalName().toLowerCase(Locale.ROOT);
            String value = attr.getNodeValue().trim();
            if (name.startsWith("on")
                    || "base".equals(name)
                    || ("href".equals(name) && !value.startsWith("#")))
                throw new IllegalArgumentException("SVG不允许事件或外部资源");
            safeStyle(value);
        }
        if ("style".equals(element.getLocalName())) safeStyle(element.getTextContent());
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element nested) validate(nested, depth + 1);
            else if (child.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE)
                throw new IllegalArgumentException("SVG不允许处理指令");
        }
    }

    private static void safeStyle(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("@import")
                || lower.contains("javascript:")
                || lower.contains("expression(")
                || lower.contains("\\")) throw new IllegalArgumentException("SVG样式包含不安全内容");
        var matches = URL.matcher(value);
        while (matches.find()) {
            String ref = matches.group(1).trim().replace("\"", "").replace("'", "");
            if (!ref.startsWith("#")) throw new IllegalArgumentException("SVG不允许外部资源");
        }
    }
}
