package com.on.ebx.connect.core;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Version-neutral, transport-independent OData v2/v4 primitives.  Connectors retain control of
 * authentication and HTTP policy; this class owns wire-shape differences that profiles should
 * not have to reimplement.
 */
public final class ODataSupport
{
	public enum Version { V2, V4 }
	public interface PageFetcher { JsonNode get(String pUrl) throws ConnectorException; }

	private static final Pattern V2_DATE = Pattern.compile("^/Date\\((-?[0-9]+)([+-][0-9]{4})?\\)/$");
	private static final String[] QUERY_OPTIONS = { "$select", "$expand", "$filter", "$orderby", "$top", "$skip", "$search", "$skiptoken", "$deltatoken", "$apply" };

	private ODataSupport() { }

	/** Builds the standard query options and applies version-specific count/format conventions. */
	public static Map<String, String> query(final Version pVersion, final Map<String, String> pInputs,
		final boolean pCollection, final String pMaximumRows, final String pDefaultExpand)
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		if (pVersion == Version.V2) query.put("$format", "json");
		for (final String option : QUERY_OPTIONS)
		{
			final String value = value(pInputs, option);
			if (value != null) query.put(option, value);
		}
		if (!query.containsKey("$expand") && trim(pDefaultExpand) != null) query.put("$expand", trim(pDefaultExpand));
		if (pCollection && !query.containsKey("$top"))
		{
			final String requested = value(pInputs, "max");
			query.put("$top", requested == null ? (trim(pMaximumRows) == null ? "500" : trim(pMaximumRows)) : requested);
		}
		if (pCollection)
		{
			query.put(pVersion == Version.V4 ? "$count" : "$inlinecount", pVersion == Version.V4 ? "true" : "allpages");
		}
		return query;
	}

	/** Unwraps a single v2 entity and removes protocol annotations recursively. */
	public static JsonNode entity(final JsonNode pBody)
	{
		if (pBody == null) return null;
		return normalise(pBody.get("d") == null ? pBody : pBody.get("d"));
	}

	/**
	 * Normalizes a collection to results/count plus optional totalCount, nextLink and deltaLink.
	 * Empty result sets remain an explicit empty page rather than becoming null.
	 */
	public static ObjectNode collection(final JsonNode pBody)
	{
		final ObjectNode out = Json.object();
		final ArrayNode results = out.putArray("results");
		if (pBody == null)
		{
			out.put("count", 0);
			return out;
		}
		JsonNode list = Json.at(pBody, "d.results");
		JsonNode total = Json.at(pBody, "d.__count");
		if (list == null)
		{
			list = pBody.get("value");
			total = pBody.get("@odata.count");
		}
		if (list == null && pBody.get("d") != null && pBody.get("d").isArray()) list = pBody.get("d");
		if (list != null && list.isArray()) for (final JsonNode item : list) results.add(normalise(item));
		out.put("count", results.size());
		if (total != null)
		{
			try { out.put("totalCount", Long.parseLong(total.asText())); }
			catch (final NumberFormatException ignored) { }
		}
		copyLink(pBody, out, "nextLink", "@odata.nextLink", "d.__next", "__next");
		copyLink(pBody, out, "deltaLink", "@odata.deltaLink", "d.__delta", "__delta");
		return out;
	}

	/** Merges an initial page and its continuations within explicit page/record bounds. */
	public static ObjectNode collectPages(final String pServiceRoot, final JsonNode pFirstPage,
		final int pMaximumPages, final int pMaximumRecords, final PageFetcher pFetcher) throws ConnectorException
	{
		if (pMaximumPages < 1 || pMaximumRecords < 1) throw new IllegalArgumentException("Positive OData page and record limits are required");
		final ObjectNode result = Json.object();
		final ArrayNode records = result.putArray("results");
		JsonNode pageBody = pFirstPage;
		String next = null;
		String delta = null;
		int pages = 0;
		while (pageBody != null && pages < pMaximumPages && records.size() < pMaximumRecords)
		{
			final ObjectNode page = collection(pageBody);
			for (final JsonNode item : page.withArray("results"))
			{
				if (records.size() >= pMaximumRecords) break;
				records.add(item);
			}
			pages++;
			next = text(page.get("nextLink"));
			final String pageDelta = text(page.get("deltaLink"));
			if (pageDelta != null) delta = pageDelta;
			if (next == null || pages >= pMaximumPages || records.size() >= pMaximumRecords) break;
			pageBody = pFetcher.get(continuationUrl(pServiceRoot, next));
		}
		result.put("count", records.size());
		result.put("pages", pages);
		if (delta != null) result.put("deltaLink", delta);
		if (next != null && (pages >= pMaximumPages || records.size() >= pMaximumRecords))
		{
			result.put("truncated", true);
			result.put("nextLink", next);
		}
		return result;
	}

	/** Drops OData annotations/deferred values, unwraps v2 results and converts v2 dates. */
	public static JsonNode normalise(final JsonNode pNode)
	{
		if (pNode == null) return null;
		if (pNode.isObject())
		{
			final JsonNode removed = pNode.get("@odata.removed") != null ? pNode.get("@odata.removed") : pNode.get("@removed");
			if (pNode.size() == 1 && pNode.get("results") != null && pNode.get("results").isArray()) return normalise(pNode.get("results"));
			final ObjectNode out = Json.object();
			if (removed != null)
			{
				out.put("_deleted", true);
				final JsonNode reason = removed.get("reason");
				if (reason != null) out.put("_removedReason", reason.asText());
			}
			final Iterator<Map.Entry<String, JsonNode>> fields = pNode.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				final String name = field.getKey();
				if ("__metadata".equals(name) || name.startsWith("@odata") || name.indexOf('@') >= 0) continue;
				final JsonNode value = field.getValue();
				if (value != null && value.isObject() && value.size() == 1 && value.get("__deferred") != null) continue;
				out.set(name, normalise(value));
			}
			return out;
		}
		if (pNode.isArray())
		{
			final ArrayNode out = Json.mapper().createArrayNode();
			for (final JsonNode item : pNode) out.add(normalise(item));
			return out;
		}
		if (pNode.isTextual())
		{
			final Matcher matcher = V2_DATE.matcher(pNode.asText());
			if (matcher.matches())
			{
				final long millis = Long.parseLong(matcher.group(1));
				return Json.fromJava(java.time.Instant.ofEpochMilli(millis).toString());
			}
		}
		return pNode;
	}

	/** Resolves a server next/delta link but rejects a continuation that changes origin. */
	public static String continuationUrl(final String pServiceRoot, final String pLink) throws ConnectorException
	{
		try
		{
			final URI root = URI.create(pServiceRoot.endsWith("/") ? pServiceRoot : pServiceRoot + "/");
			final URI resolved = root.resolve(pLink);
			if (!same(root.getScheme(), resolved.getScheme()) || !same(root.getHost(), resolved.getHost()) || effectivePort(root) != effectivePort(resolved))
				throw new ConnectorException("OData continuation link changed service origin");
			return resolved.toASCIIString();
		}
		catch (final IllegalArgumentException ex)
		{
			throw new ConnectorException("Invalid OData continuation link: " + ex.getMessage(), ex);
		}
	}

	/** Constructs an OData v4 JSON batch request; transport sends it to {@code /$batch}. */
	public static ObjectNode v4Batch(final List<BatchOperation> pOperations)
	{
		final ObjectNode body = Json.object();
		final ArrayNode requests = body.putArray("requests");
		if (pOperations != null) for (final BatchOperation operation : pOperations)
		{
			final ObjectNode request = requests.addObject();
			request.put("id", operation.getId());
			request.put("method", operation.getMethod());
			request.put("url", operation.getUrl());
			if (!operation.getHeaders().isEmpty()) request.set("headers", Json.mapper().valueToTree(operation.getHeaders()));
			if (operation.getBody() != null) request.set("body", operation.getBody());
			if (!operation.getDependsOn().isEmpty()) request.set("dependsOn", Json.mapper().valueToTree(operation.getDependsOn()));
		}
		return body;
	}

	/** Normalizes each body in an OData v4 JSON batch response while preserving id/status/headers. */
	public static ObjectNode v4BatchResponse(final JsonNode pBody)
	{
		final ObjectNode out = Json.object();
		final ArrayNode responses = out.putArray("responses");
		final JsonNode source = pBody == null ? null : pBody.get("responses");
		if (source != null && source.isArray()) for (final JsonNode response : source)
		{
			final ObjectNode copy = response.deepCopy();
			if (copy.get("body") != null) copy.set("body", normalise(copy.get("body")));
			responses.add(copy);
		}
		return out;
	}

	/** OData v2 multipart/mixed batch, with mutating operations enclosed in one changeset. */
	public static V2Batch v2Batch(final List<BatchOperation> pOperations, final String pBoundary)
	{
		final String boundary = trim(pBoundary) == null ? "batch_ebx" : pBoundary.replaceAll("[^A-Za-z0-9_-]", "_");
		final String changeset = "changeset_" + boundary;
		final StringBuilder body = new StringBuilder();
		if (pOperations != null) for (final BatchOperation operation : pOperations)
		{
			if ("GET".equals(operation.getMethod())) appendV2Operation(body, boundary, operation);
		}
		boolean opened = false;
		if (pOperations != null) for (final BatchOperation operation : pOperations)
		{
			if ("GET".equals(operation.getMethod())) continue;
			if (!opened)
			{
				body.append("--").append(boundary).append("\r\nContent-Type: multipart/mixed; boundary=").append(changeset).append("\r\n\r\n");
				opened = true;
			}
			appendV2Operation(body, changeset, operation);
		}
		if (opened) body.append("--").append(changeset).append("--\r\n");
		body.append("--").append(boundary).append("--\r\n");
		try { return new V2Batch("multipart/mixed; boundary=" + boundary, body.toString().getBytes("UTF-8")); }
		catch (final java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
	}

	private static void appendV2Operation(final StringBuilder pOut, final String pBoundary, final BatchOperation pOperation)
	{
		pOut.append("--").append(pBoundary).append("\r\nContent-Type: application/http\r\nContent-Transfer-Encoding: binary\r\n")
			.append("Content-ID: ").append(pOperation.getId()).append("\r\n\r\n").append(pOperation.getMethod()).append(' ')
			.append(pOperation.getUrl()).append(" HTTP/1.1\r\n");
		for (final Map.Entry<String, String> header : pOperation.getHeaders().entrySet()) pOut.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
		if (pOperation.getBody() != null) pOut.append("Content-Type: application/json\r\n\r\n").append(Json.write(pOperation.getBody())).append("\r\n");
		else pOut.append("\r\n");
	}

	public static final class V2Batch
	{
		private final String contentType;
		private final byte[] body;
		private V2Batch(final String pContentType, final byte[] pBody)
		{
			contentType = pContentType;
			body = pBody;
		}
		public String getContentType() { return contentType; }
		public byte[] getBody() { return body.clone(); }
	}

	/** Parses a bounded OData v2 multipart response, including nested changesets. */
	public static ObjectNode v2BatchResponse(final String pContentType, final String pBody,
		final int pMaximumParts, final int pMaximumBytes) throws ConnectorException
	{
		if (pMaximumParts < 1 || pMaximumBytes < 1) throw new IllegalArgumentException("Positive multipart limits are required");
		if (pBody == null)
		{
			final ObjectNode empty = Json.object();
			empty.putArray("responses");
			return empty;
		}
		try { if (pBody.getBytes("UTF-8").length > pMaximumBytes) throw new ConnectorException("OData batch response exceeds byte limit"); }
		catch (final java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
		final String boundary = boundary(pContentType);
		if (boundary == null) throw new ConnectorException("OData batch response has no multipart boundary");
		final ObjectNode out = Json.object();
		final ArrayNode responses = out.putArray("responses");
		parseMultipart(boundary, pBody, responses, pMaximumParts);
		return out;
	}

	private static void parseMultipart(final String pBoundary, final String pBody, final ArrayNode pResponses,
		final int pMaximumParts) throws ConnectorException
	{
		for (final String raw : pBody.split("--" + Pattern.quote(pBoundary)))
		{
			String part = raw.trim();
			if (part.length() == 0 || "--".equals(part)) continue;
			if (pResponses.size() >= pMaximumParts) throw new ConnectorException("OData batch response exceeds part limit");
			final String nested = boundary(headerValue(part, "Content-Type"));
			if (nested != null)
			{
				parseMultipart(nested, bodyAfterHeaders(part), pResponses, pMaximumParts);
				continue;
			}
			final String http = bodyAfterHeaders(part);
			final Matcher status = Pattern.compile("(?m)^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})(?:\\s+([^\\r\\n]*))?").matcher(http);
			if (!status.find()) continue;
			final ObjectNode response = pResponses.addObject();
			response.put("status", Integer.parseInt(status.group(1)));
			if (status.group(2) != null) response.put("reason", status.group(2).trim());
			final String id = headerValue(part, "Content-ID");
			if (id != null) response.put("id", id);
			final String payload = bodyAfterHeaders(http.substring(status.end()).replaceFirst("^[\\r\\n]+", ""));
			if (trim(payload) != null) try { response.set("body", normalise(Json.parse(payload))); }
			catch (final java.io.IOException ignored) { response.put("rawBody", payload); }
		}
	}

	private static String bodyAfterHeaders(final String pValue)
	{
		int at = pValue.indexOf("\r\n\r\n");
		int width = 4;
		if (at < 0)
		{
			at = pValue.indexOf("\n\n");
			width = 2;
		}
		return at < 0 ? "" : pValue.substring(at + width).trim();
	}
	private static String headerValue(final String pValue, final String pName)
	{
		final Matcher m = Pattern.compile("(?im)^" + Pattern.quote(pName) + "\\s*:\\s*([^\\r\\n]+)").matcher(pValue);
		return m.find() ? m.group(1).trim() : null;
	}
	private static String boundary(final String pContentType)
	{
		if (pContentType == null) return null;
		final Matcher m = Pattern.compile("(?i)(?:^|;)\\s*boundary\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))").matcher(pContentType);
		return m.find() ? (m.group(1) == null ? m.group(2) : m.group(1)) : null;
	}

	/** Entity-set schema extracted from either v2 or v4 CSDL metadata. */
	public static List<MetadataField> parseMetadata(final String pXml, final String pEntitySet) throws ConnectorException
	{
		if (pXml == null || pEntitySet == null) return Collections.emptyList();
		try
		{
			final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			final Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(pXml.getBytes("UTF-8")));
			String entityType = null;
			for (final Element set : elements(document, "EntitySet")) if (pEntitySet.equals(set.getAttribute("Name"))) entityType = set.getAttribute("EntityType");
			if (entityType == null) return Collections.emptyList();
			final String typeName = entityType.substring(entityType.lastIndexOf('.') + 1);
			final List<MetadataField> fields = new ArrayList<MetadataField>();
			for (final Element type : elements(document, "EntityType"))
			{
				if (!typeName.equals(type.getAttribute("Name"))) continue;
				final java.util.Set<String> keys = new java.util.LinkedHashSet<String>();
				final Element key = directChildren(type, "Key").isEmpty() ? null : directChildren(type, "Key").get(0);
				if (key != null) for (final Element ref : directChildren(key, "PropertyRef")) keys.add(ref.getAttribute("Name"));
				for (final Element property : directChildren(type, "Property"))
				{
					String label = property.getAttributeNS("http://www.sap.com/Protocols/SAPData", "label");
					if (label == null || label.length() == 0) label = property.getAttribute("sap:label");
					if (label == null || label.length() == 0) label = property.getAttribute("Name");
					fields.add(new MetadataField(property.getAttribute("Name"), label, edmType(property.getAttribute("Type")), false,
						"true".equalsIgnoreCase(property.getAttribute("Nullable")) || property.getAttribute("Nullable").length() == 0,
						keys.contains(property.getAttribute("Name")), property.getAttribute("MaxLength"), property.getAttribute("Precision"), property.getAttribute("Scale")));
				}
				for (final Element navigation : directChildren(type, "NavigationProperty"))
				{
					final String navType = navigation.getAttribute("Type");
					fields.add(new MetadataField(navigation.getAttribute("Name"), navigation.getAttribute("Name"), navType, true, true, false, "", "", ""));
				}
			}
			return fields;
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("Cannot parse OData $metadata: " + ex.getMessage(), ex);
		}
	}

	public static final class MetadataField
	{
		private final String name, label, type;
		private final boolean navigation, nullable, key;
		private final String maxLength, precision, scale;
		private MetadataField(final String pName, final String pLabel, final String pType, final boolean pNavigation, final boolean pNullable,
			final boolean pKey, final String pMaxLength, final String pPrecision, final String pScale)
		{
			name = pName;
			label = pLabel;
			type = pType;
			navigation = pNavigation;
			nullable = pNullable;
			key = pKey;
			maxLength = pMaxLength;
			precision = pPrecision;
			scale = pScale;
		}
		public String getName() { return name; }
		public String getLabel() { return label; }
		public String getType() { return type; }
		public boolean isNavigation() { return navigation; }
		public boolean isNullable() { return nullable; }
		public boolean isKey() { return key; }
		public String getMaxLength() { return maxLength; }
		public String getPrecision() { return precision; }
		public String getScale() { return scale; }
	}

	public static final class BatchOperation
	{
		private final String id, method, url;
		private final Map<String, String> headers;
		private final JsonNode body;
		private final List<String> dependsOn;
		public BatchOperation(final String pId, final String pMethod, final String pUrl, final Map<String, String> pHeaders,
			final JsonNode pBody, final List<String> pDependsOn)
		{
			if (trim(pId) == null || trim(pMethod) == null || trim(pUrl) == null) throw new IllegalArgumentException("Batch id, method and URL are required");
			id = pId;
			method = pMethod.toUpperCase(java.util.Locale.ROOT);
			url = pUrl;
			headers = pHeaders == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<String, String>(pHeaders));
			body = pBody;
			dependsOn = pDependsOn == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<String>(pDependsOn));
		}
		public String getId() { return id; }
		public String getMethod() { return method; }
		public String getUrl() { return url; }
		public Map<String, String> getHeaders() { return headers; }
		public JsonNode getBody() { return body; }
		public List<String> getDependsOn() { return dependsOn; }
	}

	private static String value(final Map<String, String> pInputs, final String pName)
	{
		if (pInputs == null) return null;
		String value = trim(pInputs.get(pName));
		if (value == null && pName.startsWith("$")) value = trim(pInputs.get(pName.substring(1)));
		return value;
	}
	private static String trim(final String pValue) { return pValue == null || pValue.trim().length() == 0 ? null : pValue.trim(); }
	private static String text(final JsonNode pValue) { return pValue == null || pValue.isNull() || pValue.asText().trim().isEmpty() ? null : pValue.asText(); }
	private static void copyLink(final JsonNode pBody, final ObjectNode pOut, final String pTarget, final String... pPaths)
	{
		for (final String path : pPaths)
		{
			final JsonNode value;
			if (path.startsWith("d.")) value = pBody.get("d") == null ? null : pBody.get("d").get(path.substring(2));
			else value = pBody.get(path);
			if (value != null && value.isTextual())
			{
				pOut.put(pTarget, value.asText());
				return;
			}
		}
	}
	private static boolean same(final String pLeft, final String pRight) { return pLeft == null ? pRight == null : pLeft.equalsIgnoreCase(pRight); }
	private static int effectivePort(final URI pUri)
	{
		if (pUri.getPort() >= 0) return pUri.getPort();
		return "https".equalsIgnoreCase(pUri.getScheme()) ? 443 : 80;
	}
	private static String edmType(final String pType) { return pType == null ? "" : pType.replace("Edm.", ""); }
	private static List<Element> elements(final Document pDocument, final String pLocalName)
	{
		final List<Element> out = new ArrayList<Element>();
		final NodeList nodes = pDocument.getElementsByTagNameNS("*", pLocalName);
		for (int i = 0; i < nodes.getLength(); i++) out.add((Element) nodes.item(i));
		return out;
	}
	private static List<Element> directChildren(final Element pParent, final String pLocalName)
	{
		final List<Element> out = new ArrayList<Element>();
		for (Node child = pParent.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element && pLocalName.equals(child.getLocalName())) out.add((Element) child);
		return out;
	}
}
