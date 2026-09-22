package io.github.baljinnyamday.xyp;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Turns an XML payload into a tree of element name to value, recursively:
 *
 * <ul>
 *   <li>an element with children becomes an unmodifiable {@code Map<String, Object>} in document
 *       order;
 *   <li>repeated sibling names become an unmodifiable {@code List<Object>};
 *   <li>a leaf becomes its trimmed text, or {@code null} when that is empty;
 *   <li>an element with {@code xsi:nil="true"} (or {@code "1"}) becomes {@code null}.
 * </ul>
 *
 * Namespace prefixes and all other attributes are ignored.
 */
final class XmlTree {

  private static final String NIL_ATTRIBUTE = "nil";

  private XmlTree() {}

  /**
   * Parses the payload into the map of its top-level elements.
   *
   * @throws XypResponseException when the payload is not valid XML, or holds a DTD
   */
  static Map<String, Object> parse(byte[] payload) {
    // A body without any markup (empty, or a plain-text error page) holds no elements. Go's
    // decoder reads it as an empty document, and so does this one, so the caller reports the
    // missing <return> element in both SDKs.
    if (!containsMarkup(payload)) {
      return Map.of();
    }
    XMLStreamReader reader = null;
    try {
      reader = newFactory().createXMLStreamReader(new ByteArrayInputStream(payload));
      Map<String, Object> document = new LinkedHashMap<>();
      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.DTD) {
          // SOAP forbids DTDs, and refusing them outright rules out entity-expansion attacks.
          throw invalidXml();
        }
        if (event == XMLStreamConstants.START_ELEMENT) {
          String name = reader.getLocalName();
          addChild(document, name, readElement(reader));
        }
      }
      return freeze(document);
    } catch (XypResponseException refused) {
      throw refused;
    } catch (XMLStreamException | RuntimeException malformed) {
      // The parser's own message can quote the payload, which is citizen data.
      throw invalidXml();
    } finally {
      close(reader);
    }
  }

  /**
   * A new factory for every document: factories are not guaranteed to be thread-safe, and the
   * default one is used so that no system property or classpath entry can swap the parser.
   */
  private static XMLInputFactory newFactory() {
    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    return factory;
  }

  /** Reads the content of the element whose start tag the reader is on. */
  private static Object readElement(XMLStreamReader reader) throws XMLStreamException {
    if (isNil(reader)) {
      skipElement(reader);
      return null;
    }
    StringBuilder text = new StringBuilder();
    Map<String, Object> children = new LinkedHashMap<>();
    while (true) {
      switch (reader.next()) {
        case XMLStreamConstants.START_ELEMENT -> {
          String name = reader.getLocalName();
          addChild(children, name, readElement(reader));
        }
        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
            text.append(reader.getText());
        case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> throw invalidXml();
        case XMLStreamConstants.END_ELEMENT -> {
          if (!children.isEmpty()) {
            return children;
          }
          String trimmed = trimSpace(text);
          return trimmed.isEmpty() ? null : trimmed;
        }
        default -> {
          // Comments and processing instructions carry no data.
        }
      }
    }
  }

  private static void skipElement(XMLStreamReader reader) throws XMLStreamException {
    int depth = 1;
    while (depth > 0) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        depth++;
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        depth--;
      }
    }
  }

  private static boolean isNil(XMLStreamReader reader) {
    for (int index = 0; index < reader.getAttributeCount(); index++) {
      if (NIL_ATTRIBUTE.equals(reader.getAttributeLocalName(index))) {
        String value = reader.getAttributeValue(index);
        if (value.equals("true") || value.equals("1")) {
          return true;
        }
      }
    }
    return false;
  }

  /** A repeated name turns into a list; {@link Repeated} marks the lists this parser made. */
  private static void addChild(Map<String, Object> parent, String name, Object value) {
    if (!parent.containsKey(name)) {
      parent.put(name, value);
      return;
    }
    Object existing = parent.get(name);
    if (existing instanceof Repeated repeated) {
      repeated.add(value);
      return;
    }
    Repeated repeated = new Repeated();
    repeated.add(existing);
    repeated.add(value);
    parent.put(name, repeated);
  }

  /** Makes the finished tree unmodifiable, all the way down. */
  private static Map<String, Object> freeze(Map<String, Object> map) {
    map.replaceAll((name, value) -> freezeValue(value));
    return Collections.unmodifiableMap(map);
  }

  @SuppressWarnings("unchecked") // only this class builds the maps, always as Map<String, Object>
  private static Object freezeValue(Object value) {
    if (value instanceof Repeated repeated) {
      List<Object> items = new ArrayList<>(repeated.size());
      for (Object item : repeated) {
        items.add(freezeValue(item));
      }
      return Collections.unmodifiableList(items);
    }
    if (value instanceof Map<?, ?> map) {
      return freeze((Map<String, Object>) map);
    }
    return value;
  }

  /** The whitespace Go's {@code strings.TrimSpace} removes: {@code unicode.IsSpace}. */
  private static String trimSpace(CharSequence text) {
    int start = 0;
    int end = text.length();
    while (start < end && isSpace(text.charAt(start))) {
      start++;
    }
    while (end > start && isSpace(text.charAt(end - 1))) {
      end--;
    }
    return text.subSequence(start, end).toString();
  }

  private static boolean isSpace(char c) {
    return switch (c) {
      case '\t', '\n', '\u000B', '\f', '\r', ' ', '\u0085', ' ', ' ', ' ', ' ', ' ', ' ', '　' ->
          true;
      default -> c >= ' ' && c <= ' ';
    };
  }

  private static boolean containsMarkup(byte[] payload) {
    for (byte b : payload) {
      if (b == '<') {
        return true;
      }
    }
    return false;
  }

  private static void close(XMLStreamReader reader) {
    if (reader == null) {
      return;
    }
    try {
      reader.close();
    } catch (XMLStreamException ignored) {
      // Nothing is left to release: the payload is an in-memory array.
    }
  }

  static XypResponseException invalidXml() {
    return new XypResponseException("XYP returned a response that is not valid XML", 0);
  }

  /** The list behind a repeated element name while the document is still being read. */
  private static final class Repeated extends ArrayList<Object> {
    private static final long serialVersionUID = 1L;
  }
}
