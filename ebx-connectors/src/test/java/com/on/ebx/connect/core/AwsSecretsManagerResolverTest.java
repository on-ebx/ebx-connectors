package com.on.ebx.connect.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import com.on.ebx.connect.test.MockSaasHttpServer;

/** Offline contract for AWS Secrets Manager signing, selection, decoding, caching, and redaction. */
public final class AwsSecretsManagerResolverTest
{
	private AwsSecretsManagerResolverTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		parseReferences();
		sharedCredentialsProfiles();
		final MockSaasHttpServer server = new MockSaasHttpServer();
		final String endpoint = "ebx.connectors.aws.secrets.endpoint";
		final String access = "ebx.connectors.aws.accessKeyId";
		final String secret = "ebx.connectors.aws.secretAccessKey";
		final String session = "ebx.connectors.aws.sessionToken";
		final String containerRelative = "ebx.connectors.aws.containerCredentialsRelativeUri";
		final String containerEndpoint = "ebx.connectors.aws.containerMetadataEndpoint";
		final String containerToken = "ebx.connectors.aws.containerAuthorizationToken";
		final String imdsEndpoint = "ebx.connectors.aws.imdsEndpoint";
		final String allowed = Egress.PROPERTY;
		final String oldEndpoint = System.getProperty(endpoint), oldAccess = System.getProperty(access);
		final String oldSecret = System.getProperty(secret), oldSession = System.getProperty(session);
		final String oldContainerRelative = System.getProperty(containerRelative);
		final String oldContainerEndpoint = System.getProperty(containerEndpoint);
		final String oldContainerToken = System.getProperty(containerToken);
		final String oldImdsEndpoint = System.getProperty(imdsEndpoint);
		final String oldAllowed = System.getProperty(allowed);
		try
		{
			System.setProperty(endpoint, server.baseUrl());
			System.setProperty(access, "AKIA-CONTRACT-KEY");
			System.setProperty(secret, "contract-signing-secret");
			System.setProperty(session, "contract-session-token");
			System.setProperty(allowed, "127.0.0.1:" + server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1));
			server.enqueue("/", new MockSaasHttpServer.Response(200,
				"{\"SecretString\":\"{\\\"database\\\":{\\\"password\\\":\\\"resolved-aws-password\\\"}}\"}"));
			server.enqueue("/", new MockSaasHttpServer.Response(200, "{\"SecretBinary\":\""
				+ Base64.getEncoder().encodeToString("binary-secret-value".getBytes(StandardCharsets.UTF_8)) + "\"}"));
			server.enqueue("/v2/credentials/task", new MockSaasHttpServer.Response(200,
				"{\"AccessKeyId\":\"ECS-ACCESS\",\"SecretAccessKey\":\"ECS-SECRET\",\"Token\":\"ECS-TOKEN\"}"));
			server.enqueue("/latest/api/token", new MockSaasHttpServer.Response(200, "imdsv2-contract-token"));
			server.enqueue("/latest/meta-data/iam/security-credentials/", new MockSaasHttpServer.Response(200, "governed-role"));
			server.enqueue("/latest/meta-data/iam/security-credentials/governed-role", new MockSaasHttpServer.Response(200,
				"{\"AccessKeyId\":\"IMDS-ACCESS\",\"SecretAccessKey\":\"IMDS-SECRET\",\"Token\":\"IMDS-TOKEN\"}"));
			server.enqueue("/latest/api/token", new MockSaasHttpServer.Response(401, "IMDSv2 required"));

			SecretResolvers.get().invalidate();
			final String password = SecretResolvers.get().resolve("awssecrets",
				"us-east-1/app/database?versionStage=AWSCURRENT#database.password");
			check("resolved-aws-password".equals(password), "JSON-field selection failed");
			check(password.equals(SecretResolvers.get().resolve("awssecrets",
				"us-east-1/app/database?versionStage=AWSCURRENT#database.password")), "cached value changed");
			check(server.calls("/") == 1, "secret-value cache did not prevent a second provider call");
			final MockSaasHttpServer.Request request = server.requests().get(0);
			check("POST".equals(request.method), "GetSecretValue must use POST");
			check("secretsmanager.GetSecretValue".equals(request.header("X-Amz-Target")), "missing AWS target header");
			check(request.header("Authorization") != null && request.header("Authorization").startsWith("AWS4-HMAC-SHA256 "),
				"request was not signed with SigV4");
			check("contract-session-token".equals(request.header("x-amz-security-token")), "session token was not signed/sent");
			check(request.body.contains("\"SecretId\":\"app/database\"")
				&& request.body.contains("\"VersionStage\":\"AWSCURRENT\""), "GetSecretValue body is incomplete");
			check(!Redaction.redact("failure " + password).contains(password), "resolved AWS value was not redacted");

			SecretResolvers.get().invalidate();
			check("binary-secret-value".equals(SecretResolvers.get().resolve("awssecrets", "eu-west-3/binary-secret")),
				"SecretBinary was not decoded");
			check(server.calls("/") == 2, "cache invalidation did not call the provider again");

			System.setProperty(containerRelative, "/v2/credentials/task");
			System.setProperty(containerEndpoint, server.baseUrl());
			System.setProperty(containerToken, "container-authorization-token");
			final SecretResolvers.AwsSecretsManagerResolver.Credentials ecs =
				SecretResolvers.AwsSecretsManagerResolver.Credentials.fromContainer();
			check("ECS-ACCESS".equals(ecs.accessKey) && "ECS-SECRET".equals(ecs.secretKey)
				&& "ECS-TOKEN".equals(ecs.sessionToken), "ECS credentials were parsed incorrectly");
			final MockSaasHttpServer.Request ecsRequest = find(server, "/v2/credentials/task");
			check("container-authorization-token".equals(ecsRequest.header("Authorization")),
				"ECS authorization token was not sent");
			System.clearProperty(containerRelative);
			System.clearProperty(containerToken);

			System.setProperty(imdsEndpoint, server.baseUrl());
			final SecretResolvers.AwsSecretsManagerResolver.Credentials imds =
				SecretResolvers.AwsSecretsManagerResolver.Credentials.fromImdsV2();
			check("IMDS-ACCESS".equals(imds.accessKey) && "IMDS-TOKEN".equals(imds.sessionToken),
				"IMDSv2 credentials were parsed incorrectly");
			final MockSaasHttpServer.Request tokenRequest = find(server, "/latest/api/token");
			check("PUT".equals(tokenRequest.method)
				&& "21600".equals(tokenRequest.header("X-aws-ec2-metadata-token-ttl-seconds")),
				"IMDSv2 token request is invalid");
			final MockSaasHttpServer.Request roleRequest = find(server, "/latest/meta-data/iam/security-credentials/");
			check("imdsv2-contract-token".equals(roleRequest.header("X-aws-ec2-metadata-token")),
				"IMDSv2 token was not required for metadata");
			check(SecretResolvers.AwsSecretsManagerResolver.Credentials.fromImdsV2() == null,
				"IMDS token refusal incorrectly fell back to IMDSv1");
			check(server.calls("/latest/meta-data/iam/security-credentials/") == 1,
				"IMDSv1 metadata call occurred after token refusal");

			try
			{
				System.setProperty("ebx.connectors.aws.containerCredentialsFullUri", "http://10.0.0.5/credentials");
				SecretResolvers.AwsSecretsManagerResolver.Credentials.fromContainer();
				throw new AssertionError("unsafe HTTP container credential URI was accepted");
			}
			catch (final IllegalStateException expected)
			{
				check(expected.getMessage().contains("HTTPS"), "unsafe container URI error is unclear");
			}
			finally { System.clearProperty("ebx.connectors.aws.containerCredentialsFullUri"); }
			System.out.println("PASS: AWS Secrets Manager signed retrieval, selectors, binary values, cache, and redaction");
		}
		finally
		{
			server.close();
			restore(endpoint, oldEndpoint); restore(access, oldAccess); restore(secret, oldSecret);
			restore(session, oldSession); restore(allowed, oldAllowed);
			restore(containerRelative, oldContainerRelative); restore(containerEndpoint, oldContainerEndpoint);
			restore(containerToken, oldContainerToken); restore(imdsEndpoint, oldImdsEndpoint);
			SecretResolvers.get().invalidate();
		}
	}

	private static MockSaasHttpServer.Request find(final MockSaasHttpServer pServer, final String pPath)
	{
		for (final MockSaasHttpServer.Request request : pServer.requests())
			if (pPath.equals(request.path)) return request;
		throw new AssertionError("No fixture request for " + pPath);
	}

	private static void parseReferences()
	{
		final SecretResolvers.AwsSecretsManagerResolver.Reference reference =
			SecretResolvers.AwsSecretsManagerResolver.Reference.parse("ap-southeast-2/team/secret?versionId=v-1#token");
		check("ap-southeast-2".equals(reference.region) && "team/secret".equals(reference.secretId)
			&& "v-1".equals(reference.versionId) && "token".equals(reference.field), "reference parsing failed");
		try
		{
			SecretResolvers.AwsSecretsManagerResolver.Reference.parse("us-east-1/name?versionId=1&versionStage=AWSCURRENT");
			throw new AssertionError("conflicting version selectors were accepted");
		}
		catch (final IllegalStateException expected)
		{
			check(expected.getMessage().contains("either"), "selector error is unclear");
		}
	}

	private static void sharedCredentialsProfiles() throws Exception
	{
		final Path file = Files.createTempFile("ebx-aws-credentials-", ".ini");
		try
		{
			Files.write(file, ("[default]\naws_access_key_id=DEFAULT-ACCESS\n"
				+ "aws_secret_access_key=DEFAULT-SECRET\n\n[governed]\n"
				+ "aws_access_key_id = PROFILE-ACCESS\naws_secret_access_key = PROFILE-SECRET\n"
				+ "aws_session_token = PROFILE-TOKEN\n").getBytes(StandardCharsets.UTF_8));
			final SecretResolvers.AwsSecretsManagerResolver.Credentials credentials =
				SecretResolvers.AwsSecretsManagerResolver.Credentials.fromProfile(file.toFile(), "governed");
			check("PROFILE-ACCESS".equals(credentials.accessKey)
				&& "PROFILE-SECRET".equals(credentials.secretKey)
				&& "PROFILE-TOKEN".equals(credentials.sessionToken), "named shared profile was parsed incorrectly");
			check(SecretResolvers.AwsSecretsManagerResolver.Credentials.fromProfile(file.toFile(), "missing") == null,
				"missing profile unexpectedly resolved");
		}
		finally { Files.deleteIfExists(file); }
	}

	private static void restore(final String pName, final String pValue)
	{
		if (pValue == null) System.clearProperty(pName); else System.setProperty(pName, pValue);
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
