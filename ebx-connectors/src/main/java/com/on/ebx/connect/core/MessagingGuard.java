package com.on.ebx.connect.core;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.connect.spi.ConnectorException;

/**
 * What a Kafka or JMS connection row may make the EBX server do (audit C2-01). A connector row is data a designer edits,
 * yet the {@code kafka.*} entries are copied into the Kafka client configuration (a bootstrap list, a login module, an
 * interceptor class, a key store path) and a JMS row names the JNDI context factory the server instantiates and the names
 * it looks up (a name with a URL scheme is resolved by JNDI whatever the provider). Each check refuses by default and names
 * the JVM property that allows the exception.
 * <ul>
 * <li>Kafka: only the keys of an allow-list reach the client; {@code bootstrap.servers} (the host comes from the base URL,
 * which {@link Egress} controls), every key ending in {@code .class} or {@code .classes}, the JAAS and OAuth endpoint keys
 * are never accepted; a {@code *.location} (trust store, key store) must lie under
 * {@value #PROPERTY_KAFKA_KEYSTORE_DIR}; {@value #PROPERTY_KAFKA_EXTRA_KEYS} adds keys to the allow-list.</li>
 * <li>JMS: the context factory must be one of the known providers ({@value #PROPERTY_JMS_FACTORIES} adds classes), JNDI
 * names match {@code [A-Za-z0-9/._-]+} (no {@code :}, so no URL scheme, and no {@code ..}), and every host of the provider
 * URL goes through {@link Egress}.</li>
 * </ul>
 * Pure: no connection; the JVM properties are read at each call.
 */
public final class MessagingGuard
{
	public static final String PROPERTY_KAFKA_EXTRA_KEYS = "ebx.connectors.kafka.extraAllowedKeys";
	public static final String PROPERTY_KAFKA_KEYSTORE_DIR = "ebx.connectors.kafka.keystoreDir";
	public static final String PROPERTY_JMS_FACTORIES = "ebx.connectors.jms.allowedContextFactories";

	private static final Set<String> KAFKA_KEYS = MessagingGuard.set("acks", "linger.ms", "batch.size", "compression.type",
		"max.request.size", "request.timeout.ms", "delivery.timeout.ms", "enable.idempotence", "client.id", "group.id",
		"auto.offset.reset", "max.poll.records", "session.timeout.ms", "security.protocol", "sasl.mechanism", "ssl.protocol",
		"ssl.enabled.protocols", "ssl.endpoint.identification.algorithm",
		// behaviour settings of the same family: timeouts, buffers, fetch sizes, retries
		"retries", "retry.backoff.ms", "max.in.flight.requests.per.connection", "buffer.memory", "max.block.ms", "send.buffer.bytes",
		"receive.buffer.bytes", "fetch.min.bytes", "fetch.max.bytes", "fetch.max.wait.ms", "max.partition.fetch.bytes",
		"heartbeat.interval.ms", "max.poll.interval.ms", "isolation.level", "connections.max.idle.ms", "reconnect.backoff.ms",
		"reconnect.backoff.max.ms", "metadata.max.age.ms", "default.api.timeout.ms", "sasl.kerberos.service.name",
		// TLS material: the passwords and types are plain values; the two *.location keys are confined to a folder below
		"ssl.keystore.type", "ssl.truststore.type", "ssl.keystore.password", "ssl.key.password", "ssl.truststore.password",
		"ssl.keymanager.algorithm", "ssl.trustmanager.algorithm", "ssl.cipher.suites", "ssl.truststore.location",
		"ssl.keystore.location");

	/** Never accepted, whatever the JVM properties say: each one replaces something the module computes or controls. */
	private static final Map<String, String> KAFKA_NEVER = new LinkedHashMap<String, String>();

	/** Context factories of the JMS providers the module has connectors or profiles for. */
	private static final Set<String> JMS_FACTORIES = MessagingGuard.set(
		"org.apache.activemq.jndi.ActiveMQInitialContextFactory", // ActiveMQ Classic
		"org.apache.activemq.artemis.jndi.ActiveMQInitialContextFactory", // ActiveMQ Artemis
		"com.ibm.mq.jms.context.WMQInitialContextFactory", // IBM MQ
		"weblogic.jndi.WLInitialContextFactory", // Oracle WebLogic
		"com.tibco.tibjms.naming.TibjmsInitialContextFactory"); // TIBCO EMS

	private static final Pattern JNDI_NAME = Pattern.compile("[A-Za-z0-9/._-]+");
	private static final Pattern PROVIDER_HOST = Pattern.compile("(?i)[a-z][a-z0-9+.-]*://([^/?,()\\s]+)");

	static
	{
		KAFKA_NEVER.put("bootstrap.servers", "the broker address comes from the connection's base URL, which the outbound address rules check");
		KAFKA_NEVER.put("sasl.jaas.config", "the login module is built by the module from the connection's user and password");
		KAFKA_NEVER.put("sasl.oauthbearer.token.endpoint.url", "it names another host the address rules do not see");
		KAFKA_NEVER.put("sasl.oauthbearer.jwks.endpoint.url", "it names another host the address rules do not see");
	}

	private MessagingGuard()
	{
	}

	private static Set<String> set(final String... pValues)
	{
		return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(pValues)));
	}

	private static Set<String> listed(final String pProperty)
	{
		final Set<String> values = new HashSet<String>();
		final String raw = System.getProperty(pProperty);
		if (raw != null)
		{
			for (final String part : raw.split(","))
			{
				if (!part.trim().isEmpty())
				{
					values.add(part.trim());
				}
			}
		}
		return values;
	}

	/**
	 * Refuses a {@code kafka.*} connector property (the key with the prefix removed, as the client receives it) that is not
	 * on the allow-list, that replaces what the module controls, that names a class, or that points a file outside the
	 * allowed folder.
	 */
	public static void checkKafkaKey(final String pKey, final String pValue) throws ConnectorException
	{
		final String key = pKey == null ? "" : pKey.trim().toLowerCase(Locale.ROOT);
		if (KAFKA_NEVER.containsKey(key))
		{
			throw new ConnectorException("Kafka: the property kafka." + key + " is refused: " + KAFKA_NEVER.get(key)
				+ " (no JVM property allows it)");
		}
		if (key.endsWith(".class") || key.endsWith(".classes"))
		{
			throw new ConnectorException("Kafka: the property kafka." + key + " is refused: the client would load the class it names"
				+ " (no JVM property allows it)");
		}
		if (!KAFKA_KEYS.contains(key) && !MessagingGuard.listed(MessagingGuard.PROPERTY_KAFKA_EXTRA_KEYS).contains(key))
		{
			throw new ConnectorException("Kafka: the property kafka." + key + " is not on the list of client settings a connection may"
				+ " set; the JVM property " + MessagingGuard.PROPERTY_KAFKA_EXTRA_KEYS + " lists other allowed keys");
		}
		if (key.endsWith(".location"))
		{
			MessagingGuard.checkKafkaFile(key, pValue);
		}
	}

	private static void checkKafkaFile(final String pKey, final String pValue) throws ConnectorException
	{
		final String dir = System.getProperty(MessagingGuard.PROPERTY_KAFKA_KEYSTORE_DIR);
		final String refusal = "Kafka: the property kafka." + pKey + " is refused: a key store or trust store is read from the server's"
			+ " filesystem; the JVM property " + MessagingGuard.PROPERTY_KAFKA_KEYSTORE_DIR + " names the one folder it may be in";
		if (dir == null || dir.trim().isEmpty() || pValue == null || pValue.trim().isEmpty())
		{
			throw new ConnectorException(refusal);
		}
		try
		{
			final String root = new File(dir.trim()).getCanonicalPath();
			final String path = new File(pValue.trim()).getCanonicalPath();
			if (!path.startsWith(root + File.separator))
			{
				throw new ConnectorException(refusal);
			}
		}
		catch (final IOException unreadable)
		{
			throw new ConnectorException(refusal, unreadable);
		}
	}

	/** Applies {@link #checkKafkaKey} to every {@code kafka.*} entry of a connector's properties (prefix still on). */
	public static void checkKafkaProperties(final Map<String, String> pProperties) throws ConnectorException
	{
		if (pProperties == null)
		{
			return;
		}
		for (final Map.Entry<String, String> property : pProperties.entrySet())
		{
			if (property.getKey() != null && property.getKey().startsWith("kafka.") && property.getValue() != null)
			{
				MessagingGuard.checkKafkaKey(property.getKey().substring(6), property.getValue());
			}
		}
	}

	/** Refuses a JNDI context factory that is not a known provider's. A blank factory (the JVM's own) is left to the caller. */
	public static void checkContextFactory(final String pFactory) throws ConnectorException
	{
		final String factory = pFactory == null ? "" : pFactory.trim();
		if (!factory.isEmpty() && !JMS_FACTORIES.contains(factory)
			&& !MessagingGuard.listed(MessagingGuard.PROPERTY_JMS_FACTORIES).contains(factory))
		{
			throw new ConnectorException("JMS: the context factory " + factory + " is refused: a class named by a connector row is"
				+ " instantiated by the server; the JVM property " + MessagingGuard.PROPERTY_JMS_FACTORIES
				+ " lists other allowed factory classes");
		}
	}

	/** Refuses a JNDI name (connection factory, destination) outside {@code [A-Za-z0-9/._-]+}: no {@code :} means no URL scheme. */
	public static void checkJndiName(final String pKind, final String pName) throws ConnectorException
	{
		if (pName == null || !JNDI_NAME.matcher(pName).matches() || pName.contains(".."))
		{
			throw new ConnectorException("JMS: the " + pKind + " name is refused: it may only hold letters, digits and / . _ - (a name"
				+ " with a scheme such as ldap:// or rmi:// would be resolved by JNDI whatever the provider)");
		}
	}

	/** Sends every host of a provider URL ({@code tcp://a:61616}, {@code failover:(tcp://a:1,tcp://b:2)}) through {@link Egress}. */
	public static void checkProviderUrl(final String pUrl)
	{
		if (pUrl == null || pUrl.trim().isEmpty())
		{
			return;
		}
		final Matcher matcher = PROVIDER_HOST.matcher(pUrl);
		boolean found = false;
		while (matcher.find())
		{
			found = true;
			Egress.check(matcher.group(1));
		}
		if (!found)
		{
			Egress.check(pUrl);
		}
	}
}
