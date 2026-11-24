package com.on.ebx.connect.release;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Deterministic contract checks for the packaged, user-facing release notes. */
public final class ReleaseNotesManifestTest {
  private static final String RESOURCE = "/com/on/ebx/connect/release/release-notes.xml";
  private static final Pattern SEMVER = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?");
  private static final Set<String> CATEGORIES = new HashSet<String>();

  static {
    CATEGORIES.add("new");
    CATEGORIES.add("improved");
    CATEGORIES.add("fixed");
    CATEGORIES.add("security");
    CATEGORIES.add("known-issue");
    CATEGORIES.add("upgrade");
  }

  private ReleaseNotesManifestTest() {
  }

  public static void main(String[] args) throws Exception {
    InputStream input = ReleaseNotesManifestTest.class.getResourceAsStream(RESOURCE);
    require(input != null, "Missing packaged release notes: " + RESOURCE);

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    Document document;
    try {
      document = factory.newDocumentBuilder().parse(input);
    } finally {
      input.close();
    }

    Element root = document.getDocumentElement();
    require("releaseNotes".equals(root.getTagName()), "Root element must be releaseNotes");
    require("1".equals(root.getAttribute("schemaVersion")), "Unsupported release-notes schemaVersion");

    NodeList releases = root.getElementsByTagName("release");
    require(releases.getLength() > 0, "At least one release entry is required");
    Set<String> ids = new HashSet<String>();
    for (int index = 0; index < releases.getLength(); index++) {
      validateRelease((Element) releases.item(index), ids);
    }
    System.out.println("ReleaseNotesManifestTest passed (" + releases.getLength() + " entries)");
  }

  private static void validateRelease(Element release, Set<String> ids) {
    String id = requiredAttribute(release, "id");
    require(ids.add(id), "Duplicate release id: " + id);
    String kind = requiredAttribute(release, "kind");
    require("formal".equals(kind) || "development".equals(kind), "Invalid release kind: " + kind);
    require(hasNonBlankChild(release, "title"), "Release " + id + " requires a title");
    require(hasNonBlankChild(release, "summary"), "Release " + id + " requires a summary");

    if ("formal".equals(kind)) {
      String version = requiredAttribute(release, "version");
      require(SEMVER.matcher(version).matches(), "Formal release version must use SemVer: " + version);
      require(("v" + version).equals(requiredAttribute(release, "sourceTag")),
          "Formal release sourceTag must be v<version>");
      LocalDate.parse(requiredAttribute(release, "releasedOn"));
    } else {
      require(!release.hasAttribute("sourceTag"), "Development increments must not claim a Git tag");
      require(!release.hasAttribute("releasedOn"), "Development increments must not claim a release date");
    }

    NodeList sections = release.getElementsByTagName("section");
    require(sections.getLength() > 0, "Release " + id + " requires at least one section");
    for (int index = 0; index < sections.getLength(); index++) {
      Element section = (Element) sections.item(index);
      require(CATEGORIES.contains(requiredAttribute(section, "category")), "Unsupported release-note category");
      require(section.getElementsByTagName("item").getLength() > 0, "Release sections cannot be empty");
    }
  }

  private static boolean hasNonBlankChild(Element parent, String name) {
    NodeList children = parent.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())
          && !child.getTextContent().trim().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private static String requiredAttribute(Element element, String name) {
    String value = element.getAttribute(name).trim();
    require(!value.isEmpty(), element.getTagName() + " requires attribute " + name);
    return value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
