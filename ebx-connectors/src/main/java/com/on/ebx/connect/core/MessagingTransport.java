package com.on.ebx.connect.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provider-neutral, bounded messaging contract used by JMS and future brokers. */
public interface MessagingTransport
{
	PublishResult publish(Configuration pConfiguration, String pDestination, String pBody, Map<String, String> pHeaders,
		boolean pPersistent, int pPriority, long pTimeToLiveMillis) throws Exception;
	List<Message> receive(Configuration pConfiguration, String pDestination, int pMaximum, long pTimeoutMillis,
		Acknowledgement pAcknowledgement, boolean pPeek, String pSelector) throws Exception;

	enum Acknowledgement { AUTO, CLIENT, DUPS_OK, TRANSACTED }
	final class Configuration
	{
		public final String providerUrl, initialContextFactory, connectionFactory, username, password, destinationType;
		public final int maximumRedeliveries;
		public final long redeliveryDelayMillis;
		public Configuration(final String pProviderUrl, final String pInitialContextFactory, final String pConnectionFactory,
			final String pUsername, final String pPassword, final String pDestinationType,
			final int pMaximumRedeliveries, final long pRedeliveryDelayMillis)
		{
			providerUrl = pProviderUrl;
			initialContextFactory = pInitialContextFactory;
			connectionFactory = pConnectionFactory;
			username = pUsername;
			password = pPassword;
			destinationType = pDestinationType;
			maximumRedeliveries = pMaximumRedeliveries;
			redeliveryDelayMillis = pRedeliveryDelayMillis;
		}
	}
	final class PublishResult
	{
		public final String messageId;
		public final long timestamp;
		public PublishResult(final String pMessageId, final long pTimestamp)
		{
			messageId = pMessageId;
			timestamp = pTimestamp;
		}
	}
	final class Message
	{
		public final String id, correlationId, body;
		public final long timestamp;
		public final boolean redelivered;
		public final Map<String, String> properties;
		public Message(final String pId, final String pCorrelationId, final String pBody, final long pTimestamp,
			final boolean pRedelivered, final Map<String, String> pProperties)
		{
			id = pId;
			correlationId = pCorrelationId;
			body = pBody;
			timestamp = pTimestamp;
			redelivered = pRedelivered;
			properties = Collections.unmodifiableMap(new LinkedHashMap<String, String>(pProperties == null ? Collections.<String, String>emptyMap() : pProperties));
		}
	}
}
