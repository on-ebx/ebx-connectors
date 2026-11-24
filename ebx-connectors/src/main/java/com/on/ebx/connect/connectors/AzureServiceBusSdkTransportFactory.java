package com.on.ebx.connect.connectors;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.on.ebx.connect.spi.ConnectorConfig;

/** Concrete reflective adapter for azure-messaging-servicebus; no Azure classes leak into the module ABI. */
public final class AzureServiceBusSdkTransportFactory implements AzureServiceBusTransport.Factory
{
	private static final String BUILDER = "com.azure.messaging.servicebus.ServiceBusClientBuilder";
	@Override public AzureServiceBusTransport open(final ConnectorConfig pConfig) throws Exception
	{
		if (!sdkAvailable(AzureServiceBusSdkTransportFactory.class.getClassLoader()))
			throw new IllegalStateException("Azure Service Bus SDK is not packaged (azure-messaging-servicebus and dependencies are required)");
		return new Transport(pConfig);
	}

	static boolean sdkAvailable(final ClassLoader pLoader)
	{
		try
		{
			Class.forName(BUILDER, false, pLoader);
			return true;
		}
		catch (final Throwable ex) { return false; }
	}

	private static final class Locked
	{
		final Object receiver, message;
		final long retainedAt;
		Locked(final Object pReceiver, final Object pMessage)
		{
			receiver = pReceiver;
			message = pMessage;
			retainedAt = System.currentTimeMillis();
		}
	}
	private static final Map<String, Locked> LOCKS = new ConcurrentHashMap<String, Locked>();

	private static final class Transport implements AzureServiceBusTransport
	{
		private final ConnectorConfig config;
		private final List<Object> closeables = new ArrayList<Object>();
		Transport(final ConnectorConfig pConfig)
		{
			if (pConfig == null) throw new IllegalArgumentException("Configuration is required");
			config = pConfig;
			reap();
		}

		@Override public void publish(final String pEntity, final String pBody, final Map<String, String> pProperties) throws Exception
		{
			final Object senderBuilder = call(builder(), "sender");
			configureEntity(senderBuilder, pEntity, null);
			final Object sender = call(senderBuilder, "buildClient");
			closeables.add(sender);
			final Class<?> messageType = type("com.azure.messaging.servicebus.ServiceBusMessage");
			final Object message = messageType.getConstructor(String.class).newInstance(pBody);
			if (pProperties != null && !pProperties.isEmpty()) applicationProperties(message).putAll(pProperties);
			call(sender, "sendMessage", message);
		}

		@Override public List<Message> receive(final String pEntity, final String pSubscription, final int pMax,
			final long pWaitMillis, final ReceiveMode pMode) throws Exception
		{
			final Object receiverBuilder = call(builder(), "receiver");
			configureEntity(receiverBuilder, pEntity, pSubscription);
			final Class<?> modeType = type("com.azure.messaging.servicebus.models.ServiceBusReceiveMode");
			final Object mode = enumConstant(modeType, pMode == ReceiveMode.PEEK_LOCK ? "PEEK_LOCK" : "RECEIVE_AND_DELETE");
			call(receiverBuilder, "receiveMode", mode);
			final Object receiver = call(receiverBuilder, "buildClient");
			final List<Message> results = convert(call(receiver, "receiveMessages", Integer.valueOf(pMax), Duration.ofMillis(pWaitMillis)),
				pMode == ReceiveMode.PEEK_LOCK ? receiver : null);
			if (pMode == ReceiveMode.PEEK_LOCK && !results.isEmpty()) { /* retained with locks */ }
			else closeables.add(receiver);
			return results;
		}

		@Override public List<Message> peek(final String pEntity, final String pSubscription, final int pMax) throws Exception
		{
			final Object receiverBuilder = call(builder(), "receiver");
			configureEntity(receiverBuilder, pEntity, pSubscription);
			final Object receiver = call(receiverBuilder, "buildClient");
			closeables.add(receiver);
			return convert(call(receiver, "peekMessages", Integer.valueOf(pMax)), null);
		}

		@Override public void complete(final String pEntity, final String pSubscription, final String pLockToken) throws Exception { settle("complete", pLockToken); }
		@Override public void abandon(final String pEntity, final String pSubscription, final String pLockToken) throws Exception { settle("abandon", pLockToken); }
		private void settle(final String pAction, final String pLockToken) throws Exception
		{
			final Locked locked = LOCKS.remove(pLockToken);
			if (locked == null) throw new IllegalArgumentException("Unknown or expired Service Bus lock token");
			try
			{
				call(locked.receiver, pAction, locked.message);
			}
			finally
			{
				AzureServiceBusSdkTransportFactory.close(locked.receiver);
			}
		}

		private Object builder() throws Exception
		{
			final Object builder = type(BUILDER).getConstructor().newInstance();
			final String mode = config.getProperty("auth.mode", "CONNECTION_STRING");
			if ("CONNECTION_STRING".equals(mode)) call(builder, "connectionString", config.getApiKey());
			else
			{
				final Class<?> identityBuilder = type("com.azure.identity.DefaultAzureCredentialBuilder");
				final Object credential = call(identityBuilder.getConstructor().newInstance(), "build");
				call(builder, "credential", namespace(), credential);
			}
			return builder;
		}

		private void configureEntity(final Object pBuilder, final String pEntity, final String pSubscription) throws Exception
		{
			final boolean explicitTopic = pEntity.startsWith("topic:");
			final String entity = explicitTopic ? pEntity.substring(6) : pEntity;
			if (!explicitTopic && (pSubscription == null || pSubscription.trim().isEmpty())) call(pBuilder, "queueName", entity);
			else
			{
				call(pBuilder, "topicName", entity);
				if (pSubscription != null && !pSubscription.trim().isEmpty()) call(pBuilder, "subscriptionName", pSubscription);
			}
		}
		private String namespace()
		{
			String value = config.getBaseUrl();
			value = value.replaceFirst("^(?:sb|https)://", "");
			final int slash = value.indexOf('/');
			return slash < 0 ? value : value.substring(0, slash);
		}

		private List<Message> convert(final Object pIterable, final Object pLockReceiver) throws Exception
		{
			if (!(pIterable instanceof Iterable<?>)) return Collections.emptyList();
			final List<Message> results = new ArrayList<Message>();
			for (final Object raw : (Iterable<?>) pIterable)
			{
				final String id = text(call(raw, "getMessageId"));
				final Object tokenValue = call(raw, "getLockToken");
				final String token = tokenValue == null ? null : tokenValue.toString();
				final String body = text(call(raw, "getBody"));
				long enqueued = 0L;
				final Object enqueuedValue = call(raw, "getEnqueuedTime");
				if (enqueuedValue != null)
				{
					final Object instant = call(enqueuedValue, "toInstant");
					enqueued = ((Number) call(instant, "toEpochMilli")).longValue();
				}
				if (pLockReceiver != null && token != null) LOCKS.put(token, new Locked(pLockReceiver, raw));
				results.add(new Message(id, token, body, enqueued));
			}
			return results;
		}
		@Override
		public void close()
		{
			for (final Object value : closeables) AzureServiceBusSdkTransportFactory.close(value);
			closeables.clear();
		}
	}

	private static Class<?> type(final String pName) throws ClassNotFoundException { return Class.forName(pName, true, AzureServiceBusSdkTransportFactory.class.getClassLoader()); }
	@SuppressWarnings("unchecked")
	private static Map<Object, Object> applicationProperties(final Object pMessage) throws Exception
	{
		final Object value = call(pMessage, "getApplicationProperties");
		if (!(value instanceof Map<?, ?>)) throw new IllegalStateException("Azure Service Bus SDK returned invalid application properties");
		return (Map<Object, Object>) value;
	}
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static Object enumConstant(final Class<?> pType, final String pName)
	{
		if (!pType.isEnum()) throw new IllegalArgumentException(pType.getName() + " is not an enum");
		return Enum.valueOf((Class<? extends Enum>) pType.asSubclass(Enum.class), pName);
	}
	private static Object call(final Object pTarget, final String pName, final Object... pArguments) throws Exception
	{
		Method selected = null;
		for (final Method method : pTarget.getClass().getMethods())
		{
			if (!method.getName().equals(pName) || method.getParameterTypes().length != pArguments.length) continue;
			boolean compatible = true;
			final Class<?>[] types = method.getParameterTypes();
			for (int i = 0; i < types.length; i++) if (pArguments[i] != null && !wrap(types[i]).isAssignableFrom(pArguments[i].getClass()))
			{
				compatible = false;
				break;
			}
			if (compatible)
			{
				selected = method;
				break;
			}
		}
		if (selected == null) throw new NoSuchMethodException(pTarget.getClass().getName() + "." + pName);
		return selected.invoke(pTarget, pArguments);
	}
	private static Class<?> wrap(final Class<?> pType)
	{
		if (!pType.isPrimitive()) return pType;
		if (pType == int.class) return Integer.class;
		if (pType == long.class) return Long.class;
		if (pType == boolean.class) return Boolean.class;
		return pType;
	}
	private static String text(final Object pValue) { return pValue == null ? null : pValue.toString(); }
	private static void close(final Object pValue) { try { call(pValue, "close"); } catch (final Exception ignored) { } }
	private static void reap()
	{
		final long cutoff = System.currentTimeMillis() - 5L * 60L * 1000L;
		for (final Map.Entry<String, Locked> entry : LOCKS.entrySet()) if (entry.getValue().retainedAt < cutoff && LOCKS.remove(entry.getKey(), entry.getValue())) close(entry.getValue().receiver);
	}
}
