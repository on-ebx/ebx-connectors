package com.on.ebx.connect.ebx;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.connect.ebx.scheduler.RestSchemaRefreshScheduledTask;

/** Account-free checks for adapter bounds, stable keys and persistence delegation. */
public final class EbxRestSchemaRefreshStoreTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		final MemoryBackend backend = new MemoryBackend();
		final EbxRestSchemaRefreshStore store = new EbxRestSchemaRefreshStore(backend, "salesforce", "https://example.test/openapi.json");
		final String key = store.stateKey();
		assertTrue(key.equals(EbxRestSchemaRefreshStore.keyFor("https://example.test/openapi.json")), "key is stable");
		assertTrue(key.length() == 71, "key has bounded SHA-256 representation");
		store.save(key, "{\"checkedAt\":1}");
		assertTrue("{\"checkedAt\":1}".equals(store.load(key)), "state round trips through backend");
		expectIo(new Call() { public void run() throws Exception { store.load("wrong"); } }, "foreign key rejected");
		expectIo(new Call() { public void run() throws Exception {
			store.save(key, repeat('x', EbxRestSchemaRefreshStore.MAX_STATE_CHARS + 1));
		} }, "oversized state rejected");
		assertTrue(RestSchemaRefreshScheduledTaskTestAccess.minutes("1") == 1L, "minimum interval accepted");
		assertTrue(RestSchemaRefreshScheduledTaskTestAccess.minutes("10080") == 10080L, "maximum interval accepted");
		System.out.println("PASS: EBX schema refresh adapter bounds and restart-safe delegation");
	}

	private static final class MemoryBackend implements EbxRestSchemaRefreshStore.Backend
	{
		private final Map<String, String> values = new LinkedHashMap<String, String>();
		public String load(final String pConnector, final String pKey) { return this.values.get(pConnector + "|" + pKey); }
		public void save(final String pConnector, final String pKey, final String pUrl, final String pState)
		{ this.values.put(pConnector + "|" + pKey, pState); }
	}
	private interface Call { void run() throws Exception; }
	private static void expectIo(final Call pCall, final String pMessage) throws Exception
	{
		try { pCall.run(); throw new AssertionError(pMessage); }
		catch (final IOException expected) { }
	}
	private static String repeat(final char pChar, final int pCount)
	{
		final char[] chars = new char[pCount]; java.util.Arrays.fill(chars, pChar); return new String(chars);
	}
	private static void assertTrue(final boolean pValue, final String pMessage)
	{ if (!pValue) throw new AssertionError(pMessage); }
}

/** Keeps the production scheduler's parser package-private while testing it without EBX runtime state. */
final class RestSchemaRefreshScheduledTaskTestAccess
{
	static long minutes(final String pValue)
	{
		try
		{
			final java.lang.reflect.Method method = RestSchemaRefreshScheduledTask.class.getDeclaredMethod("boundedMinutes", String.class);
			method.setAccessible(true);
			return ((Long) method.invoke(null, pValue)).longValue();
		}
		catch (final Exception ex) { throw new IllegalStateException(ex); }
	}
}
