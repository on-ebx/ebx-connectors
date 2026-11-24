package com.on.ebx.connect.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Opaque metadata-cache key which never retains a URL, tenant name, query, or credential. */
public final class MetadataCacheKey
{
	private final String connectorType;
	private final String operation;
	private final String scopeDigest;

	private MetadataCacheKey(final String pConnectorType, final String pOperation, final String pScopeDigest)
	{
		this.connectorType = pConnectorType;
		this.operation = pOperation;
		this.scopeDigest = pScopeDigest;
	}

	/**
	 * Builds a safe key. Scope may contain endpoint/account context but is immediately reduced to
	 * a SHA-256 digest; it is never available from this object or exposed by {@link #toString()}.
	 */
	public static MetadataCacheKey of(final String pConnectorType, final String pOperation,
		final String pScope)
	{
		return new MetadataCacheKey(token(pConnectorType, "connector type"),
			token(pOperation, "operation"), digest(pScope == null ? "" : pScope));
	}

	private static String token(final String pValue, final String pLabel)
	{
		if (pValue == null || pValue.trim().isEmpty()) throw new IllegalArgumentException(pLabel + " must not be empty");
		final String value = pValue.trim().toLowerCase(Locale.ROOT);
		if (!value.matches("[a-z0-9][a-z0-9._-]{0,79}"))
			throw new IllegalArgumentException(pLabel + " contains unsafe characters");
		return value;
	}

	private static String digest(final String pValue)
	{
		try
		{
			final byte[] hash = MessageDigest.getInstance("SHA-256").digest(pValue.getBytes(StandardCharsets.UTF_8));
			final StringBuilder result = new StringBuilder(hash.length * 2);
			for (final byte b : hash) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
			return result.toString();
		}
		catch (final NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	@Override public int hashCode()
	{
		int result = this.connectorType.hashCode();
		result = 31 * result + this.operation.hashCode();
		return 31 * result + this.scopeDigest.hashCode();
	}

	@Override public boolean equals(final Object pOther)
	{
		if (this == pOther) return true;
		if (!(pOther instanceof MetadataCacheKey)) return false;
		final MetadataCacheKey other = (MetadataCacheKey) pOther;
		return this.connectorType.equals(other.connectorType) && this.operation.equals(other.operation)
			&& this.scopeDigest.equals(other.scopeDigest);
	}

	@Override public String toString()
	{
		return this.connectorType + "/" + this.operation + "/" + this.scopeDigest.substring(0, 12);
	}
}
