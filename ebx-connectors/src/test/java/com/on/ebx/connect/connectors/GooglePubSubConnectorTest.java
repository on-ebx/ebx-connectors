package com.on.ebx.connect.connectors;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.Egress;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.test.MockSaasHttpServer;

/** Offline Pub/Sub HTTP contract: bounded calls and pull/ack separation. */
public final class GooglePubSubConnectorTest
{
	private GooglePubSubConnectorTest() { }
	public static void main(final String[] pArgs) throws Exception
	{
		final MockSaasHttpServer server = new MockSaasHttpServer();
		final String old = System.getProperty(Egress.PROPERTY);
		try
		{
			System.setProperty(Egress.PROPERTY, "127.0.0.1:" + server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1));
			final ConnectorConfig config = new ConnectorConfig(); config.setId("pubsub-test"); config.setType("google-pubsub");
			config.setBaseUrl(server.baseUrl() + "/v1"); config.setProperty("project", "demo-project");
			config.setAuthType(ConnectorConfig.AuthType.BEARER); config.setApiKey("fixture-token");
			final GooglePubSubConnector connector = new GooglePubSubConnector(); connector.configure(config);

			server.enqueue("/v1/projects/demo-project/topics/events:publish", new MockSaasHttpServer.Response(200, "{\"messageIds\":[\"m1\"]}"));
			final Map<String,String> publish = map("topic", "events", "data", "hello");
			check("m1".equals(connector.execute(null, "publish", publish, null).path("messageIds").get(0).asText()), "publish");
			final MockSaasHttpServer.Request sent = find(server, "/v1/projects/demo-project/topics/events:publish");
			check("Bearer fixture-token".equals(sent.header("Authorization")) && sent.body.contains("aGVsbG8="), "auth/base64 body");
			final Map<String,String> redirectHeaders = new LinkedHashMap<String,String>();
			redirectHeaders.put("Location", "https://attacker.invalid/collect");
			server.enqueue("/v1/projects/demo-project/topics/events:publish", new MockSaasHttpServer.Response(302, "{}", redirectHeaders));
			check(fails(connector, "publish", publish).contains("HTTP 302"), "authorization-bearing redirect was followed");

			server.enqueue("/v1/projects/demo-project/subscriptions/jobs:pull", new MockSaasHttpServer.Response(200,
				"{\"receivedMessages\":[{\"ackId\":\"a1\",\"message\":{\"data\":\"eA==\"}}]}"));
			final JsonNode pulled = connector.execute(null, "pull", map("subscription", "jobs", "maxMessages", "5"), null);
			check(!pulled.path("autoAcknowledged").asBoolean(true) && "a1".equals(pulled.path("receivedMessages").get(0).path("ackId").asText()), "pull without ack");
			check(server.calls("/v1/projects/demo-project/subscriptions/jobs:acknowledge") == 0, "pull auto-acked");

			server.enqueue("/v1/projects/demo-project/subscriptions/jobs:acknowledge", new MockSaasHttpServer.Response(200, "{}"));
			check(connector.execute(null, "ack", map("subscription", "jobs", "ackIds", "a1,a2"), null).path("acknowledged").asInt() == 2, "explicit ack");
			server.enqueue("/v1/projects/demo-project/topics", new MockSaasHttpServer.Response(200, "{\"topics\":[{\"name\":\"projects/demo-project/topics/events\"}]}"));
			check(connector.execute(null, "topics", map("pageSize", "10"), null).path("topics").size() == 1, "topic discovery");
			server.enqueue("/v1/projects/demo-project/subscriptions", new MockSaasHttpServer.Response(200, "{\"subscriptions\":[{\"name\":\"projects/demo-project/subscriptions/jobs\"}]}"));
			check(connector.execute(null, "subscriptions", map("pageSize", "10"), null).path("subscriptions").size() == 1, "subscription discovery");
			check(fails(connector, "pull", map("subscription", "jobs", "maxMessages", "1001")).contains("between 1 and 1000"), "pull bound");
			check(connector.getDescriptor().getOperations().size() == 5, "descriptor operations");
			System.out.println("PASS: Google Pub/Sub bounded publish, non-auto-ack pull, explicit ack, discovery, bearer auth, and endpoint safety");
		}
		finally { server.close(); if (old == null) System.clearProperty(Egress.PROPERTY); else System.setProperty(Egress.PROPERTY, old); }
	}
	private static Map<String,String> map(final String... pValues) { final Map<String,String> m=new LinkedHashMap<String,String>(); for(int i=0;i<pValues.length;i+=2)m.put(pValues[i],pValues[i+1]); return m; }
	private static MockSaasHttpServer.Request find(final MockSaasHttpServer pServer, final String pPath) { for(final MockSaasHttpServer.Request r:pServer.requests())if(pPath.equals(r.path))return r;throw new AssertionError(pPath); }
	private static String fails(final GooglePubSubConnector pConnector, final String pOperation, final Map<String,String> pInputs) { try{pConnector.execute(null,pOperation,pInputs,null);}catch(final ConnectorException ex){return ex.getMessage();}throw new AssertionError("expected failure"); }
	private static void check(final boolean pCondition, final String pMessage){if(!pCondition)throw new AssertionError(pMessage);}
}
