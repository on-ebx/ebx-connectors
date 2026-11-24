package com.on.ebx.connect.core;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;

/** Shared, dependency-free SOAP envelope, fault, and WSDL/XSD metadata support. */
public final class SoapSupport
{
	public static final String SOAP11 = "http://schemas.xmlsoap.org/soap/envelope/";
	public static final String SOAP12 = "http://www.w3.org/2003/05/soap-envelope";
	private static final String WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
	private static final String WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";

	private SoapSupport() { }

	public static final class Request
	{
		private final String envelope;
		private final Map<String, String> headers;
		Request(final String pEnvelope, final Map<String, String> pHeaders)
		{
			this.envelope = pEnvelope;
			this.headers = Collections.unmodifiableMap(pHeaders);
		}
		public String getEnvelope() { return this.envelope; }
		public Map<String, String> getHeaders() { return this.headers; }
	}

	public static Request request(final String pVersion, final String pAction, final String pBody,
		final String pUsername, final String pPassword)
	{
		final boolean v12 = "1.2".equals(trim(pVersion));
		final String action = trim(pAction);
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		if (v12)
			headers.put("Content-Type", "application/soap+xml; charset=UTF-8" + (action.isEmpty() ? "" : "; action=\"" + header(action) + "\""));
		else
		{
			headers.put("Content-Type", "text/xml; charset=UTF-8");
			headers.put("SOAPAction", "\"" + header(action) + "\"");
		}
		headers.put("Accept", "text/xml, application/soap+xml, application/xml");
		final StringBuilder security = new StringBuilder();
		if (!trim(pUsername).isEmpty())
		{
			security.append("<wsse:Security soapenv:mustUnderstand=\"1\" xmlns:wsse=\"").append(WSSE)
				.append("\" xmlns:wsu=\"").append(WSU).append("\"><wsse:UsernameToken><wsse:Username>")
				.append(xml(pUsername)).append("</wsse:Username><wsse:Password Type=\"").append(WSSE)
				.append("#PasswordText\">").append(xml(pPassword)).append("</wsse:Password></wsse:UsernameToken></wsse:Security>");
		}
		return new Request("<?xml version=\"1.0\" encoding=\"UTF-8\"?><soapenv:Envelope xmlns:soapenv=\""
			+ (v12 ? SOAP12 : SOAP11) + "\"><soapenv:Header>" + security + "</soapenv:Header><soapenv:Body>"
			+ (pBody == null ? "" : pBody) + "</soapenv:Body></soapenv:Envelope>", headers);
	}

	/** Creates a UsernameToken PasswordDigest request using caller-supplied nonce/time for testability. */
	public static Request digestRequest(final String pVersion, final String pAction, final String pBody,
		final String pUsername, final String pPassword, final byte[] pNonce, final String pCreated) throws ConnectorException
	{
		if (trim(pUsername).isEmpty() || pNonce == null || pNonce.length == 0 || trim(pCreated).isEmpty())
			throw new ConnectorException("WS-Security digest requires username, nonce and created time");
		try
		{
			final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			sha1.update(pNonce);
			sha1.update(pCreated.getBytes(StandardCharsets.UTF_8));
			sha1.update(value(pPassword).getBytes(StandardCharsets.UTF_8));
			final String digest = java.util.Base64.getEncoder().encodeToString(sha1.digest());
			final String nonce = java.util.Base64.getEncoder().encodeToString(pNonce);
			final Request plain = request(pVersion, pAction, pBody, null, null);
			final String token = "<wsse:Security soapenv:mustUnderstand=\"1\" xmlns:wsse=\"" + WSSE + "\" xmlns:wsu=\"" + WSU
				+ "\"><wsse:UsernameToken><wsse:Username>" + xml(pUsername) + "</wsse:Username><wsse:Password Type=\"" + WSSE
				+ "#PasswordDigest\">" + digest + "</wsse:Password><wsse:Nonce EncodingType=\"" + WSSE + "#Base64Binary\">" + nonce
				+ "</wsse:Nonce><wsu:Created>" + xml(pCreated) + "</wsu:Created></wsse:UsernameToken></wsse:Security>";
			return new Request(plain.getEnvelope().replace("<soapenv:Header></soapenv:Header>", "<soapenv:Header>" + token + "</soapenv:Header>"),
				new LinkedHashMap<String, String>(plain.getHeaders()));
		}
		catch (final Exception ex) { throw new ConnectorException("Cannot create WS-Security password digest", ex); }
	}

	/** Resolves a WSDL/XSD import while enforcing same-origin and depth/count bounds. */
	public static String importUrl(final String pParentUrl, final String pLocation, final int pDepth,
		final int pMaximumDepth, final int pImportedCount, final int pMaximumImports) throws ConnectorException
	{
		if (pDepth >= pMaximumDepth) throw new ConnectorException("SOAP schema import depth limit exceeded");
		if (pImportedCount >= pMaximumImports) throw new ConnectorException("SOAP schema import count limit exceeded");
		try
		{
			final URI parent = URI.create(pParentUrl);
			final URI resolved = parent.resolve(pLocation);
			if (!("http".equalsIgnoreCase(resolved.getScheme()) || "https".equalsIgnoreCase(resolved.getScheme()))
				|| resolved.getHost() == null || resolved.getUserInfo() != null)
				throw new ConnectorException("SOAP schema imports require an HTTP(S) URL without embedded credentials");
			if (!same(parent.getScheme(), resolved.getScheme()) || !same(parent.getHost(), resolved.getHost()) || port(parent) != port(resolved))
				throw new ConnectorException("SOAP schema import changed origin");
			return new URI(resolved.getScheme(), null, resolved.getHost(), resolved.getPort(), resolved.getPath(), resolved.getQuery(), null).normalize().toASCIIString();
		}
		catch (final ConnectorException ex) { throw ex; }
		catch (final Exception ex) { throw new ConnectorException("Invalid SOAP schema import URL", ex); }
	}

	/** Fetch seam used by the bounded WSDL graph loader. Implementations must return UTF-8 XML. */
	public interface DocumentFetcher
	{
		String fetch(String pUrl) throws ConnectorException;
	}

	/** Immutable safety policy for recursive WSDL/XSD import traversal. */
	public static final class ImportLimits
	{
		private final int maximumDepth;
		private final int maximumDocuments;
		private final long maximumBytes;
		private final long maximumMillis;
		public ImportLimits(final int pMaximumDepth, final int pMaximumDocuments, final long pMaximumBytes, final long pMaximumMillis)
		{
			if (pMaximumDepth < 0 || pMaximumDepth > 32 || pMaximumDocuments < 1 || pMaximumDocuments > 1024
				|| pMaximumBytes < 1 || pMaximumBytes > 64L * 1024L * 1024L || pMaximumMillis < 1 || pMaximumMillis > 120000L)
				throw new IllegalArgumentException("SOAP import limits are outside the supported safety bounds");
			this.maximumDepth = pMaximumDepth;
			this.maximumDocuments = pMaximumDocuments;
			this.maximumBytes = pMaximumBytes;
			this.maximumMillis = pMaximumMillis;
		}
		public static ImportLimits defaults() { return new ImportLimits(4, 24, 4L * 1024L * 1024L, 15000L); }
	}

	/**
	 * Loads and parses a complete, same-origin WSDL/XSD graph. Each canonical URL is fetched once;
	 * cycles are harmless, while depth, document count, aggregate UTF-8 bytes and wall time are bounded.
	 */
	public static Wsdl loadWsdl(final String pRootUrl, final String pRootXml, final DocumentFetcher pFetcher,
		final ImportLimits pLimits) throws ConnectorException
	{
		if (trim(pRootUrl).isEmpty() || pFetcher == null) throw new ConnectorException("SOAP imports require a root URL and document fetcher");
		final ImportLimits limits = pLimits == null ? ImportLimits.defaults() : pLimits;
		final List<String> documents = new ArrayList<String>();
		final Set<String> visited = new LinkedHashSet<String>();
		final long[] bytes = new long[] { 0L };
		final long deadline = System.nanoTime() + limits.maximumMillis * 1000000L;
		final String rootUrl;
		try
		{
			final URI root = URI.create(pRootUrl).normalize();
			if (!("http".equalsIgnoreCase(root.getScheme()) || "https".equalsIgnoreCase(root.getScheme())) || root.getHost() == null || root.getUserInfo() != null)
				throw new IllegalArgumentException("HTTP(S) URL required");
			rootUrl = new URI(root.getScheme(), null, root.getHost(), root.getPort(), root.getPath(), root.getQuery(), null).toASCIIString();
		}
		catch (final Exception ex) { throw new ConnectorException("Invalid SOAP WSDL root URL", ex); }
		loadDocument(rootUrl, pRootXml, 0, pFetcher, limits, visited, documents, bytes, deadline);
		return parseWsdlDocuments(documents);
	}

	private static void loadDocument(final String pUrl, final String pXml, final int pDepth, final DocumentFetcher pFetcher,
		final ImportLimits pLimits, final Set<String> pVisited, final List<String> pDocuments, final long[] pBytes,
		final long pDeadline) throws ConnectorException
	{
		if (System.nanoTime() > pDeadline) throw new ConnectorException("SOAP schema import time limit exceeded");
		if (pVisited.contains(pUrl)) return;
		if (pVisited.size() >= pLimits.maximumDocuments) throw new ConnectorException("SOAP schema import count limit exceeded");
		final String xml = pXml == null ? pFetcher.fetch(pUrl) : pXml;
		if (System.nanoTime() > pDeadline) throw new ConnectorException("SOAP schema import time limit exceeded");
		if (xml == null) throw new ConnectorException("SOAP schema import returned no document: " + pUrl);
		pBytes[0] += xml.getBytes(StandardCharsets.UTF_8).length;
		if (pBytes[0] > pLimits.maximumBytes) throw new ConnectorException("SOAP schema import byte limit exceeded");
		final Document document;
		try { document = Xml.parse(xml); }
		catch (final Exception ex) { throw new ConnectorException("Cannot parse SOAP schema import " + pUrl + ": " + ex.getMessage(), ex); }
		pVisited.add(pUrl);
		pDocuments.add(xml);
		for (final Element item : importElements(document))
		{
			String location = trim(item.getAttribute("schemaLocation"));
			if (location.isEmpty()) location = trim(item.getAttribute("location"));
			if (location.isEmpty()) continue; // Namespace-only xsd:import has no retrievable resource.
			// Count is checked after cycle canonicalization in loadDocument, so a cycle at the limit remains harmless.
			final String childUrl = importUrl(pUrl, location, pDepth, pLimits.maximumDepth, 0, pLimits.maximumDocuments);
			loadDocument(childUrl, null, pDepth + 1, pFetcher, pLimits, pVisited, pDocuments, pBytes, pDeadline);
		}
	}

	private static List<Element> importElements(final Document pDocument)
	{
		final List<Element> result = new ArrayList<Element>();
		for (final Element item : descendants(pDocument, "import")) result.add(item);
		for (final Element item : descendants(pDocument, "include")) result.add(item);
		return result;
	}

	public static ConnectorException fault(final String pConnector, final String pOperation,
		final Element pFault, final int pStatus)
	{
		final String code = firstText(pFault, "faultcode", "Value");
		final String reason = firstText(pFault, "faultstring", "Text", "Reason");
		final String detail = firstText(pFault, "detail", "Detail");
		final StringBuilder message = new StringBuilder(pConnector).append(": SOAP fault on ").append(pOperation);
		if (!code.isEmpty()) message.append(" [").append(code).append(']');
		if (!reason.isEmpty()) message.append(": ").append(reason);
		if (!detail.isEmpty() && !detail.equals(reason)) message.append(" — ").append(detail);
		return new ConnectorException(message.toString(), pStatus, null);
	}

	public static final class Wsdl
	{
		private final String targetNamespace;
		private final List<WsdlOperation> operations;
		Wsdl(final String pTargetNamespace, final List<WsdlOperation> pOperations)
		{
			this.targetNamespace = pTargetNamespace;
			this.operations = Collections.unmodifiableList(pOperations);
		}
		public String getTargetNamespace() { return this.targetNamespace; }
		public List<WsdlOperation> getOperations() { return this.operations; }
	}

	public static final class WsdlOperation
	{
		private final String name;
		private String action = "";
		private String endpoint = "";
		private String version = "1.1";
		private String inputElement = "";
		private String targetNamespace = "";
		private final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		WsdlOperation(final String pName) { this.name = pName; }
		public String getName() { return this.name; }
		public String getAction() { return this.action; }
		public String getEndpoint() { return this.endpoint; }
		public String getVersion() { return this.version; }
		public String getInputElement() { return this.inputElement; }
		public List<FieldInfo> getFields() { return Collections.unmodifiableList(this.fields); }
		/** Namespace-safe starter payload; callers may replace values with mapping placeholders. */
		public String requestTemplate(final String pTargetNamespace)
		{
			final String namespace = this.targetNamespace.isEmpty() ? pTargetNamespace : this.targetNamespace;
			final String local = qLocal(this.inputElement.isEmpty() ? this.name : this.inputElement);
			final StringBuilder out = new StringBuilder("<tns:").append(local).append(" xmlns:tns=\"")
				.append(xml(namespace)).append("\">");
			for (final FieldInfo field : this.fields)
				out.append("<tns:").append(qLocal(field.getPath())).append(">{").append(qLocal(field.getPath())).append("}</tns:").append(qLocal(field.getPath())).append('>');
			return out.append("</tns:").append(local).append('>').toString();
		}
	}

	/** Parses a WSDL 1.1 document plus its inline XSD into callable operation metadata. */
	public static Wsdl parseWsdl(final String pXml) throws ConnectorException
	{
		return parseWsdlDocuments(Collections.singletonList(pXml));
	}

	private static Wsdl parseWsdlDocuments(final List<String> pXmlDocuments) throws ConnectorException
	{
		try
		{
			final List<Document> docs = new ArrayList<Document>();
			String targetNs = "";
			for (final String xml : pXmlDocuments)
			{
				final Document doc = Xml.parse(xml);
				if (!descendants(doc, "Policy").isEmpty() || !descendants(doc, "PolicyReference").isEmpty())
					throw new ConnectorException("WS-Policy is not interpreted; configure the required transport/security policy explicitly");
				if (!descendants(doc, "Signature").isEmpty() || !descendants(doc, "SignedInfo").isEmpty())
					throw new ConnectorException("XML Signature requirements are unsupported by the generic SOAP connector");
				docs.add(doc);
				if (targetNs.isEmpty() && "definitions".equals(Xml.localName(doc.getDocumentElement())))
					targetNs = doc.getDocumentElement().getAttribute("targetNamespace");
			}
			final Map<String, List<FieldInfo>> elements = new LinkedHashMap<String, List<FieldInfo>>();
			for (final Document doc : docs) elements.putAll(schemaElements(doc));
			final Map<String, String> messageElements = new LinkedHashMap<String, String>();
			for (final Document doc : docs)
				for (final Element message : descendants(doc, "message"))
				{
					final Element part = child(message, "part");
					if (part != null) messageElements.put(message.getAttribute("name"), part.getAttribute("element"));
				}
			final Map<String, String> operationInputs = new LinkedHashMap<String, String>();
			for (final Document doc : docs)
				for (final Element portType : descendants(doc, "portType"))
					for (final Element operation : children(portType, "operation"))
					{
						final Element input = child(operation, "input");
						if (input != null) operationInputs.put(operation.getAttribute("name"), messageElements.get(qLocal(input.getAttribute("message"))));
					}
			final Map<String, WsdlOperation> operations = new LinkedHashMap<String, WsdlOperation>();
			for (final Document doc : docs) for (final Element binding : descendants(doc, "binding"))
			{
				final String bindingNamespace = doc.getDocumentElement().getAttribute("targetNamespace");
				final Element soapBinding = firstSoapChild(binding, "binding");
				final String version = soapBinding != null && soapBinding.getNamespaceURI() != null
					&& soapBinding.getNamespaceURI().toLowerCase().contains("soap12") ? "1.2" : "1.1";
				for (final Element operation : children(binding, "operation"))
				{
					final String name = operation.getAttribute("name");
					if (name.isEmpty()) continue;
					WsdlOperation item = operations.get(name);
					if (item == null)
					{
						item = new WsdlOperation(name);
						operations.put(name, item);
					}
					if (item.targetNamespace.isEmpty()) item.targetNamespace = bindingNamespace;
					item.version = version;
					final Element soapOperation = firstSoapChild(operation, "operation");
					if (soapOperation != null) item.action = soapOperation.getAttribute("soapAction");
					item.inputElement = value(operationInputs.get(name));
					final List<FieldInfo> fields = elements.get(qLocal(item.inputElement));
					if (fields != null) item.fields.addAll(fields);
				}
			}
			for (final Document doc : docs) for (final Element port : descendants(doc, "port"))
			{
				final Element address = firstSoapChild(port, "address");
				if (address == null) continue;
				for (final WsdlOperation operation : operations.values())
					if (operation.endpoint.isEmpty()) operation.endpoint = address.getAttribute("location");
			}
			return new Wsdl(targetNs, new ArrayList<WsdlOperation>(operations.values()));
		}
		catch (final ConnectorException ex) { throw ex; }
		catch (final Exception ex) { throw new ConnectorException("Cannot parse WSDL: " + ex.getMessage(), ex); }
	}

	private static Map<String, List<FieldInfo>> schemaElements(final Document pDoc)
	{
		final Map<String, List<FieldInfo>> result = new LinkedHashMap<String, List<FieldInfo>>();
		for (final Element element : descendants(pDoc, "element"))
		{
			final String name = element.getAttribute("name");
			if (name.isEmpty()) continue;
			final List<FieldInfo> fields = new ArrayList<FieldInfo>();
			final Element complex = child(element, "complexType");
			if (complex != null)
				for (final Element nested : descendants(complex, "element"))
					if (!nested.getAttribute("name").isEmpty()) fields.add(FieldInfo.of(nested.getAttribute("name"), nested.getAttribute("name"), xsdType(nested.getAttribute("type"))));
			if (!fields.isEmpty()) result.put(name, fields);
		}
		return result;
	}

	private static String xsdType(final String pType)
	{
		final String type = qLocal(pType).toLowerCase();
		if (type.contains("int") || type.contains("decimal") || type.contains("double") || type.contains("float")) return "number";
		if (type.contains("bool")) return "boolean";
		if (type.contains("date") || type.contains("time")) return "date";
		return "string";
	}

	private static List<Element> descendants(final Node pNode, final String pName)
	{
		final List<Element> result = new ArrayList<Element>();
		final NodeList nodes = pNode.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element)
		{
			final Element item = (Element) nodes.item(i);
			if (pName.equals(Xml.localName(item))) result.add(item);
			result.addAll(descendants(item, pName));
		}
		return result;
	}
	private static List<Element> children(final Node pNode, final String pName)
	{
		final List<Element> result = new ArrayList<Element>();
		final NodeList nodes = pNode.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element && pName.equals(Xml.localName(nodes.item(i)))) result.add((Element) nodes.item(i));
		return result;
	}
	private static Element child(final Node pNode, final String pName)
	{
		final List<Element> found = children(pNode, pName);
		return found.isEmpty() ? null : found.get(0);
	}
	private static Element firstSoapChild(final Node pNode, final String pName)
	{
		for (final Element item : children(pNode, pName))
			if (item.getNamespaceURI() != null && item.getNamespaceURI().contains("soap")) return item;
		return null;
	}
	private static String firstText(final Node pNode, final String... pNames)
	{
		for (final String name : pNames)
		{
			final Element found = Xml.find(pNode, name);
			if (found != null && !trim(found.getTextContent()).isEmpty()) return trim(found.getTextContent());
		}
		return "";
	}
	private static String qLocal(final String pName)
	{
		final String value = value(pName);
		final int colon = value.indexOf(':');
		return colon < 0 ? value : value.substring(colon + 1);
	}
	private static String value(final String pValue) { return pValue == null ? "" : pValue; }
	private static boolean same(final String pLeft, final String pRight) { return pLeft == null ? pRight == null : pLeft.equalsIgnoreCase(pRight); }
	private static int port(final URI pUri) { return pUri.getPort() >= 0 ? pUri.getPort() : ("https".equalsIgnoreCase(pUri.getScheme()) ? 443 : 80); }
	private static String trim(final String pValue) { return value(pValue).trim(); }
	private static String header(final String pValue) { return trim(pValue).replace("\r", "").replace("\n", "").replace("\"", ""); }
	private static String xml(final String pValue) { return value(pValue).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
}
