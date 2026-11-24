package com.on.ebx.connect.connectors;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.connect.core.MessagingGuard;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Audit C2-01: what a Kafka or JMS connection row may make the server instantiate, look up or reach. Every refusal must name
 * the key or value and, where one exists, the JVM property that allows it; ordinary configurations must still pass.
 */
public final class MessagingGuardTest
{
	private MessagingGuardTest() { }

	private interface Check { void run() throws Exception; }

	public static void main(final String[] pArgs) throws Exception
	{
		kafka();
		kafkaFiles();
		jms();
		System.out.println("PASS: Kafka and JMS connection rows cannot replace the broker, load classes, or resolve foreign JNDI names");
	}

	private static void kafka() throws Exception
	{
		final Map<String, String> fine = new LinkedHashMap<String, String>();
		for (final String key : new String[] { "linger.ms", "acks", "batch.size", "compression.type", "max.request.size", "enable.idempotence",
			"auto.offset.reset", "ssl.endpoint.identification.algorithm", "security.protocol", "sasl.mechanism" })
		{
			fine.put("kafka." + key, "1");
		}
		fine.put("topic", "t"); fine.put("acks", "all"); // not kafka.* : never looked at
		MessagingGuard.checkKafkaProperties(fine);
		MessagingGuard.checkKafkaProperties(null);
		refuse("kafka.fetch.unknown.setting", MessagingGuard.PROPERTY_KAFKA_EXTRA_KEYS, "fetch.unknown.setting", "1");
		refuse("interceptor.classes", "no JVM property", "interceptor.classes", "com.x.I");
		refuse("sasl.login.callback.handler.class", "no JVM property", "sasl.login.callback.handler.class", "com.x.H");
		refuse("bootstrap.servers", "base URL", "bootstrap.servers", "evil:9092");
		refuse("Bootstrap.Servers", "base URL", "Bootstrap.Servers", "evil:9092");
		refuse("sasl.jaas.config", "no JVM property", "sasl.jaas.config", "x required;");
		refuse("sasl.oauthbearer.token.endpoint.url", "no JVM property", "sasl.oauthbearer.token.endpoint.url", "http://evil/t");
		// the exception list opens an unlisted key, but never a hard refusal
		System.setProperty(MessagingGuard.PROPERTY_KAFKA_EXTRA_KEYS, "fetch.unknown.setting, other.key");
		try
		{
			MessagingGuard.checkKafkaKey("fetch.unknown.setting", "1");
			refuse("bootstrap.servers", "base URL", "bootstrap.servers", "evil:9092");
		}
		finally { System.clearProperty(MessagingGuard.PROPERTY_KAFKA_EXTRA_KEYS); }
		final Map<String, String> hostile = new LinkedHashMap<String, String>();
		hostile.put("kafka.bootstrap.servers", "evil:9092");
		try { MessagingGuard.checkKafkaProperties(hostile); throw new AssertionError("bootstrap.servers must be refused"); }
		catch (final ConnectorException expected) { check(expected.getMessage().contains("kafka.bootstrap.servers"), "message names the key"); }
	}

	private static void kafkaFiles() throws Exception
	{
		refuse("ssl.truststore.location", MessagingGuard.PROPERTY_KAFKA_KEYSTORE_DIR, "ssl.truststore.location", "/etc/passwd");
		final File dir = File.createTempFile("kafka-keys", "");
		dir.delete();
		dir.mkdirs();
		System.setProperty(MessagingGuard.PROPERTY_KAFKA_KEYSTORE_DIR, dir.getPath());
		try
		{
			MessagingGuard.checkKafkaKey("ssl.truststore.location", new File(dir, "trust.jks").getPath());
			refuse("outside", MessagingGuard.PROPERTY_KAFKA_KEYSTORE_DIR, "ssl.keystore.location", "/etc/passwd");
			refuse("parent segment", MessagingGuard.PROPERTY_KAFKA_KEYSTORE_DIR, "ssl.keystore.location", dir.getPath() + "/../x.jks");
		}
		finally { System.clearProperty(MessagingGuard.PROPERTY_KAFKA_KEYSTORE_DIR); dir.delete(); }
	}

	private static void jms() throws Exception
	{
		for (final String factory : new String[] { "org.apache.activemq.jndi.ActiveMQInitialContextFactory",
			"org.apache.activemq.artemis.jndi.ActiveMQInitialContextFactory", "com.ibm.mq.jms.context.WMQInitialContextFactory",
			"weblogic.jndi.WLInitialContextFactory", "com.tibco.tibjms.naming.TibjmsInitialContextFactory", "", null })
		{
			MessagingGuard.checkContextFactory(factory);
		}
		final Check evil = new Check() { public void run() throws Exception { MessagingGuard.checkContextFactory("com.sun.jndi.ldap.LdapCtxFactory"); } };
		refused(evil, MessagingGuard.PROPERTY_JMS_FACTORIES);
		System.setProperty(MessagingGuard.PROPERTY_JMS_FACTORIES, "com.sun.jndi.ldap.LdapCtxFactory");
		try { MessagingGuard.checkContextFactory("com.sun.jndi.ldap.LdapCtxFactory"); }
		finally { System.clearProperty(MessagingGuard.PROPERTY_JMS_FACTORIES); }
		MessagingGuard.checkJndiName("destination", "dynamicQueues/ebx.connectors");
		MessagingGuard.checkJndiName("connection factory", "ConnectionFactory");
		for (final String name : new String[] { "ldap://evil/a", "rmi://evil/a", "queue://x", "java:comp/env/x", "a/../b", "", null, "a b", "a\nb" })
		{
			final String hostile = name;
			refused(new Check() { public void run() throws Exception { MessagingGuard.checkJndiName("destination", hostile); } }, "scheme");
		}
		MessagingGuard.checkProviderUrl(null);
		// the real transport refuses before it instantiates or looks anything up
		final ReflectiveJmsTransport transport = new ReflectiveJmsTransport();
		final String[][] hostile = { { "tcp://broker:61616", "com.sun.jndi.ldap.LdapCtxFactory", "ConnectionFactory", "q" },
			{ "tcp://broker:61616", "org.apache.activemq.jndi.ActiveMQInitialContextFactory", "ldap://evil/x", "q" },
			{ "tcp://broker:61616", "org.apache.activemq.jndi.ActiveMQInitialContextFactory", "ConnectionFactory", "rmi://evil/q" } };
		for (final String[] row : hostile)
		{
			try
			{
				transport.publish(new com.on.ebx.connect.core.MessagingTransport.Configuration(row[0], row[1], row[2], null, null, "QUEUE", 0, 0L),
					row[3], "{}", new LinkedHashMap<String, String>(), true, 4, 0L);
				throw new AssertionError("transport must refuse " + row[1] + " " + row[2] + " " + row[3]);
			}
			catch (final ConnectorException expected) { check(expected.getMessage().startsWith("JMS: "), expected.getMessage()); }
		}
	}

	private static void refuse(final String pLabel, final String pMustName, final String pKey, final String pValue) throws Exception
	{
		try
		{
			MessagingGuard.checkKafkaKey(pKey, pValue);
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().startsWith("Kafka: ") && expected.getMessage().contains(pKey.toLowerCase())
				&& expected.getMessage().contains(pMustName), pLabel + ": message must name the key and '" + pMustName + "': " + expected.getMessage());
			return;
		}
		throw new AssertionError(pLabel + " must be refused");
	}

	private static void refused(final Check pCheck, final String pMustName) throws Exception
	{
		try
		{
			pCheck.run();
		}
		catch (final ConnectorException expected)
		{
			check(expected.getMessage().startsWith("JMS: ") && expected.getMessage().contains(pMustName), "message names " + pMustName + ": " + expected.getMessage());
			return;
		}
		throw new AssertionError("expected a refusal naming " + pMustName);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
