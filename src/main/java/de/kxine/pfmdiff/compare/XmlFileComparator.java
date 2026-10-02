package de.kxine.pfmdiff.compare;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class XmlFileComparator {
    private static final long MAX_XML_BYTES = 16L * 1024 * 1024;
    private static final Pattern ENCODING = Pattern.compile("encoding\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);

    private XmlFileComparator() {
    }

    static ContentComparison compare(Path original, Path comparison, int detailLimit, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        if (Files.size(original) > MAX_XML_BYTES || Files.size(comparison) > MAX_XML_BYTES) {
            throw new IOException("XML structural comparison is limited to 16 MiB per file.");
        }
        byte[] leftBytes = Files.readAllBytes(original);
        checkCancelled(cancelled);
        byte[] rightBytes = Files.readAllBytes(comparison);
        checkCancelled(cancelled);
        if (leftBytes.length > MAX_XML_BYTES || rightBytes.length > MAX_XML_BYTES) {
            throw new IOException("XML structural comparison is limited to 16 MiB per file.");
        }
        Document left = parse(leftBytes);
        checkCancelled(cancelled);
        Document right = parse(rightBytes);
        checkCancelled(cancelled);
        List<String> leftCanonical = canonical(left, cancelled);
        List<String> rightCanonical = canonical(right, cancelled);
        boolean semanticEqual = leftCanonical.equals(rightCanonical);

        List<String> details = new ArrayList<>();
        details.add("Exact XML bytes: different.");
        details.add(semanticEqual
                ? "Structural XML comparison: equal (only exact representation changed)."
                : "Structural XML comparison: different.");
        if (!semanticEqual) {
            appendBounded(details, TextDiff.lines(String.join("\n", leftCanonical),
                    String.join("\n", rightCanonical), detailLimit, cancelled), detailLimit);
        } else {
            details.add("Exact text differences:");
            appendBounded(details, TextDiff.lines(decode(leftBytes), decode(rightBytes), detailLimit, cancelled), detailLimit);
        }
        return new ContentComparison(semanticEqual, details);
    }

    private static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(SAXParseException exception) throws SAXException { throw exception; }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException { throw exception; }
        });
        return builder.parse(new ByteArrayInputStream(bytes));
    }

    private static List<String> canonical(Document document, BooleanSupplier cancelled)
            throws ComparisonEngine.ComparisonCancelledException {
        List<String> output = new ArrayList<>();
        appendElement(document.getDocumentElement(), "", 1, output, false, cancelled);
        return output;
    }

    private static void appendElement(Element element, String parentPath, int index, List<String> output,
                                      boolean preserveSpace, BooleanSupplier cancelled)
            throws ComparisonEngine.ComparisonCancelledException {
        checkCancelled(cancelled);
        String name = expandedName(element);
        String path = parentPath + "/" + name + "[" + index + "]";
        output.add(path);
        String space = element.getAttributeNS(XMLConstants.XML_NS_URI, "space");
        if ("preserve".equals(space)) preserveSpace = true;
        else if ("default".equals(space)) preserveSpace = false;

        NamedNodeMap map = element.getAttributes();
        List<Attr> attributes = new ArrayList<>();
        for (int i = 0; i < map.getLength(); i++) {
            Attr attr = (Attr) map.item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())) attributes.add(attr);
        }
        attributes.sort(Comparator.comparing(XmlFileComparator::expandedName));
        for (Attr attr : attributes) output.add(path + "/@" + expandedName(attr) + " = " + attr.getValue());

        NodeList children = element.getChildNodes();
        boolean ignoreIndentation = !preserveSpace && hasIndentedElementOnlyContent(children);
        StringBuilder text = new StringBuilder();
        boolean explicitCdata = false;
        Map<String, Integer> siblingCounts = new HashMap<>();
        for (int i = 0; i < children.getLength(); i++) {
            checkCancelled(cancelled);
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
                explicitCdata |= child.getNodeType() == Node.CDATA_SECTION_NODE;
                continue;
            }
            appendText(output, path, text, ignoreIndentation && !explicitCdata);
            explicitCdata = false;
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                int sibling = siblingCounts.merge(expandedName(child), 1, Integer::sum);
                appendElement((Element) child, path, sibling, output, preserveSpace, cancelled);
            } else if (child.getNodeType() == Node.COMMENT_NODE) {
                output.add(path + "/#comment = " + child.getNodeValue());
            }
        }
        appendText(output, path, text, ignoreIndentation && !explicitCdata);
    }

    private static boolean hasIndentedElementOnlyContent(NodeList children) {
        boolean indented = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.CDATA_SECTION_NODE) return false;
            if (child.getNodeType() != Node.TEXT_NODE) continue;
            String value = child.getNodeValue();
            if (!value.isBlank()) return false;
            for (int j = 0; j + 1 < value.length(); j++) {
                if ((value.charAt(j) == '\n' || value.charAt(j) == '\r')
                        && (value.charAt(j + 1) == ' ' || value.charAt(j + 1) == '\t')) {
                    indented = true;
                }
            }
        }
        return indented;
    }

    private static void appendText(List<String> output, String path, StringBuilder text, boolean ignoreIndentation) {
        if (text.length() == 0) return;
        String value = text.toString();
        text.setLength(0);
        // Only collapse indentation when the parent is consistently pretty-printed element-only content.
        if (ignoreIndentation && value.isBlank() && (value.contains("\n") || value.contains("\r"))) return;
        output.add(path + "/#text = " + value);
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws ComparisonEngine.ComparisonCancelledException {
        if (cancelled != null && cancelled.getAsBoolean()) throw new ComparisonEngine.ComparisonCancelledException();
    }

    private static String expandedName(Node node) {
        String local = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
        String namespace = node.getNamespaceURI();
        return namespace == null || namespace.isEmpty() ? local : "{" + namespace + "}" + local;
    }

    private static String decode(byte[] bytes) {
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            offset = 3;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else {
            String declaration = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.US_ASCII);
            Matcher matcher = ENCODING.matcher(declaration);
            if (matcher.find()) {
                try { charset = Charset.forName(matcher.group(1)); } catch (Exception ignored) { }
            }
        }
        return charset.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    }

    private static void appendBounded(List<String> target, List<String> source, int limit) {
        int remaining = Math.max(0, limit - target.size());
        target.addAll(source.subList(0, Math.min(remaining, source.size())));
        if (source.size() > remaining) target.add("… additional details omitted from this report");
    }

    record ContentComparison(boolean semanticEqual, List<String> details) { }
}
