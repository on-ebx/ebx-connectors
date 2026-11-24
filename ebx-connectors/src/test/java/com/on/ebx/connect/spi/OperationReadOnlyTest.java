package com.on.ebx.connect.spi;

/**
 * Read recipe + deduced scan contract, section 2 (2026-09-24): {@code Operation#isReadOnly()} is the ONE place
 * {@code readOnly} is derived, from the operation's {@code kind} and, when the connector tracks one, its
 * {@code httpMethod}. Nothing here touches EBX or the network.
 */
public final class OperationReadOnlyTest
{
	public static void main(final String[] args)
	{
		// no httpMethod known: kind alone decides (SQL SELECT, SOAP/GraphQL query, catalog/profile/sample/describe...)
		check(Operation.read("get", "d", "id").isReadOnly(), "READ, no method: read-only");
		check(Operation.search("query", "d", "sql").isReadOnly(), "SEARCH, no method: read-only");
		check(!Operation.write("update", "d", "id").isReadOnly(), "WRITE, no method: not read-only");

		// httpMethod known: GET/HEAD are read-only, everything else is not, whatever the declared kind
		check(new Operation("get", Operation.Kind.READ, "d", null, null, "GET").isReadOnly(), "GET: read-only");
		check(new Operation("head", Operation.Kind.READ, "d", null, null, "head").isReadOnly(), "HEAD, lower case: read-only");
		check(!new Operation("delete", Operation.Kind.READ, "d", null, null, "DELETE").isReadOnly(),
			"DELETE declared READ (a connector's own default-kind heuristic can miss DELETE): still not read-only");
		check(!new Operation("post", Operation.Kind.SEARCH, "d", null, null, "POST").isReadOnly(),
			"a search-style POST is not provably non-mutating: not read-only");
		check(!new Operation("put", Operation.Kind.WRITE, "d", null, null, "GET").isReadOnly(),
			"WRITE always wins over a GET method (a mutating action exposed through a GET would still be false)");

		// the 5-arg constructor (no httpMethod) behaves exactly like before: null method, same as the factories
		final Operation legacy = new Operation("op", Operation.Kind.SEARCH, "d", new String[] { "a" }, null);
		check(legacy.getHttpMethod() == null && legacy.isReadOnly(), "5-arg constructor: no method, kind decides");

		System.out.println("OperationReadOnlyTest: PASS");
	}

	static void check(final boolean condition, final String what)
	{
		if (!condition)
		{
			throw new AssertionError(what);
		}
	}
}
