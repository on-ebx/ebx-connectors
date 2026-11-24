package com.on.ebx.connect.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.connect.test.MockSaasHttpServer;

/** Offline contract for service-account and metadata-backed Google Secret Manager access. */
public final class GoogleSecretManagerResolverTest
{
	private GoogleSecretManagerResolverTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		referencesAndCrc();
		externalAccountFlow();
		final MockSaasHttpServer server = new MockSaasHttpServer();
		final Path credentials = Files.createTempFile("ebx-gcp-service-account-", ".json");
		final String allowed = Egress.PROPERTY;
		final String credentialsProperty = "ebx.connectors.gcp.credentialsFile";
		final String tokenEndpoint = "ebx.connectors.gcp.tokenEndpoint";
		final String metadataEndpoint = "ebx.connectors.gcp.metadataEndpoint";
		final String secretEndpoint = "ebx.connectors.gcp.secretManagerEndpoint";
		final String oldAllowed = System.getProperty(allowed), oldCredentials = System.getProperty(credentialsProperty);
		final String oldToken = System.getProperty(tokenEndpoint), oldMetadata = System.getProperty(metadataEndpoint);
		final String oldSecret = System.getProperty(secretEndpoint);
		try
		{
			final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			final KeyPair pair = generator.generateKeyPair();
			final String pem = "-----BEGIN PRIVATE KEY-----\\n"
				+ Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
				+ "\\n-----END PRIVATE KEY-----\\n";
			final String json = "{\"type\":\"service_account\",\"client_email\":\"fixture@example.iam.gserviceaccount.com\","
				+ "\"private_key_id\":\"fixture-key\",\"private_key\":\"" + pem + "\","
				+ "\"token_uri\":\"https://oauth2.googleapis.com/token\"}";
			Files.write(credentials, json.getBytes(StandardCharsets.UTF_8));
			System.setProperty(allowed, "127.0.0.1:" + server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1));
			System.setProperty(credentialsProperty, credentials.toString());
			System.setProperty(tokenEndpoint, server.baseUrl() + "/token");
			System.setProperty(secretEndpoint, server.baseUrl() + "/v1");
			server.enqueue("/token", new MockSaasHttpServer.Response(200,
				"{\"access_token\":\"service-account-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}"));
			server.enqueue("/v1/projects/demo/secrets/database/versions/latest:access",
				secretResponse("service-account-secret", true));

			final SecretResolvers.GoogleSecretManagerResolver serviceAccount =
				new SecretResolvers.GoogleSecretManagerResolver();
			check("service-account-secret".equals(serviceAccount.resolve("projects/demo/secrets/database")),
				"service-account secret retrieval failed");
			final MockSaasHttpServer.Request oauth = find(server, "/token", 0);
			check("POST".equals(oauth.method) && oauth.body.contains("jwt-bearer") && oauth.body.contains("assertion="),
				"service-account JWT exchange is invalid");
			final MockSaasHttpServer.Request secret = find(server,
				"/v1/projects/demo/secrets/database/versions/latest:access", 0);
			check("Bearer service-account-token".equals(secret.header("Authorization")),
				"Secret Manager request did not use the service-account token");

			System.clearProperty(credentialsProperty);
			System.clearProperty(tokenEndpoint);
			System.setProperty(metadataEndpoint, server.baseUrl() + "/metadata/token");
			final Map<String, String> metadataHeaders = new LinkedHashMap<String, String>();
			metadataHeaders.put("Metadata-Flavor", "Google");
			server.enqueue("/metadata/token", new MockSaasHttpServer.Response(200,
				"{\"access_token\":\"metadata-token\",\"expires_in\":300,\"token_type\":\"Bearer\"}", metadataHeaders));
			server.enqueue("/v1/projects/demo/secrets/metadata/versions/7:access",
				secretResponse("metadata-secret", false));
			final SecretResolvers.GoogleSecretManagerResolver metadata =
				new SecretResolvers.GoogleSecretManagerResolver();
			check("metadata-secret".equals(metadata.resolve("projects/demo/secrets/metadata/versions/7")),
				"metadata identity secret retrieval failed");
			check("Google".equals(find(server, "/metadata/token", 0).header("Metadata-Flavor")),
				"metadata request omitted Metadata-Flavor");
			check("Bearer metadata-token".equals(find(server,
				"/v1/projects/demo/secrets/metadata/versions/7:access", 0).header("Authorization")),
				"Secret Manager request did not use the metadata token");

			System.out.println("PASS: Google Secret Manager service-account and metadata identity flows, CRC32C, bounds, and endpoint safety");
		}
		finally
		{
			server.close(); Files.deleteIfExists(credentials);
			restore(allowed, oldAllowed); restore(credentialsProperty, oldCredentials);
			restore(tokenEndpoint, oldToken); restore(metadataEndpoint, oldMetadata); restore(secretEndpoint, oldSecret);
		}
	}

	private static void externalAccountFlow() throws Exception
	{
		final MockSaasHttpServer server = new MockSaasHttpServer();
		final Path subject = Files.createTempFile("ebx-gcp-subject-", ".json");
		final Path credentials = Files.createTempFile("ebx-gcp-external-", ".json");
		final String allowed = Egress.PROPERTY;
		final String credentialsProperty = "ebx.connectors.gcp.credentialsFile";
		final String stsEndpoint = "ebx.connectors.gcp.stsEndpoint";
		final String impersonationEndpoint = "ebx.connectors.gcp.impersonationEndpoint";
		final String secretEndpoint = "ebx.connectors.gcp.secretManagerEndpoint";
		final String oldAllowed = System.getProperty(allowed), oldCredentials = System.getProperty(credentialsProperty);
		final String oldSts = System.getProperty(stsEndpoint), oldImpersonation = System.getProperty(impersonationEndpoint);
		final String oldSecret = System.getProperty(secretEndpoint);
		try
		{
			Files.write(subject, "{\"token\":\"fixture-oidc-subject\"}".getBytes(StandardCharsets.UTF_8));
			final String json = "{\"type\":\"external_account\","
				+ "\"audience\":\"//iam.googleapis.com/projects/123/locations/global/workloadIdentityPools/pool/providers/provider\","
				+ "\"subject_token_type\":\"urn:ietf:params:oauth:token-type:jwt\","
				+ "\"token_url\":\"https://sts.googleapis.com/v1/token\","
				+ "\"service_account_impersonation_url\":\"https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/fixture@example.iam.gserviceaccount.com:generateAccessToken\","
				+ "\"credential_source\":{\"file\":\"" + jsonEscape(subject.toString())
				+ "\",\"format\":{\"type\":\"json\",\"subject_token_field_name\":\"token\"}}}";
			Files.write(credentials, json.getBytes(StandardCharsets.UTF_8));
			System.setProperty(allowed, "127.0.0.1:" + server.baseUrl().substring(server.baseUrl().lastIndexOf(':') + 1));
			System.setProperty(credentialsProperty, credentials.toString());
			System.setProperty(stsEndpoint, server.baseUrl() + "/sts");
			System.setProperty(impersonationEndpoint, server.baseUrl() + "/impersonate");
			System.setProperty(secretEndpoint, server.baseUrl() + "/v1");
			server.enqueue("/sts", new MockSaasHttpServer.Response(200,
				"{\"access_token\":\"federated-token\",\"token_type\":\"Bearer\",\"expires_in\":900}"));
			server.enqueue("/impersonate", new MockSaasHttpServer.Response(200,
				"{\"accessToken\":\"impersonated-token\",\"expireTime\":\"2099-01-01T00:00:00Z\"}"));
			server.enqueue("/v1/projects/demo/secrets/federated/versions/latest:access",
				secretResponse("federated-secret", true));
			final SecretResolvers.GoogleSecretManagerResolver resolver = new SecretResolvers.GoogleSecretManagerResolver();
			check("federated-secret".equals(resolver.resolve("projects/demo/secrets/federated")),
				"external_account secret retrieval failed");
			final MockSaasHttpServer.Request sts = find(server, "/sts", 0);
			check(sts.body.contains("subject_token=fixture-oidc-subject") && sts.body.contains("token-exchange"),
				"RFC 8693 exchange did not contain the expected subject token and grant");
			check("Bearer federated-token".equals(find(server, "/impersonate", 0).header("Authorization")),
				"impersonation did not use the federated token");
			check("Bearer impersonated-token".equals(find(server,
				"/v1/projects/demo/secrets/federated/versions/latest:access", 0).header("Authorization")),
				"Secret Manager did not use the impersonated token");

			final String rejected = json.replace("\"file\":\"" + jsonEscape(subject.toString()) + "\"",
				"\"url\":\"https://attacker.invalid/token\"");
			Files.write(credentials, rejected.getBytes(StandardCharsets.UTF_8));
			try
			{
				new SecretResolvers.GoogleSecretManagerResolver().accessToken();
				throw new AssertionError("URL credential_source was accepted");
			}
			catch (final IllegalStateException expected)
			{
				check(expected.getMessage().contains("local-file"), "credential_source rejection was unclear");
			}
		}
		finally
		{
			server.close(); Files.deleteIfExists(subject); Files.deleteIfExists(credentials);
			restore(allowed, oldAllowed); restore(credentialsProperty, oldCredentials); restore(stsEndpoint, oldSts);
			restore(impersonationEndpoint, oldImpersonation); restore(secretEndpoint, oldSecret);
		}
	}

	private static String jsonEscape(final String pValue)
	{
		return pValue.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static void referencesAndCrc()
	{
		check("projects/p/secrets/s/versions/latest".equals(
			SecretResolvers.GoogleSecretManagerResolver.normalizeResource("projects/p/secrets/s")),
			"latest version was not defaulted");
		check(SecretResolvers.GoogleSecretManagerResolver.crc32c("123456789".getBytes(StandardCharsets.UTF_8))
			== 0xE3069283L, "CRC32C implementation does not match the Castagnoli check value");
		try
		{
			SecretResolvers.GoogleSecretManagerResolver.normalizeResource("https://example.test/secret");
			throw new AssertionError("full URL secret reference was accepted");
		}
		catch (final IllegalStateException expected) { check(expected.getMessage().contains("projects"), "reference error is unclear"); }
	}

	private static MockSaasHttpServer.Response secretResponse(final String pValue, final boolean pCrc)
	{
		final byte[] bytes = pValue.getBytes(StandardCharsets.UTF_8);
		return new MockSaasHttpServer.Response(200, "{\"payload\":{\"data\":\""
			+ Base64.getEncoder().encodeToString(bytes) + "\""
			+ (pCrc ? ",\"dataCrc32c\":\"" + SecretResolvers.GoogleSecretManagerResolver.crc32c(bytes) + "\"" : "")
			+ "}}" );
	}

	private static MockSaasHttpServer.Request find(final MockSaasHttpServer pServer, final String pPath, final int pOccurrence)
	{
		int found = 0;
		for (final MockSaasHttpServer.Request request : pServer.requests())
			if (pPath.equals(request.path) && found++ == pOccurrence) return request;
		throw new AssertionError("No fixture request for " + pPath);
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
