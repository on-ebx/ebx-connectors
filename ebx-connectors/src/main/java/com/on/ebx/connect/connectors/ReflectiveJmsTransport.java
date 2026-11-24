package com.on.ebx.connect.connectors;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.naming.Context;
import javax.naming.InitialContext;

import com.on.ebx.connect.core.MessagingGuard;
import com.on.ebx.connect.core.MessagingTransport;

/** JMS 1.1 runtime isolated behind reflection so providers own the JMS API version. */
final class ReflectiveJmsTransport implements MessagingTransport
{
	@Override public PublishResult publish(final Configuration c, final String destination, final String body,
		final Map<String, String> headers, final boolean persistent, final int priority, final long ttl) throws Exception
	{
		final Resources r = open(c, destination, false, Acknowledgement.AUTO, null);
		Object producer = null;
		try
		{
			producer = call(r.session, "createProducer", r.destination);
			final Object message = call(r.session, "createTextMessage", body);
			if (headers != null) for (final Map.Entry<String, String> header : headers.entrySet()) call(message, "setStringProperty", header.getKey(), header.getValue());
			call(producer, "send", message, Integer.valueOf(persistent ? 2 : 1), Integer.valueOf(priority), Long.valueOf(ttl));
			return new PublishResult(string(call(message, "getJMSMessageID")), number(call(message, "getJMSTimestamp")));
		}
		finally
		{
			close(producer);
			r.close();
		}
	}
	@Override public List<Message> receive(final Configuration c, final String destination, final int maximum, final long timeout,
		final Acknowledgement acknowledgement, final boolean peek, final String selector) throws Exception
	{
		final Resources r = open(c, destination, true, peek ? Acknowledgement.TRANSACTED : acknowledgement, selector);
		Object consumer = null;
		try
		{
			consumer = selector == null || selector.trim().isEmpty() ? call(r.session, "createConsumer", r.destination)
				: call(r.session, "createConsumer", r.destination, selector);
			call(r.connection, "start");
			final List<Message> result = new ArrayList<Message>();
			for (int i = 0; i < maximum; i++)
			{
				final Object message = call(consumer, "receive", Long.valueOf(i == 0 ? timeout : 1L));
				if (message == null) break;
				String body;
				try
				{
					body = string(call(message, "getText"));
				}
				catch (final NoSuchMethodException ex)
				{
					body = String.valueOf(message);
				}
				result.add(new Message(string(call(message, "getJMSMessageID")), string(call(message, "getJMSCorrelationID")), body,
					number(call(message, "getJMSTimestamp")), Boolean.TRUE.equals(call(message, "getJMSRedelivered")), Collections.<String, String>emptyMap()));
				if (!peek && acknowledgement == Acknowledgement.CLIENT) call(message, "acknowledge");
			}
			if (peek) call(r.session, "rollback");
			else if (acknowledgement == Acknowledgement.TRANSACTED) call(r.session, "commit");
			return result;
		}
		finally
		{
			close(consumer);
			r.close();
		}
	}
	private static Resources open(final Configuration c, final String destination, final boolean consumer,
		final Acknowledgement acknowledgement, final String selector) throws Exception
	{
		// audit C2-01: the factory class, the provider hosts and the JNDI names are checked before anything is instantiated or looked up
		MessagingGuard.checkContextFactory(c.initialContextFactory);
		MessagingGuard.checkProviderUrl(c.providerUrl);
		MessagingGuard.checkJndiName("connection factory", c.connectionFactory);
		MessagingGuard.checkJndiName("destination", destination);
		final Hashtable<String, String> environment = new Hashtable<String, String>();
		if (c.initialContextFactory != null && !c.initialContextFactory.isEmpty()) environment.put(Context.INITIAL_CONTEXT_FACTORY, c.initialContextFactory);
		if (c.providerUrl != null && !c.providerUrl.isEmpty()) environment.put(Context.PROVIDER_URL, c.providerUrl);
		final Context context = environment.isEmpty() ? new InitialContext() : new InitialContext(environment);
		try
		{
			final Object factory = context.lookup(c.connectionFactory);
			final Object connection = blank(c.username) ? call(factory, "createConnection") : call(factory, "createConnection", c.username, c.password);
			try
			{
				final Object policy = call(connection, "getRedeliveryPolicy");
				call(policy, "setMaximumRedeliveries", Integer.valueOf(c.maximumRedeliveries));
				call(policy, "setInitialRedeliveryDelay", Long.valueOf(c.redeliveryDelayMillis));
			}
			catch (final NoSuchMethodException ignored) { /* Provider has no configurable redelivery policy. */ }
			final boolean transacted = acknowledgement == Acknowledgement.TRANSACTED;
			final int mode = transacted ? 0 : acknowledgement == Acknowledgement.CLIENT ? 2 : acknowledgement == Acknowledgement.DUPS_OK ? 3 : 1;
			final Object session = call(connection, "createSession", Boolean.valueOf(transacted), Integer.valueOf(mode));
			Object target;
			try { target = context.lookup(destination); }
			catch (final Exception absent) { target = call(session, "TOPIC".equalsIgnoreCase(c.destinationType) ? "createTopic" : "createQueue", destination); }
			return new Resources(context, connection, session, target);
		}
		catch (final Exception ex)
		{
			try
			{
				context.close();
			}
			catch (final Exception ignored) { }
			throw ex;
		}
	}
	private static Object call(final Object target, final String name, final Object... args) throws Exception
	{
		for (final Method method : target.getClass().getMethods()) if (method.getName().equals(name) && method.getParameterTypes().length == args.length && accepts(method.getParameterTypes(), args))
		{
			method.setAccessible(true);
			return method.invoke(target, args);
		}
		throw new NoSuchMethodException(target.getClass().getName() + "." + name);
	}
	private static boolean accepts(final Class<?>[] types, final Object[] args)
	{
		for (int i = 0; i < types.length; i++) if (args[i] != null && !box(types[i]).isAssignableFrom(args[i].getClass())) return false;
		return true;
	}
	private static Class<?> box(final Class<?> type)
	{
		if (!type.isPrimitive()) return type;
		if (type == Integer.TYPE) return Integer.class;
		if (type == Long.TYPE) return Long.class;
		if (type == Boolean.TYPE) return Boolean.class;
		return type;
	}
	private static void close(final Object value) { if (value != null) try { call(value, "close"); } catch (final Exception ignored) { } }
	private static boolean blank(final String value) { return value == null || value.trim().isEmpty(); }
	private static String string(final Object value) { return value == null ? null : String.valueOf(value); }
	private static long number(final Object value) { return value instanceof Number ? ((Number) value).longValue() : 0L; }
	private static final class Resources
	{
		final Context context;
		final Object connection, session, destination;
		Resources(final Context c, final Object cn, final Object s, final Object d)
		{
			context = c;
			connection = cn;
			session = s;
			destination = d;
		}
		void close()
		{
			ReflectiveJmsTransport.close(session);
			ReflectiveJmsTransport.close(connection);
			try
			{
				context.close();
			}
			catch (final Exception ignored) { }
		}
	}
}
