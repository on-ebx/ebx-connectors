package com.on.ebx.connect.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.on.ebx.connect.spi.ConnectorException;

/** Deterministic, account-free checks for the shared SOAP/WSDL foundation. */
public final class SoapFoundationTest
{
	private SoapFoundationTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final SoapSupport.Request soap11 = SoapSupport.request("1.1", "urn:find", "<m:Find xmlns:m=\"urn:test\"><m:id>7</m:id></m:Find>", "a&b", "p<q");
		check("text/xml; charset=UTF-8".equals(soap11.getHeaders().get("Content-Type")), "SOAP 1.1 content type");
		check("\"urn:find\"".equals(soap11.getHeaders().get("SOAPAction")), "SOAP 1.1 action");
		check(soap11.getEnvelope().contains("Username>a&amp;b</") && soap11.getEnvelope().contains("PasswordText\">p&lt;q</"), "WS-Security escaping");
		final SoapSupport.Request soap12 = SoapSupport.request("1.2", "urn:save", "<Save xmlns=\"urn:test\"/>", null, null);
		check(soap12.getHeaders().get("Content-Type").contains("application/soap+xml")
			&& soap12.getHeaders().get("Content-Type").contains("action=\"urn:save\"")
			&& !soap12.getHeaders().containsKey("SOAPAction"), "SOAP 1.2 headers");
		final SoapSupport.Request digest = SoapSupport.digestRequest("1.1", "urn:find", "<Find/>", "user", "secret",
			"nonce".getBytes("UTF-8"), "2026-09-08T00:00:00Z");
		check(digest.getEnvelope().contains("#PasswordDigest") && digest.getEnvelope().contains("<wsse:Nonce")
			&& digest.getEnvelope().contains("<wsu:Created>2026-09-08T00:00:00Z</wsu:Created>"), "WS-Security digest token");
		check("https://example.test/schema/common.xsd".equals(SoapSupport.importUrl("https://example.test/ws/service.wsdl", "../schema/common.xsd", 0, 3, 0, 8)), "same-origin WSDL import");
		boolean crossOrigin = false;
		try { SoapSupport.importUrl("https://example.test/ws/service.wsdl", "https://evil.test/x.xsd", 0, 3, 0, 8); }
		catch (final ConnectorException expected) { crossOrigin = true; }
		check(crossOrigin, "cross-origin WSDL import accepted");

		final SoapSupport.Wsdl wsdl = SoapSupport.parseWsdl(wsdl());
		check("urn:catalog".equals(wsdl.getTargetNamespace()), "target namespace");
		check(wsdl.getOperations().size() == 1, "operation discovery");
		final SoapSupport.WsdlOperation operation = wsdl.getOperations().get(0);
		check("Lookup".equals(operation.getName()) && "1.2".equals(operation.getVersion()), "SOAP binding version");
		check("urn:lookup".equals(operation.getAction()) && "https://example.test/soap".equals(operation.getEndpoint()), "action/endpoint");
		check(operation.getFields().size() == 2 && "number".equals(operation.getFields().get(1).getType()), "inline XSD fields");
		check(operation.requestTemplate(wsdl.getTargetNamespace()).contains("<tns:LookupRequest xmlns:tns=\"urn:catalog\">")
			&& operation.requestTemplate(wsdl.getTargetNamespace()).contains("<tns:id>{id}</tns:id>"), "namespace-safe template");

		final Document fault11 = Xml.parse("<s:Fault xmlns:s=\"" + SoapSupport.SOAP11 + "\"><faultcode>s:Client</faultcode><faultstring>Bad input</faultstring><detail><code>E7</code></detail></s:Fault>");
		final ConnectorException normalized11 = SoapSupport.fault("demo", "lookup", fault11.getDocumentElement(), 500);
		check(normalized11.getStatus() == 500 && normalized11.getMessage().contains("[s:Client]: Bad input") && normalized11.getMessage().contains("E7"), "SOAP 1.1 fault");
		final Document fault12 = Xml.parse("<s:Fault xmlns:s=\"" + SoapSupport.SOAP12 + "\"><s:Code><s:Value>s:Sender</s:Value></s:Code><s:Reason><s:Text xml:lang=\"en\">Invalid</s:Text></s:Reason></s:Fault>");
		check(SoapSupport.fault("demo", "save", fault12.getDocumentElement(), 400).getMessage().contains("[s:Sender]: Invalid"), "SOAP 1.2 fault");

		boolean rejected = false;
		try { Xml.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"); }
		catch (final Exception expected) { rejected = true; }
		check(rejected, "DOCTYPE/XXE must be rejected");
		boolean policyRejected = false;
		try { SoapSupport.parseWsdl("<definitions xmlns:wsp=\"urn:wsp\"><wsp:Policy/></definitions>"); }
		catch (final ConnectorException expected) { policyRejected = expected.getMessage().contains("WS-Policy"); }
		check(policyRejected, "unsupported WS-Policy not made explicit");
		System.out.println("PASS: SOAP 1.1/1.2, WS-Security, WSDL/XSD discovery, templates, and faults");
	}

	private static String wsdl()
	{
		return "<wsdl:definitions xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\" xmlns:soap12=\"http://schemas.xmlsoap.org/wsdl/soap12/\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:tns=\"urn:catalog\" targetNamespace=\"urn:catalog\">"
			+ "<wsdl:types><xsd:schema targetNamespace=\"urn:catalog\"><xsd:element name=\"LookupRequest\"><xsd:complexType><xsd:sequence>"
			+ "<xsd:element name=\"id\" type=\"xsd:string\"/><xsd:element name=\"limit\" type=\"xsd:int\"/>"
			+ "</xsd:sequence></xsd:complexType></xsd:element></xsd:schema></wsdl:types>"
			+ "<wsdl:message name=\"LookupInput\"><wsdl:part name=\"body\" element=\"tns:LookupRequest\"/></wsdl:message>"
			+ "<wsdl:portType name=\"CatalogPortType\"><wsdl:operation name=\"Lookup\"><wsdl:input message=\"tns:LookupInput\"/></wsdl:operation></wsdl:portType>"
			+ "<wsdl:binding name=\"CatalogBinding\" type=\"tns:CatalogPortType\"><soap12:binding transport=\"http://schemas.xmlsoap.org/soap/http\"/>"
			+ "<wsdl:operation name=\"Lookup\"><soap12:operation soapAction=\"urn:lookup\"/></wsdl:operation></wsdl:binding>"
			+ "<wsdl:service name=\"Catalog\"><wsdl:port name=\"CatalogPort\" binding=\"tns:CatalogBinding\"><soap12:address location=\"https://example.test/soap\"/></wsdl:port></wsdl:service>"
			+ "</wsdl:definitions>";
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
