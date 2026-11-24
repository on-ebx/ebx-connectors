package com.on.ebx.connect.connectors;

import java.lang.reflect.Method;

import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/** Runtime adapter for com.rabbitmq:amqp-client; reflection keeps the optional API out of compilation. */
final class ReflectiveRabbitMqTransport implements AmqpTransport
{
	private final Object connection, channel;
	ReflectiveRabbitMqTransport(final ConnectorConfig pConfig) throws ConnectorException
	{
		try
		{
			final Class<?> factoryType = Class.forName("com.rabbitmq.client.ConnectionFactory");
			final Object factory = factoryType.newInstance();
			factoryType.getMethod("setUri", String.class).invoke(factory, pConfig.getBaseUrl());
			if (pConfig.getUsername() != null && !pConfig.getUsername().isEmpty()) factoryType.getMethod("setUsername", String.class).invoke(factory, pConfig.getUsername());
			if (pConfig.getPassword() != null && !pConfig.isPlaceholder(pConfig.getPassword())) factoryType.getMethod("setPassword", String.class).invoke(factory, pConfig.getPassword());
			factoryType.getMethod("setConnectionTimeout", int.class).invoke(factory, Integer.valueOf(pConfig.getConnectTimeoutMs()));
			factoryType.getMethod("setHandshakeTimeout", int.class).invoke(factory, Integer.valueOf(pConfig.getReadTimeoutMs()));
			connection = factoryType.getMethod("newConnection").invoke(factory);
			channel = connection.getClass().getMethod("createChannel").invoke(connection);
			final int prefetch = Math.max(1, Math.min(500, integer(pConfig.getProperty("prefetch", "100"), 100)));
			channel.getClass().getMethod("basicQos", int.class).invoke(channel, Integer.valueOf(prefetch));
		}
		catch (final Exception ex) { throw failure("connect", ex); }
	}
	public void publish(final String pExchange, final String pRoutingKey, final byte[] pBody) throws ConnectorException
	{
		try { channel.getClass().getMethod("basicPublish", String.class, String.class, Class.forName("com.rabbitmq.client.AMQP$BasicProperties"), byte[].class)
			.invoke(channel, pExchange, pRoutingKey, null, pBody); }
		catch (final Exception ex) { throw failure("publish", ex); }
	}
	public Delivery get(final String pQueue) throws ConnectorException
	{
		try
		{
			final Object response = channel.getClass().getMethod("basicGet", String.class, boolean.class).invoke(channel, pQueue, Boolean.FALSE);
			if (response == null) return null;
			final Object envelope = response.getClass().getMethod("getEnvelope").invoke(response);
			final long tag = ((Number) envelope.getClass().getMethod("getDeliveryTag").invoke(envelope)).longValue();
			final boolean redelivered = ((Boolean) envelope.getClass().getMethod("isRedeliver").invoke(envelope)).booleanValue();
			final byte[] body = (byte[]) response.getClass().getMethod("getBody").invoke(response);
			final int remaining = ((Number) response.getClass().getMethod("getMessageCount").invoke(response)).intValue();
			return new Delivery(tag, redelivered, body, remaining);
		}
		catch (final Exception ex) { throw failure("receive", ex); }
	}
	public void ack(final long pTag) throws ConnectorException { invokeDisposition("basicAck", pTag, false); }
	public void nack(final long pTag, final boolean pRequeue) throws ConnectorException { invokeDisposition("basicNack", pTag, pRequeue); }
	private void invokeDisposition(final String pName, final long pTag, final boolean pFlag) throws ConnectorException
	{
		try
		{
			if ("basicAck".equals(pName)) channel.getClass().getMethod(pName, long.class, boolean.class).invoke(channel, Long.valueOf(pTag), Boolean.FALSE);
			else channel.getClass().getMethod(pName, long.class, boolean.class, boolean.class).invoke(channel, Long.valueOf(pTag), Boolean.FALSE, Boolean.valueOf(pFlag));
		}
		catch (final Exception ex) { throw failure("acknowledge", ex); }
	}
	public void close()
	{
		close(channel);
		close(connection);
	}
	private static void close(final Object pObject)
	{
		if (pObject != null) try
		{
			final Method method = pObject.getClass().getMethod("close");
			method.invoke(pObject);
		}
		catch (final Exception ignored) { }
	}
	private static int integer(final String pValue, final int pDefault)
	{
		try
		{
			return Integer.parseInt(pValue);
		}
		catch (final Exception ex)
		{
			return pDefault;
		}
	}
	private static ConnectorException failure(final String pAction, final Exception pCause)
	{ return new ConnectorException("RabbitMQ " + pAction + " failed: " + (pCause.getCause() == null ? pCause.getMessage() : pCause.getCause().getMessage()), pCause); }
}
