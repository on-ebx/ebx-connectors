package com.on.ebx.connect.connectors;

import java.util.List;
import java.util.Map;

/** SDK isolation boundary for Azure Service Bus AMQP operations. */
public interface AzureServiceBusTransport extends AutoCloseable
{
	enum ReceiveMode { PEEK_LOCK, RECEIVE_AND_DELETE }
	final class Message
	{
		public final String messageId, lockToken, body;
		public final long enqueuedAt;
		public Message(final String pMessageId, final String pLockToken, final String pBody, final long pEnqueuedAt)
		{
			messageId = pMessageId;
			lockToken = pLockToken;
			body = pBody;
			enqueuedAt = pEnqueuedAt;
		}
	}
	void publish(String pEntity, String pBody, Map<String, String> pProperties) throws Exception;
	List<Message> receive(String pEntity, String pSubscription, int pMax, long pWaitMillis, ReceiveMode pMode) throws Exception;
	List<Message> peek(String pEntity, String pSubscription, int pMax) throws Exception;
	void complete(String pEntity, String pSubscription, String pLockToken) throws Exception;
	void abandon(String pEntity, String pSubscription, String pLockToken) throws Exception;
	@Override void close();

	interface Factory { AzureServiceBusTransport open(com.on.ebx.connect.spi.ConnectorConfig pConfig) throws Exception; }
}
