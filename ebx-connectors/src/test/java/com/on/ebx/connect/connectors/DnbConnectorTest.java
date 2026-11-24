package com.on.ebx.connect.connectors;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.Operation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** Deterministic contract and transport checks for the D&B Direct+ connector. */
public final class DnbConnectorTest
{
	private DnbConnectorTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		testDescriptorAndDunsValidation();
		testRegistrationAndMonitoringTransport();
		System.out.println("PASS: D&B matching, enrichment, registration and monitoring contract");
	}

	private static void testDescriptorAndDunsValidation() throws Exception
	{
		final DnbConnector connector = new DnbConnector();
		check("dnb".equals(connector.getType()), "connector type");
		check(connector.getDescriptor().getAuthenticationMethods().contains(AuthenticationMethod.OAUTH2_CLIENT_CREDENTIALS),
			"client-credentials authentication descriptor");
		final Set<String> operations = new HashSet<String>();
		for (final Operation operation : connector.getOperations()) operations.add(operation.getName());
		check(operations.contains("match") && operations.contains("bestMatch") && operations.contains("enrich")
			&& operations.contains("familyTree") && operations.contains("monitoringUpdates")
			&& operations.contains("registrationAdd") && operations.contains("registrationRemove"), "lifecycle operations");
		check(DnbConnector.dunsValues("12-345-6789, 987654321,123456789").size() == 2, "DUNS normalization and deduplication");
		check(DnbConnector.multipartDuns("test-boundary", "123456789").contains("filename=\"duns.txt\""), "multipart filename");
	}

	private static void testRegistrationAndMonitoringTransport() throws Exception
	{
		final String previousAllowList = System.getProperty(Egress.PROPERTY);
		final Probe probe = new Probe();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v2/token", probe);
		server.createContext("/v1/monitoring/registrations/acceptance", probe);
		server.start();
		try
		{
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + server.getAddress().getPort());
			final ConnectorConfig config = new ConnectorConfig();
			config.setId("DnB-test"); config.setType("dnb");
			config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
			config.setAuthType(ConnectorConfig.AuthType.BASIC);
			config.setUsername("consumer-key"); config.setPassword("consumer-secret");
			config.setProperty("registration.reference", "acceptance");
			config.setProperty("registration.path", "/v1/monitoring/registrations/{reference}/{type}");
			config.setProperty("notifications.path", "/v1/monitoring/registrations/{reference}/notifications");

			final DnbConnector connector = new DnbConnector(); connector.configure(config);
			final Map<String, String> inputs = new HashMap<String, String>(); inputs.put("duns", "123456789 987654321");
			final JsonNode added = connector.execute(null, "registrationAdd", inputs, null);
			check(added.get("accepted").asBoolean() && added.get("count").asInt() == 2, "registration result");
			check("PATCH".equals(probe.registrationMethod), "registration PATCH method");
			check(probe.registrationBody.contains("123456789\n987654321"), "registration DUNS payload");
			check(probe.registrationContentType.startsWith("multipart/form-data; boundary="), "registration multipart content type");

			final JsonNode updates = connector.execute(null, "monitoringUpdates", Collections.<String, String>emptyMap(), null);
			check(updates.get("notifications").size() == 1, "monitoring response");
			check("GET".equals(probe.notificationMethod), "monitoring GET method");
		}
		finally
		{
			server.stop(0);
			if (previousAllowList == null) System.clearProperty(Egress.PROPERTY);
			else System.setProperty(Egress.PROPERTY, previousAllowList);
		}
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError("D&B connector test failed: " + pMessage);
	}

	private static final class Probe implements HttpHandler
	{
		String registrationMethod;
		String registrationBody;
		String registrationContentType;
		String notificationMethod;

		@Override
		public void handle(final HttpExchange pExchange)
		{
			try
			{
				final String path = pExchange.getRequestURI().getPath();
				final byte[] response;
				final int status;
				if ("/v2/token".equals(path))
				{
					response = "{\"access_token\":\"test-token\",\"expiresIn\":3600}".getBytes(StandardCharsets.UTF_8);
					status = 200;
				}
				else if (path.endsWith("/notifications"))
				{
					this.notificationMethod = pExchange.getRequestMethod();
					response = "{\"notifications\":[{\"organization\":{\"duns\":\"123456789\"}}]}".getBytes(StandardCharsets.UTF_8);
					status = 200;
				}
				else
				{
					this.registrationMethod = pExchange.getRequestMethod();
					this.registrationContentType = pExchange.getRequestHeaders().getFirst("Content-Type");
					this.registrationBody = read(pExchange.getRequestBody());
					response = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
					status = 202;
				}
				pExchange.getResponseHeaders().set("Content-Type", "application/json");
				pExchange.sendResponseHeaders(status, response.length);
				pExchange.getResponseBody().write(response);
			}
			catch (final java.io.IOException ex) { throw new IllegalStateException(ex); }
			finally { pExchange.close(); }
		}

		private static String read(final InputStream pInput) throws java.io.IOException
		{
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final byte[] buffer = new byte[1024];
			int count = pInput.read(buffer);
			while (count >= 0) { out.write(buffer, 0, count); count = pInput.read(buffer); }
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}
}
