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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class XmlFileComparator {
    private static final Pattern ENCODING = Pattern.compile("encoding\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);

    private XmlFileComparator() {
    }

    static ContentComparison compare(Path original, Path comparison, int detailLimit) throws Exception {
        byte[] leftBytes = Files.readAllBytes(original);
        byte[] rightBytes = Files.readAllBytes(comparison);
        Document left = parse(leftBytes);
        Document right = parse(rightBytes);
        List<String> leftCanonical = canonical(left);
        List<String> rightCanonical = canonical(right);
        boolean semanticEqual = leftCanonical.equals(rightCanonical);

        List<String> details = new ArrayList<>();
        details.add("Exact XML bytes: different.");
        details.add(semanticEqual
                ? "Structural XML comparison: equal (only exact representation changed)."
                : "Structural XML comparison: different.");
        if (!semanticEqual) {
            appendBounded(details, TextDiff.lines(String.join("\n", leftCanonical),
                    String.join("\n", rightCanonical), detailLimit), detailLimit);
        } else {
            details.add("Exact text differences:");
            appendBounded(details, TextDiff.lines(decode(leftBytes), decode(rightBytes), detailLimit), detailLimit);
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

    private static List<String> canonical(Document document) {
        List<String> output = new ArrayList<>();
        appendElement(document.getDocumentElement(), "", 1, output);
        return output;
    }

    private static void appendElement(Element element, String parentPath, int index, List<String> output) {
        String name = expandedName(element);
        String path = parentPath + "/" + name + "[" + index + "]";
        output.add(path);

        NamedNodeMap map = element.getAttributes();
        List<Attr> attributes = new ArrayList<>();
        for (int i = 0; i < map.getLength(); i++) {
            Attr attr = (Attr) map.item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())) attributes.add(attr);
        }
        attributes.sort(Comparator.comparing(XmlFileComparator::expandedName));
        for (Attr attr : attributes) output.add(path + "/@" + expandedName(attr) + " = " + attr.getValue());

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                appendElement((Element) child, path, siblingIndex(child), output);
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                if (!child.getNodeValue().trim().isEmpty()) output.add(path + "/#text = " + child.getNodeValue());
            } else if (child.getNodeType() == Node.COMMENT_NODE) {
                output.add(path + "/#comment = " + child.getNodeValue());
            }
        }
    }

    private static int siblingIndex(Node node) {
        int index = 1;
        for (Node previous = node.getPreviousSibling(); previous != null; previous = previous.getPreviousSibling()) {
            if (previous.getNodeType() == Node.ELEMENT_NODE && expandedName(previous).equals(expandedName(node))) index++;
        }
        return index;
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
