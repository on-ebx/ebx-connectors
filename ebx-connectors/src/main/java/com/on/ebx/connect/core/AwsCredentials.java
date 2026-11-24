package com.on.ebx.connect.core;

/** Immutable public view of the hardened AWS credential chain used by connector transports. */
public final class AwsCredentials
{
	private final String accessKeyId;
	private final String secretAccessKey;
	private final String sessionToken;
	private final String source;

	private AwsCredentials(final SecretResolvers.AwsSecretsManagerResolver.Credentials pCredentials)
	{
		this.accessKeyId = pCredentials.accessKey;
		this.secretAccessKey = pCredentials.secretKey;
		this.sessionToken = pCredentials.sessionToken;
		this.source = pCredentials.source;
	}

	/** Uses the existing properties/environment, shared-profile, ECS, then IMDSv2 precedence. */
	public static AwsCredentials load() throws Exception
	{
		return new AwsCredentials(SecretResolvers.AwsSecretsManagerResolver.Credentials.load());
	}

	public String getAccessKeyId() { return this.accessKeyId; }
	public String getSecretAccessKey() { return this.secretAccessKey; }
	public String getSessionToken() { return this.sessionToken; }
	public String getSource() { return this.source; }
}
