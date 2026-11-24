package com.on.ebx.connect.core;

import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.connect.spi.ConnectorException;

/** Deterministic tests for bounded recursive same-origin WSDL/XSD loading. */
public final class SoapRecursiveWsdlTest
{
	private SoapRecursiveWsdlTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		final Map<String, String> graph = new LinkedHashMap<String, String>();
		graph.put("https://example.test/ws/child.wsdl", childWsdl());
		graph.put("https://example.test/schema/types.xsd", schema("<xsd:include schemaLocation=\"../ws/root.wsdl\"/>"));
		final CountingFetcher fetcher = new CountingFetcher(graph, 0L);
		final SoapSupport.Wsdl wsdl = SoapSupport.loadWsdl("https://example.test/ws/root.wsdl", rootWsdl(), fetcher,
			new SoapSupport.ImportLimits(4, 4, 20000, 1000));
		check(fetcher.calls == 2, "cycle fetched a document twice");
		check(wsdl.getOperations().size() == 1, "imported WSDL operation not merged");
		final SoapSupport.WsdlOperation operation = wsdl.getOperations().get(0);
		check(operation.getFields().size() == 2 && "boolean".equals(operation.getFields().get(1).getType()), "imported XSD fields not merged");
		check(operation.requestTemplate(wsdl.getTargetNamespace()).contains("xmlns:tns=\"urn:service\"")
			&& operation.requestTemplate(wsdl.getTargetNamespace()).contains("<tns:enabled>{enabled}</tns:enabled>"), "imported WSDL namespace/XSD template missing");

		rejected("origin", rootWsdl().replace("child.wsdl", "https://evil.test/child.wsdl"), graph,
			new SoapSupport.ImportLimits(4, 4, 20000, 1000), "changed origin");
		rejected("depth", rootWsdl(), graph, new SoapSupport.ImportLimits(1, 4, 20000, 1000), "depth limit");
		rejected("count", rootWsdl(), graph, new SoapSupport.ImportLimits(4, 2, 20000, 1000), "count limit");
		rejected("bytes", rootWsdl(), graph, new SoapSupport.ImportLimits(4, 4, 100, 1000), "byte limit");
		boolean timedOut = false;
		try
		{
			SoapSupport.loadWsdl("https://example.test/ws/root.wsdl", rootWsdl(), new CountingFetcher(graph, 10L),
				new SoapSupport.ImportLimits(4, 4, 20000, 1));
		}
		catch (final ConnectorException expected) { timedOut = expected.getMessage().contains("time limit"); }
		check(timedOut, "time limit not enforced after fetch");

		final Map<String, String> malicious = new LinkedHashMap<String, String>();
		malicious.put("https://example.test/ws/child.wsdl", "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>");
		rejected("XXE", rootWsdl(), malicious, new SoapSupport.ImportLimits(4, 4, 20000, 1000), "Cannot parse");
		final Map<String, String> policy = new LinkedHashMap<String, String>();
		policy.put("https://example.test/ws/child.wsdl", childWsdl().replace("<wsdl:definitions", "<wsdl:definitions xmlns:wsp=\"urn:wsp\"").replace("</wsdl:definitions>", "<wsp:Policy/></wsdl:definitions>"));
		policy.put("https://example.test/schema/types.xsd", schema(""));
		rejected("policy", rootWsdl(), policy, new SoapSupport.ImportLimits(4, 4, 20000, 1000), "WS-Policy");
		final Map<String, String> signature = new LinkedHashMap<String, String>();
		signature.put("https://example.test/ws/child.wsdl", childWsdl().replace("</wsdl:definitions>", "<Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\"/></wsdl:definitions>"));
		signature.put("https://example.test/schema/types.xsd", schema(""));
		rejected("signature", rootWsdl(), signature, new SoapSupport.ImportLimits(4, 4, 20000, 1000), "Signature");
		System.out.println("PASS: recursive WSDL/XSD imports are merged and bounded by origin, cycles, depth, count, bytes and time");
	}

	private static void rejected(final String pLabel, final String pRoot, final Map<String, String> pGraph,
		final SoapSupport.ImportLimits pLimits, final String pMessage) throws Exception
	{
		boolean rejected = false;
		try { SoapSupport.loadWsdl("https://example.test/ws/root.wsdl", pRoot, new CountingFetcher(pGraph, 0L), pLimits); }
		catch (final ConnectorException expected) { rejected = expected.getMessage().contains(pMessage); }
		check(rejected, pLabel + " was not rejected with a clear error");
	}

	private static String rootWsdl()
	{
		return "<wsdl:definitions xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\" targetNamespace=\"urn:catalog\">"
			+ "<wsdl:import namespace=\"urn:service\" location=\"child.wsdl\"/></wsdl:definitions>";
	}

	private static String childWsdl()
	{
		return "<wsdl:definitions xmlns:wsdl=\"http://schemas.xmlsoap.org/wsdl/\" xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap/\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:tns=\"urn:service\" targetNamespace=\"urn:service\">"
			+ "<wsdl:types><xsd:schema targetNamespace=\"urn:catalog\"><xsd:import namespace=\"urn:types\" schemaLocation=\"../schema/types.xsd\"/></xsd:schema></wsdl:types>"
			+ "<wsdl:message name=\"SaveInput\"><wsdl:part name=\"body\" element=\"tns:SaveRequest\"/></wsdl:message>"
			+ "<wsdl:portType name=\"Port\"><wsdl:operation name=\"Save\"><wsdl:input message=\"tns:SaveInput\"/></wsdl:operation></wsdl:portType>"
			+ "<wsdl:binding name=\"Binding\" type=\"tns:Port\"><soap:binding/><wsdl:operation name=\"Save\"><soap:operation soapAction=\"urn:save\"/></wsdl:operation></wsdl:binding>"
			+ "<wsdl:service name=\"Service\"><wsdl:port name=\"Port\" binding=\"tns:Binding\"><soap:address location=\"https://example.test/soap\"/></wsdl:port></wsdl:service>"
			+ "</wsdl:definitions>";
	}

	private static String schema(final String pExtra)
	{
		return "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\"urn:types\">" + pExtra
			+ "<xsd:element name=\"SaveRequest\"><xsd:complexType><xsd:sequence><xsd:element name=\"id\" type=\"xsd:string\"/>"
			+ "<xsd:element name=\"enabled\" type=\"xsd:boolean\"/></xsd:sequence></xsd:complexType></xsd:element></xsd:schema>";
	}

	private static final class CountingFetcher implements SoapSupport.DocumentFetcher
	{
		private final Map<String, String> graph; private final long delay; private int calls;
		CountingFetcher(final Map<String, String> pGraph, final long pDelay) { this.graph = pGraph; this.delay = pDelay; }
		@Override public String fetch(final String pUrl) throws ConnectorException
		{
			this.calls++;
			if (this.delay > 0) try { Thread.sleep(this.delay); } catch (final InterruptedException ex) { Thread.currentThread().interrupt(); }
			final String value = this.graph.get(pUrl);
			if (value == null) throw new ConnectorException("Unexpected fetch " + pUrl);
			return value;
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
