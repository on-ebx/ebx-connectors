package com.on.ebx.connect.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** SHA-256 fingerprints of the outbound documents. */
public final class Hashing
{
	private Hashing()
	{
	}

	public static String sha256(final String pText)
	{
		try
		{
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest((pText == null ? "" : pText).getBytes(StandardCharsets.UTF_8));
			final StringBuilder hex = new StringBuilder();
			for (final byte b : digest)
			{
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (final Exception ex)
		{
			return Integer.toHexString((pText == null ? "" : pText).hashCode());
		}
	}
}
