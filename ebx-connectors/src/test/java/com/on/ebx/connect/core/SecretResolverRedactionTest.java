package com.on.ebx.connect.core;

import com.on.ebx.connect.spi.SecretResolver;

/** Ensures values returned by any built-in or extension resolver enter the global redaction set. */
public final class SecretResolverRedactionTest
{
	private SecretResolverRedactionTest()
	{
	}

	public static void main(final String[] pArgs)
	{
		final String scheme = "redaction-contract";
		final String secret = "resolved-value-must-never-leak";
		SecretResolvers.get().register(new SecretResolver()
		{
			@Override public String getScheme() { return scheme; }
			@Override public String resolve(final String pReference) { return secret; }
		});
		SecretResolvers.get().invalidate();

		check(secret.equals(SecretResolvers.get().resolve(scheme, "fixture")), "resolver changed the value");
		final String message = Redaction.redact("provider echoed " + secret + " while resolving fixture");
		check(!message.contains(secret), "resolved secret remained visible in redacted text");
		check(message.contains("***"), "resolved secret was removed without the standard mask");

		System.out.println("PASS: resolved secret values are registered with global redaction");
		webhook();
		noLock();
		volume();
	}

	/** C2-02: a webhook URL is a credential: masked in what a reader is shown and in every log line. */
	private static void webhook()
	{
		final com.on.ebx.connect.spi.ConnectorConfig c = new com.on.ebx.connect.spi.ConnectorConfig();
		c.setId("slack-alerts");
		c.setType("chat-webhook");
		final String token = "AbCdEfGhIjKlMnOpQrStUv123";
		c.setBaseUrl("https://hooks.slack.com/services/T000/B000/" + token);
		Redaction.remember(c);
		// what connectors_list / connectors_describe / GET /v1/connectors put in "baseUrl", and toString()
		final String shown = Redaction.displayUrl(c);
		check(shown.startsWith("https://hooks.slack.com") && !shown.contains(token) && !shown.contains("T000"), "webhook URL shown to a reader: " + shown);
		check(!c.toString().contains(token), "ConnectorConfig.toString leaks the webhook");
		// a failure line of a REST connector quotes the full URL
		final String line = Redaction.redact("slack-alerts: POST " + c.getBaseUrl() + " failed: HTTP 404 no_service");
		check(!line.contains(token) && !line.contains("T000/B000"), "log line leaks the webhook: " + line);
		// even the path alone, as some clients quote it
		check(!Redaction.redact("404 on /services/T000/B000/" + token).contains(token), "path alone leaks");
		// user information and JDBC attributes of any URL
		check(!Redaction.redact("connect https://bob:hunter2pw@host.example/x failed").contains("hunter2pw"), "userinfo leaks");
		check(!Redaction.redact("jdbc:sqlserver://h:1433;databaseName=d;password=Sup3rS3cret;encrypt=true").contains("Sup3rS3cret"), "JDBC password leaks");
		final com.on.ebx.connect.spi.ConnectorConfig plain = new com.on.ebx.connect.spi.ConnectorConfig();
		plain.setType("rest");
		plain.setBaseUrl("https://api.example.com/v1");
		check("https://api.example.com/v1".equals(Redaction.displayUrl(plain)), "an ordinary base URL must stay readable");
		System.out.println("PASS: chat webhook URLs are masked for readers and in log lines");
	}

	/** C2-14: the read path takes no lock: it still answers while another thread holds the secrets monitor. */
	private static void noLock()
	{
		Redaction.rememberSecret("lock-test-secret-value");
		try
		{
			final java.lang.reflect.Field field = Redaction.class.getDeclaredField("SECRETS");
			field.setAccessible(true);
			final Object monitor = field.get(null);
			final String[] answer = new String[1];
			synchronized (monitor)
			{
				final Thread reader = new Thread(new Runnable()
				{
					@Override
					public void run()
					{
						answer[0] = Redaction.redact("x lock-test-secret-value y");
					}
				});
				reader.start();
				reader.join(5000L);
				check(!reader.isAlive(), "Redaction.redact waits for the secrets monitor");
			}
			check("x *** y".equals(answer[0]), "unexpected redaction: " + answer[0]);
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
		System.out.println("PASS: Redaction.redact reads without a lock");
	}

	/** C2-14: 4096 secrets, 10000 texts of 4 KB on 8 threads, same masking as the sequential reference. */
	private static void volume()
	{
		final java.util.Random random = new java.util.Random(7);
		final java.util.List<String> secrets = new java.util.ArrayList<String>();
		for (int i = 0; i < 4096; i++)
		{
			final String secret = "sec" + Long.toString(random.nextLong() & Long.MAX_VALUE, 36) + i;
			secrets.add(secret);
			Redaction.rememberSecret(secret);
		}
		final String[] texts = new String[10000];
		final String[] expected = new String[texts.length];
		for (int i = 0; i < texts.length; i++)
		{
			final StringBuilder text = new StringBuilder(4200);
			while (text.length() < 4000)
			{
				text.append("record ").append(random.nextInt(100000)).append(" value ").append(random.nextInt(1000)).append(' ');
			}
			if (i % 10 == 0)
			{
				text.append(" token=").append(secrets.get(random.nextInt(secrets.size()))).append(" end");
			}
			texts[i] = text.toString();
			expected[i] = i % 10 == 0 ? null : texts[i];
		}
		final long started = System.nanoTime();
		final java.util.concurrent.atomic.AtomicInteger bad = new java.util.concurrent.atomic.AtomicInteger();
		final Thread[] threads = new Thread[8];
		for (int t = 0; t < threads.length; t++)
		{
			final int offset = t;
			threads[t] = new Thread(new Runnable()
			{
				@Override
				public void run()
				{
					for (int i = offset; i < texts.length; i += 8)
					{
						final String out = Redaction.redact(texts[i]);
						if (i % 10 == 0 ? out.contains("sec") && out.contains(" end") && out.indexOf("token=***") < 0 : !out.equals(expected[i]))
						{
							bad.incrementAndGet();
						}
					}
				}
			});
			threads[t].start();
		}
		try
		{
			for (final Thread thread : threads)
			{
				thread.join();
			}
		}
		catch (final InterruptedException ex)
		{
			throw new AssertionError(ex);
		}
		final long millis = (System.nanoTime() - started) / 1000000L;
		check(bad.get() == 0, bad.get() + " texts were masked wrongly");
		System.out.println("PASS: 10000 texts of 4 KB against 4096 secrets on 8 threads in " + millis + " ms");
		check(millis < 20000L, "redaction too slow: " + millis + " ms");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
