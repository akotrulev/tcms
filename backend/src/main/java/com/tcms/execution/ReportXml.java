package com.tcms.execution;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * <strong>The only place in this application that parses XML, and the only place that constructs a
 * {@link DocumentBuilderFactory}.</strong>
 *
 * <h2>Why that matters more than the six lines it saves</h2>
 *
 * <p>This endpoint accepts an XML document, over the network, from a credential holder — and the
 * default JAXP configuration resolves external entities. A report opening with
 *
 * <pre>{@code <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/tcms/tcms.env">]>}</pre>
 *
 * <p>is read by the default parser as an instruction to inline that file, and the resulting string
 * lands in a test name, which is stored, matched and served back. That is the production signing
 * key, the database password and the SMTP credentials, exfiltrated through a feature working exactly
 * as designed. §108 is the same lesson from the other end: a configuration default that is wrong in
 * the reassuring direction, past every gate.
 *
 * <p>So the hardening is not a checklist item on each parser — <strong>a second construction site is
 * the whole of the vulnerability</strong>, because it is the one that will be written by somebody
 * adding a fifth format who does not know this file exists. There is one factory method, both XML
 * parsers call it, and {@code ReportParserTest} asserts a DOCTYPE payload is refused rather than
 * trusting that the flags are still set.
 *
 * <p>Four settings and each closes a different door: {@code disallow-doctype-decl} refuses the
 * declaration outright; the two {@code external-*-entities} features refuse resolution; and
 * {@link DocumentBuilderFactory#setXIncludeAware(boolean)} with
 * {@link DocumentBuilderFactory#setExpandEntityReferences(boolean)} close the two routes that are
 * not spelled "entity".
 *
 * <p><strong>The overlap between them was measured, not assumed, and the two halves are not
 * redundant.</strong> Setting {@code disallow-doctype-decl} to false leaves the external-entity test
 * GREEN — the {@code external-*-entities} flags and {@code ACCESS_EXTERNAL_DTD} still refuse the
 * file read on their own — and reddens only the entity-<em>expansion</em> test, which is a
 * denial-of-service rather than a disclosure and is stopped by nothing else here. So the line to
 * keep if only one could be kept is the external-entity pair, and the DOCTYPE ban is what stands
 * between this endpoint and a billion-laughs expansion. Relaxing either costs something specific.
 *
 * <h2>DOM rather than StAX</h2>
 *
 * <p>Both report dialects need to walk <em>into</em> an element for its children — a
 * {@code <testcase>}'s {@code <failure>}, a {@code <test-method>}'s {@code <exception>} — and a
 * streaming parser expresses that as a state machine per format. The payload is already capped at
 * 1 MiB by {@code IngestionEdit}, so the memory argument for streaming does not apply, and the
 * state machine is where a parser acquires the bugs that make a report silently short.
 */
final class ReportXml {

    private ReportXml() {
    }

    /** A report that could not be read as XML at all — carried to the caller as a field error. */
    static final class MalformedReportException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MalformedReportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Parses {@code report} into a DOM with external entity resolution disabled.
     *
     * @throws MalformedReportException for anything that is not well-formed XML, <em>including</em>
     *                                  a document the hardening refuses — a caller sending a DOCTYPE
     *                                  gets "this is not a report we can read", not a description of
     *                                  what the parser declined to do for them
     */
    static Document parse(String report) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            // No EntityResolver fallback: with the features above nothing should reach one, and an
            // resolver returning an empty stream would make a refused document parse as a valid
            // empty one — a report that ingests zero tests and reports success.
            Document document =
                    builder.parse(new ByteArrayInputStream(report.getBytes(StandardCharsets.UTF_8)));
            document.getDocumentElement().normalize();
            return document;

        } catch (ParserConfigurationException cause) {
            // The features above are all supported by the JDK's built-in parser, so this is a
            // wiring fault rather than a bad payload — and it must not be reported to the caller as
            // a malformed report, which would send them to fix a file that is fine.
            throw new IllegalStateException("The XML parser could not be hardened.", cause);
        } catch (Exception cause) {
            throw new MalformedReportException("That report could not be read as XML.", cause);
        }
    }

    /** Every {@code <tag>} element anywhere in the document, in document order. */
    static List<Element> elements(Document document, String tag) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            found.add((Element) nodes.item(i));
        }
        return found;
    }

    /** The direct children of {@code parent} named {@code tag} — never grandchildren. */
    static List<Element> children(Element parent, String tag) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(node.getNodeName())) {
                found.add((Element) node);
            }
        }
        return found;
    }

    /** An attribute's value, or null when absent or blank — absent and empty are one case here. */
    static String attribute(Element element, String name) {
        if (!element.hasAttribute(name)) {
            return null;
        }
        String value = element.getAttribute(name).trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * The element's message: its {@code message} attribute, its text content, or null.
     *
     * <p>Both, because runners disagree — Surefire puts the assertion in {@code message} and the
     * stack trace in the body, and some emit only the body. Joined rather than chosen so a failure
     * never arrives with an empty comment on a report that clearly said something.
     */
    static String message(Element element) {
        String attribute = attribute(element, "message");
        String body = element.getTextContent() == null ? null : element.getTextContent().trim();
        if (attribute == null) {
            return body == null || body.isEmpty() ? null : body;
        }
        if (body == null || body.isEmpty() || body.startsWith(attribute)) {
            return attribute;
        }
        return attribute + "\n" + body;
    }

    /**
     * Seconds as a decimal string ({@code time="1.234"}) to whole milliseconds, or null.
     *
     * <p>Unparseable is null rather than an error: a duration nobody reads yet is not worth failing
     * a whole report over, and {@link IngestedResult#durationMs()} is documented as optional.
     */
    static Long secondsToMillis(String seconds) {
        if (seconds == null) {
            return null;
        }
        try {
            return Math.round(Double.parseDouble(seconds) * 1000);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
