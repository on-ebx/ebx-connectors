package com.on.ebx.connect.core;

import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Google Cloud access token for a connector: property {@code serviceAccountJson} (the key file
 * content) or {@code serviceAccountFile} (a file inside the directory of {@code -Debx.connectors.secrets.dir}, audit C2-08), else a ready-made token
 * in {@code apiKey} with authentication BEARER. Tokens are cached for 50 minutes.
 */
public final class GoogleAuth
{
	private String token;
	private long expiry;

	public synchronized String token(final ConnectorConfig pConfig, final String pScope) throws ConnectorException
	{
		if (this.token != null && System.currentTimeMillis() < this.expiry)
		{
			return this.token;
		}
		if (pConfig.getAuthType() == ConnectorConfig.AuthType.BEARER && !pConfig.isPlaceholder(pConfig.getApiKey()))
		{
			return pConfig.getApiKey();
		}
		String json = pConfig.getProperty("serviceAccountJson", null);
		final String file = pConfig.getProperty("serviceAccountFile", null);
		try
		{
			if (json == null && file != null)
			{
				// audit C2-08: the same confinement as a file: secret reference (the directory of -Debx.connectors.secrets.dir), not a free path of the server
				try
				{
					json = new SecretResolvers.FileResolver().resolve(file);
				}
				catch (final IllegalStateException refused)
				{
					throw new ConnectorException("Google: serviceAccountFile is refused: " + refused.getMessage()
						+ ". Put the key file in the secrets directory and name it relative to it, or use serviceAccountJson / a secret reference.");
				}
			}
			if (json == null)
			{
				throw new ConnectorException("Google: set the property serviceAccountJson or serviceAccountFile (service account key), or a bearer token in apiKey");
			}
			this.token = Jwt.googleAccessToken(json, pScope, pConfig.getReadTimeoutMs());
			this.expiry = System.currentTimeMillis() + 50L * 60L * 1000L;
			return this.token;
		}
		catch (final ConnectorException ex)
		{
			throw ex;
		}
		catch (final Exception ex)
		{
			throw new ConnectorException("Google authentication failed: " + ex.getMessage(), ex);
		}
	}
}
