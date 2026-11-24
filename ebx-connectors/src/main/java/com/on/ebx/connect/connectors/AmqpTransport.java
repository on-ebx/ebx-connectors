package com.on.ebx.connect.connectors;

import com.on.ebx.connect.spi.ConnectorException;

/** Narrow AMQP 0-9-1 boundary which keeps the RabbitMQ client isolated from connector logic. */
interface AmqpTransport extends AutoCloseable
{
	final class Delivery
	{
		final long tag;
		final boolean redelivered;
		final byte[] body;
		final int remaining;
		Delivery(final long pTag, final boolean pRedelivered, final byte[] pBody, final int pRemaining)
		{
			tag = pTag;
			redelivered = pRedelivered;
			body = pBody;
			remaining = pRemaining;
		}
	}
	void publish(String pExchange, String pRoutingKey, byte[] pBody) throws ConnectorException;
	Delivery get(String pQueue) throws ConnectorException;
	void ack(long pTag) throws ConnectorException;
	void nack(long pTag, boolean pRequeue) throws ConnectorException;
	void close();
}
