package com.on.ebx.connect.connectors;

import java.util.Map;

import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.Operation;

/**
 * Read recipe + deduced scan contract, section 2 (2026-09-24): {@code GenericRestConnector} is the one connector
 * that declares an HTTP method per operation from plain configuration, so it is the one place
 * {@code Operation#isReadOnly()} needs that method rather than {@code kind} alone - in particular, an
 * unconfigured DELETE defaults to {@code Kind.READ} (see {@link GenericRestConnector#getOperations()}), which
 * would wrongly read as read-only without the method. No network, no EBX.
 */
public final class GenericRestConnectorReadOnlyTest
{
	public static void main(final String[] args)
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("generic-readonly-test");
		config.setBaseUrl("https://example.test");
		config.setAuthType(ConnectorConfig.AuthType.NONE);
		config.setProperty("op.list.method", "GET");
		config.setProperty("op.list.path", "/things");
		config.setProperty("op.remove.method", "DELETE");
		config.setProperty("op.remove.path", "/things/{id}");
		config.setProperty("op.search.method", "POST"); // POST used for a query-style search: still not GET/HEAD
		config.setProperty("op.search.path", "/things/search");
		config.setProperty("op.search.kind", "SEARCH");
		config.setProperty("op.create.method", "POST");
		config.setProperty("op.create.path", "/things");

		final GenericRestConnector connector = new GenericRestConnector();
		connector.configure(config);

		final Map<String, Operation> byName = new java.util.LinkedHashMap<String, Operation>();
		for (final Operation op : connector.getOperations())
		{
			byName.put(op.getName(), op);
		}

		check(byName.get("list").getKind() == Operation.Kind.READ && byName.get("list").isReadOnly(), "GET: READ kind, read-only");
		check(byName.get("remove").getKind() == Operation.Kind.READ, "DELETE with no op.<name>.kind override defaults to Kind.READ (the connector's own heuristic)");
		check(!byName.get("remove").isReadOnly(), "DELETE is never read-only, even though its default kind says READ");
		check(byName.get("search").getKind() == Operation.Kind.SEARCH, "explicit kind override to SEARCH");
		check(!byName.get("search").isReadOnly(), "SEARCH transported over POST is not provably non-mutating: not read-only");
		check(byName.get("create").getKind() == Operation.Kind.WRITE && !byName.get("create").isReadOnly(), "POST: WRITE kind, not read-only");

		System.out.println("GenericRestConnectorReadOnlyTest: PASS");
	}

	static void check(final boolean condition, final String what)
	{
		if (!condition)
		{
			throw new AssertionError(what);
		}
	}
}
