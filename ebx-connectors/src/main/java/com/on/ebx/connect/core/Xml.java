package com.on.ebx.connect.core;

import java.io.StringReader;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * XML to JSON for the SOAP connector and XML APIs: namespaces are dropped, an element with child
 * elements becomes an object, repeated child names become an array, a text-only element becomes a
 * string, attributes become {@code @name} fields.
 */
public final class Xml
{
	private Xml()
	{
	}

	public static Document parse(final String pXml) throws Exception
	{
		if (pXml != null && pXml.toUpperCase(java.util.Locale.ROOT).contains("<!DOCTYPE"))
		{
			throw new IllegalArgumentException("XML document type declarations are not allowed");
		}
		final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setExpandEntityReferences(false);
		safeFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
		safeFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
		safeFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
		safeFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		try { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", ""); } catch (final Exception ignored) { /* JAXP 1.5 */ }
		try { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", ""); } catch (final Exception ignored) { /* JAXP 1.5 */ }
		try { factory.setXIncludeAware(false); } catch (final UnsupportedOperationException ignored) { /* old parser */ }
		return factory.newDocumentBuilder().parse(new InputSource(new StringReader(pXml)));
	}

	private static void safeFeature(final DocumentBuilderFactory pFactory, final String pFeature, final boolean pValue)
	{
		try { pFactory.setFeature(pFeature, pValue); } catch (final Exception ignored) { /* parser without this feature */ }
	}

	/** First descendant element whose local name matches (null when absent). */
	public static Element find(final Node pRoot, final String pLocalName)
	{
		if (pRoot == null)
		{
			return null;
		}
		final NodeList children = pRoot.getChildNodes();
		for (int i = 0; i < children.getLength(); i++)
		{
			final Node child = children.item(i);
			if (child instanceof Element)
			{
				if (pLocalName.equals(Xml.localName(child)))
				{
					return (Element) child;
				}
				final Element deeper = Xml.find(child, pLocalName);
				if (deeper != null)
				{
					return deeper;
				}
			}
		}
		return null;
	}

	public static String localName(final Node pNode)
	{
		final String local = pNode.getLocalName();
		if (local != null)
		{
			return local;
		}
		final String name = pNode.getNodeName();
		return name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
	}

	public static JsonNode toJson(final Element pElement)
	{
		final ObjectNode out = Json.object();
		final NamedNodeMap attributes = pElement.getAttributes();
		for (int i = 0; attributes != null && i < attributes.getLength(); i++)
		{
			final Node a = attributes.item(i);
			if (!a.getNodeName().startsWith("xmlns"))
			{
				out.put("@" + Xml.localName(a), a.getNodeValue());
			}
		}
		final NodeList children = pElement.getChildNodes();
		boolean hasElements = false;
		final StringBuilder text = new StringBuilder();
		for (int i = 0; i < children.getLength(); i++)
		{
			final Node child = children.item(i);
			if (child instanceof Element)
			{
				hasElements = true;
				final String name = Xml.localName(child);
				final JsonNode value = Xml.toJson((Element) child);
				final JsonNode existing = out.get(name);
				if (existing == null)
				{
					out.set(name, value);
				}
				else if (existing.isArray())
				{
					((ArrayNode) existing).add(value);
				}
				else
				{
					final ArrayNode list = out.putArray(name);
					list.add(existing);
					list.add(value);
				}
			}
			else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
			{
				text.append(child.getNodeValue());
			}
		}
		if (!hasElements)
		{
			final String value = text.toString().trim();
			if (out.size() == 0)
			{
				return Json.mapper().getNodeFactory().textNode(value);
			}
			out.put("#text", value);
		}
		return out;
	}
}
