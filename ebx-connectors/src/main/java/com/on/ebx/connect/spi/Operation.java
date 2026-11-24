package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One operation a connector exposes: a name, what it does (read one object, search, write),
 * and the inputs it needs. Inputs are plain strings resolved by a mapping from the EBX record
 * (see {@code MappingDefinition#getInputs()}).
 */
public final class Operation
{
	public enum Kind
	{
		READ, SEARCH, WRITE
	}

	private final String name;
	private final Kind kind;
	private final String description;
	private final List<String> requiredInputs;
	private final List<String> optionalInputs;
	private final String httpMethod;

	public Operation(final String pName, final Kind pKind, final String pDescription,
		final String[] pRequiredInputs, final String[] pOptionalInputs)
	{
		this(pName, pKind, pDescription, pRequiredInputs, pOptionalInputs, null);
	}

	/**
	 * {@code pHttpMethod}: the HTTP verb this operation transports over, when the connector definition already
	 * knows it (a configuration-driven REST connector such as {@code GenericRestConnector}); {@code null} for
	 * every operation that is not HTTP-transported, or whose connector does not track a method per operation
	 * (JDBC, SOAP, GraphQL, SQL-family statement APIs: their {@link Kind} alone already says read or write).
	 * Read recipe + deduced scan (2026-09-24): the one signal, together with {@link #kind}, that
	 * {@link #isReadOnly()} derives {@code readOnly} from.
	 */
	public Operation(final String pName, final Kind pKind, final String pDescription,
		final String[] pRequiredInputs, final String[] pOptionalInputs, final String pHttpMethod)
	{
		this.name = pName;
		this.kind = pKind;
		this.description = pDescription;
		this.requiredInputs = pRequiredInputs == null ? Collections.<String> emptyList()
				: Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(pRequiredInputs)));
		this.optionalInputs = pOptionalInputs == null ? Collections.<String> emptyList()
				: Collections.unmodifiableList(new ArrayList<String>(Arrays.asList(pOptionalInputs)));
		this.httpMethod = pHttpMethod == null || pHttpMethod.trim().isEmpty() ? null : pHttpMethod.trim().toUpperCase(java.util.Locale.ENGLISH);
	}

	public static Operation read(final String pName, final String pDescription, final String... pRequired)
	{
		return new Operation(pName, Kind.READ, pDescription, pRequired, null);
	}

	public static Operation search(final String pName, final String pDescription, final String... pRequired)
	{
		return new Operation(pName, Kind.SEARCH, pDescription, pRequired, null);
	}

	public static Operation write(final String pName, final String pDescription, final String... pRequired)
	{
		return new Operation(pName, Kind.WRITE, pDescription, pRequired, null);
	}

	public String getName()
	{
		return this.name;
	}

	public Kind getKind()
	{
		return this.kind;
	}

	public String getDescription()
	{
		return this.description;
	}

	public List<String> getRequiredInputs()
	{
		return this.requiredInputs;
	}

	public List<String> getOptionalInputs()
	{
		return this.optionalInputs;
	}

	public String getHttpMethod()
	{
		return this.httpMethod;
	}

	/**
	 * True only when this operation cannot change anything at the source: an HTTP GET/HEAD REST operation, a SQL
	 * SELECT query operation, a file read, a catalog/profile/sample/describe operation. Read recipe + deduced scan
	 * contract, section 2 (2026-09-24) — the ONE place {@code readOnly} is derived, from what the operation
	 * definition already declares: {@link #httpMethod} when the connector tracks one, else {@link #kind} alone.
	 * A {@link Kind#WRITE} operation is never read-only, whatever its HTTP method. An operation transported over
	 * HTTP with a known, non-safe method (POST/PUT/PATCH/DELETE...) is never read-only either, even when declared
	 * {@link Kind#SEARCH} (a search-style POST is not provably non-mutating).
	 */
	public boolean isReadOnly()
	{
		if (this.kind == Kind.WRITE)
		{
			return false;
		}
		if (this.httpMethod != null)
		{
			return "GET".equals(this.httpMethod) || "HEAD".equals(this.httpMethod);
		}
		return true;
	}

	@Override
	public String toString()
	{
		return this.name + " (" + this.kind + ")";
	}
}
