package com.on.ebx.connect.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.spi.SecretResolver;

/**
 * Registry of {@link SecretResolver}s: the built-in schemes ({@code env}, {@code file},
 * {@code vault}, {@code azurekv}, {@code awssecrets}, {@code gcpsecret}) plus the ones declared through {@code ServiceLoader}. Resolved
 * values are cached ({@code ebx.connectors.secrets.cacheSeconds}, default 300) so that a secret
 * rotated in the vault is picked up without restart, and the vault is not called on every request.
 */
public final class SecretResolvers
{
	private static final SecretResolvers INSTANCE = new SecretResolvers();

	private final Map<String, SecretResolver> resolvers = new LinkedHashMap<String, SecretResolver>();
	private final ConcurrentHashMap<String, String[]> cache = new ConcurrentHashMap<String, String[]>();

	private SecretResolvers()
	{
		this.register(new EnvResolver());
		this.register(new FileResolver());
		this.register(new HashicorpVaultResolver());
		this.register(new AzureKeyVaultResolver());
		this.register(new AwsSecretsManagerResolver());
		this.register(new GoogleSecretManagerResolver());
		try
		{
			for (final SecretResolver resolver : ServiceLoader.load(SecretResolver.class, SecretResolvers.class.getClassLoader()))
			{
				this.register(resolver);
			}
		}
		catch (final Throwable ex)
		{
			// a broken service file must not prevent the built-ins from working
		}
	}

	public static SecretResolvers get()
	{
		return SecretResolvers.INSTANCE;
	}

	public synchronized void register(final SecretResolver pResolver)
	{
		this.resolvers.put(pResolver.getScheme().toLowerCase(), pResolver);
	}

	public synchronized boolean handles(final String pScheme)
	{
		return this.resolvers.containsKey(pScheme.toLowerCase());
	}

	public synchronized java.util.Set<String> schemes()
	{
		return new java.util.TreeSet<String>(this.resolvers.keySet());
	}

	/** The secret for a scheme and reference, from the cache when fresh; IllegalStateException with the reason otherwise. */
	public String resolve(final String pScheme, final String pReference)
	{
		final String key = pScheme + ":" + pReference;
		final String[] cached = this.cache.get(key);
		if (cached != null && Long.parseLong(cached[1]) > System.currentTimeMillis())
		{
			return cached[0];
		}
		final SecretResolver resolver;
		synchronized (this)
		{
			resolver = this.resolvers.get(pScheme.toLowerCase());
		}
		if (resolver == null)
		{
			throw new IllegalStateException("no secret resolver for scheme '" + pScheme + "' (known: " + this.schemes() + ")");
		}
		try
		{
			final String value = resolver.resolve(pReference);
			if (value == null || value.isEmpty())
			{
				throw new IllegalStateException("secret reference '" + key + "' resolved to an empty value");
			}
			Redaction.rememberSecret(value);
			final long ttl = Long.parseLong(System.getProperty("ebx.connectors.secrets.cacheSeconds", "300")) * 1000L;
			this.cache.put(key, new String[] { value, String.valueOf(System.currentTimeMillis() + ttl) });
			return value;
		}
		catch (final IllegalStateException ex)
		{
			throw ex;
		}
		catch (final Exception ex)
		{
			throw new IllegalStateException("secret reference '" + pScheme + ":" + pReference + "' cannot be resolved: "
					+ (ex.getMessage() == null ? ex.toString() : ex.getMessage()), ex);
		}
	}

	public void invalidate()
	{
		this.cache.clear();
	}

	static String setting(final String pProperty, final String pEnv)
	{
		final String property = System.getProperty(pProperty);
		if (property != null && !property.trim().isEmpty())
		{
			return property.trim();
		}
		final String env = pEnv == null ? null : System.getenv(pEnv);
		return env == null || env.trim().isEmpty() ? null : env.trim();
	}

	static String readFile(final File pFile) throws Exception
	{
		final InputStream in = new FileInputStream(pFile);
		try
		{
			final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			final byte[] buffer = new byte[4096];
			for (int n = in.read(buffer); n >= 0; n = in.read(buffer))
			{
				out.write(buffer, 0, n);
			}
			return new String(out.toByteArray(), "UTF-8").trim();
		}
		finally
		{
			in.close();
		}
	}

	// ------------------------------------------------------------------ built-ins

	/**
	 * Why a stored value is a {@code file:} or {@code env:} reference this instance refuses, null when it is not one or is
	 * accepted. Used by the status route: the refusal itself happens at resolution. Never reads the file or the variable.
	 */
	public static String referenceRefusal(final String pStored)
	{
		if (pStored == null)
		{
			return null;
		}
		final String stored = pStored.trim();
		if (stored.regionMatches(true, 0, "file:", 0, 5))
		{
			final String directory = System.getProperty(SecretResolvers.SECRETS_DIR_PROPERTY);
			return directory == null || directory.trim().isEmpty() ? "file: reference refused, " + SecretResolvers.SECRETS_DIR_PROPERTY + " is not set" : null;
		}
		if (stored.regionMatches(true, 0, "env:", 0, 4))
		{
			final String allowed = System.getProperty(SecretResolvers.ENV_ALLOWED_PROPERTY);
			if (allowed == null || allowed.trim().isEmpty())
			{
				return "env: reference refused, " + SecretResolvers.ENV_ALLOWED_PROPERTY + " is not set";
			}
			return EnvResolver.listed(stored.substring(4).trim(), allowed) ? null : "env: reference refused, the variable is not in " + SecretResolvers.ENV_ALLOWED_PROPERTY;
		}
		return null;
	}

	/** The environment variables, and the only system properties, that {@code env:} may read: names, or {@code PREFIX_*}. */
	public static final String ENV_ALLOWED_PROPERTY = "ebx.connectors.secrets.envAllowed";
	/** The directory that holds every file a {@code file:} reference may read. */
	public static final String SECRETS_DIR_PROPERTY = "ebx.connectors.secrets.dir";
	private static final long MAX_SECRET_FILE_BYTES = 1024L * 1024L;

	/**
	 * {@code env:NAME}: an environment variable. Whoever may edit a connector chooses the name and
	 * the host the value is sent to, so the reach of the scheme is set at JVM level:
	 * {@link #ENV_ALLOWED_PROPERTY} lists what may be read. System properties are read only when
	 * that list names them: they hold the JVM's own secrets (the Vault token of this module,
	 * keystore passwords), which no connector has a reason to read.
	 */
	static final class EnvResolver implements SecretResolver
	{
		@Override
		public String getScheme()
		{
			return "env";
		}

		@Override
		public String resolve(final String pReference) throws Exception
		{
			final String name = pReference.trim();
			final String allowed = System.getProperty(SecretResolvers.ENV_ALLOWED_PROPERTY);
			if (allowed == null || allowed.trim().isEmpty())
			{
				// refused by default (C2-08): an unconfined env: reference would read any variable of the EBX process
				throw new IllegalStateException("env: secret references are refused: set -D" + SecretResolvers.ENV_ALLOWED_PROPERTY
						+ "=NAME1,PREFIX_* to the variables the connectors may read (a restart is needed)");
			}
			if (!EnvResolver.listed(name, allowed))
			{
				throw new IllegalStateException("'" + name + "' is not in " + SecretResolvers.ENV_ALLOWED_PROPERTY);
			}
			final String value = SecretResolvers.setting(name, name);
			if (value == null)
			{
				throw new IllegalStateException("environment variable or system property '" + name + "' is not set");
			}
			return value;
		}

		private static boolean listed(final String pName, final String pAllowed)
		{
			for (final String part : pAllowed.split(","))
			{
				final String pattern = part.trim();
				if (pattern.isEmpty())
				{
					continue;
				}
				// a lone * would hand every system property back: a prefix is required before it
				if (pattern.endsWith("*") ? pattern.length() > 1 && pName.startsWith(pattern.substring(0, pattern.length() - 1)) : pattern.equals(pName))
				{
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * {@code file:/run/secrets/sirene}: the trimmed content of a file (Docker and Kubernetes
	 * secrets). With {@link #SECRETS_DIR_PROPERTY} set, only a file inside that directory is read,
	 * links resolved. Without it the scheme is refused: it would read whatever the EBX process can,
	 * {@code ebx.properties} included, on behalf of whoever may edit a connector.
	 */
	static final class FileResolver implements SecretResolver
	{
		@Override
		public String getScheme()
		{
			return "file";
		}

		@Override
		public String resolve(final String pReference) throws Exception
		{
			final String directory = System.getProperty(SecretResolvers.SECRETS_DIR_PROPERTY);
			File file = new File(pReference.trim());
			if (directory == null || directory.trim().isEmpty())
			{
				// refused by default (C2-08): an unconfined file: reference would read any file the EBX process can read
				throw new IllegalStateException("file: secret references are refused: set -D" + SecretResolvers.SECRETS_DIR_PROPERTY
						+ "=<directory> to the directory that holds the secret files (a restart is needed)");
			}
			else
			{
				final java.nio.file.Path root;
				try
				{
					root = java.nio.file.Paths.get(directory.trim()).toRealPath();
				}
				catch (final java.io.IOException ex)
				{
					throw new IllegalStateException("the secrets directory set by " + SecretResolvers.SECRETS_DIR_PROPERTY + " does not exist or cannot be read");
				}
				// a relative reference names a file of the secrets directory
				final java.nio.file.Path candidate = file.isAbsolute() ? file.toPath() : root.resolve(pReference.trim());
				final java.nio.file.Path real;
				try
				{
					real = candidate.toRealPath();
				}
				catch (final java.io.IOException ex)
				{
					// same answer whether the file is absent or elsewhere: the message must not tell which files exist outside the directory
					throw new IllegalStateException("file reference refused: no such file in the secrets directory (" + SecretResolvers.SECRETS_DIR_PROPERTY + ")");
				}
				if (!real.startsWith(root) || real.equals(root))
				{
					throw new IllegalStateException("file reference refused: no such file in the secrets directory (" + SecretResolvers.SECRETS_DIR_PROPERTY + ")");
				}
				file = real.toFile();
			}
			if (!file.isFile())
			{
				throw new IllegalStateException("file reference refused: no such file in the secrets directory (" + SecretResolvers.SECRETS_DIR_PROPERTY + ")");
			}
			if (file.length() > SecretResolvers.MAX_SECRET_FILE_BYTES)
			{
				throw new IllegalStateException("file reference refused: the file exceeds 1 MiB, not a secret");
			}
			return SecretResolvers.readFile(file);
		}
	}

	/**
	 * {@code vault:secret/data/ebx/sirene#apiKey}: HashiCorp Vault KV (v2 path with {@code /data/},
	 * or v1). Address from {@code -Debx.connectors.vault.address} or {@code VAULT_ADDR}; token from
	 * {@code -Debx.connectors.vault.token}, {@code VAULT_TOKEN} or {@code ~/.vault-token};
	 * namespace from {@code -Debx.connectors.vault.namespace} or {@code VAULT_NAMESPACE}.
	 */
	static final class HashicorpVaultResolver implements SecretResolver
	{
		@Override
		public String getScheme()
		{
			return "vault";
		}

		@Override
		public String resolve(final String pReference) throws Exception
		{
			final String address = SecretResolvers.setting("ebx.connectors.vault.address", "VAULT_ADDR");
			if (address == null)
			{
				throw new IllegalStateException("Vault address not set (-Debx.connectors.vault.address or VAULT_ADDR)");
			}
			String token = SecretResolvers.setting("ebx.connectors.vault.token", "VAULT_TOKEN");
			if (token == null)
			{
				final File home = new File(System.getProperty("user.home"), ".vault-token");
				token = home.isFile() ? SecretResolvers.readFile(home) : null;
			}
			if (token == null)
			{
				throw new IllegalStateException("Vault token not set (-Debx.connectors.vault.token, VAULT_TOKEN or ~/.vault-token)");
			}
			final int hash = pReference.lastIndexOf('#');
			if (hash < 0)
			{
				throw new IllegalStateException("a Vault reference is <path>#<key>, e.g. secret/data/ebx/sirene#apiKey");
			}
			final String path = pReference.substring(0, hash).trim().replaceAll("^/+", "");
			final String key = pReference.substring(hash + 1).trim();
			final Map<String, String> headers = new LinkedHashMap<String, String>();
			headers.put("X-Vault-Token", token);
			final String namespace = SecretResolvers.setting("ebx.connectors.vault.namespace", "VAULT_NAMESPACE");
			if (namespace != null)
			{
				headers.put("X-Vault-Namespace", namespace);
			}
			final HttpJsonClient client = new HttpJsonClient(5000, 10000, "EBX-Connectors/1.0 (vault)");
			final HttpJsonClient.Response response = client.send("GET", HttpJsonClient.buildUrl(address, "/v1/" + path, null), headers, null);
			if (!response.isSuccess())
			{
				throw new IllegalStateException("Vault answered HTTP " + response.getStatus() + " for " + path);
			}
			JsonNode value = Json.at(response.getBody(), "data.data." + key); // KV v2
			if (value == null)
			{
				value = Json.at(response.getBody(), "data." + key); // KV v1
			}
			if (value == null || value.isNull())
			{
				throw new IllegalStateException("key '" + key + "' not found in Vault secret " + path);
			}
			return value.asText();
		}
	}

	/**
	 * {@code azurekv:https://myvault.vault.azure.net/secrets/sirene-key} (or {@code azurekv:myvault/sirene-key}):
	 * Azure Key Vault. Authentication: a service principal from {@code AZURE_TENANT_ID},
	 * {@code AZURE_CLIENT_ID}, {@code AZURE_CLIENT_SECRET} (or the {@code ebx.connectors.azure.*}
	 * system properties), otherwise the managed identity of the host (App Service {@code IDENTITY_ENDPOINT}
	 * or the IMDS endpoint of a virtual machine).
	 */
	static final class AzureKeyVaultResolver implements SecretResolver
	{
		private String token;
		private long tokenExpiry;

		@Override
		public String getScheme()
		{
			return "azurekv";
		}

		@Override
		public String resolve(final String pReference) throws Exception
		{
			String url = pReference.trim();
			if (!url.startsWith("https://"))
			{
				final int slash = url.indexOf('/');
				if (slash < 0)
				{
					throw new IllegalStateException("an Azure Key Vault reference is <vault>/<secret name> or the secret URL");
				}
				url = "https://" + url.substring(0, slash) + ".vault.azure.net/secrets/" + url.substring(slash + 1);
			}
			final Map<String, String> headers = new LinkedHashMap<String, String>();
			headers.put("Authorization", "Bearer " + this.accessToken());
			final HttpJsonClient client = new HttpJsonClient(5000, 10000, "EBX-Connectors/1.0 (azurekv)");
			final HttpJsonClient.Response response = client.send("GET", url + (url.contains("?") ? "&" : "?") + "api-version=7.4", headers, null);
			if (!response.isSuccess())
			{
				throw new IllegalStateException("Azure Key Vault answered HTTP " + response.getStatus() + " for " + url);
			}
			final JsonNode value = Json.at(response.getBody(), "value");
			if (value == null || value.isNull())
			{
				throw new IllegalStateException("no value in the Key Vault answer for " + url);
			}
			return value.asText();
		}

		private synchronized String accessToken() throws Exception
		{
			if (this.token != null && System.currentTimeMillis() < this.tokenExpiry)
			{
				return this.token;
			}
			final HttpJsonClient client = new HttpJsonClient(5000, 10000, "EBX-Connectors/1.0 (azurekv)");
			final String tenant = SecretResolvers.setting("ebx.connectors.azure.tenantId", "AZURE_TENANT_ID");
			final String clientId = SecretResolvers.setting("ebx.connectors.azure.clientId", "AZURE_CLIENT_ID");
			final String secret = SecretResolvers.setting("ebx.connectors.azure.clientSecret", "AZURE_CLIENT_SECRET");
			final HttpJsonClient.Response response;
			if (tenant != null && clientId != null && secret != null)
			{
				final Map<String, String> headers = new LinkedHashMap<String, String>();
				headers.put("Content-Type", "application/x-www-form-urlencoded");
				final String form = "grant_type=client_credentials&client_id=" + HttpJsonClient.encode(clientId) + "&client_secret="
						+ HttpJsonClient.encode(secret) + "&scope=" + HttpJsonClient.encode("https://vault.azure.net/.default");
				response = client.send("POST", "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token", headers, form);
			}
			else
			{
				final String endpoint = SecretResolvers.setting("ebx.connectors.azure.identityEndpoint", "IDENTITY_ENDPOINT");
				final Map<String, String> headers = new LinkedHashMap<String, String>();
				final String url;
				if (endpoint != null)
				{
					headers.put("X-IDENTITY-HEADER", String.valueOf(SecretResolvers.setting("ebx.connectors.azure.identityHeader", "IDENTITY_HEADER")));
					url = endpoint + (endpoint.contains("?") ? "&" : "?") + "api-version=2019-08-01&resource=" + HttpJsonClient.encode("https://vault.azure.net");
				}
				else
				{
					headers.put("Metadata", "true");
					url = "http://169.254.169.254/metadata/identity/oauth2/token?api-version=2018-02-01&resource=" + HttpJsonClient.encode("https://vault.azure.net");
				}
				// the identity endpoint of the host: an address set by the platform, which answers itself and never redirects
				response = new HttpJsonClient(5000, 10000, "EBX-Connectors/1.0 (azure-identity)").forPlatformMetadata()
					.sendBytesWithoutRedirects("GET", url, headers, null, 65536);
			}
			final JsonNode access = Json.at(response.getBody(), "access_token");
			if (!response.isSuccess() || access == null)
			{
				throw new IllegalStateException("Azure identity token refused (HTTP " + response.getStatus() + "): set AZURE_TENANT_ID / AZURE_CLIENT_ID / AZURE_CLIENT_SECRET or run with a managed identity");
			}
			final JsonNode expires = Json.at(response.getBody(), "expires_in");
			this.token = access.asText();
			this.tokenExpiry = System.currentTimeMillis() + (expires == null ? 3000L : Math.max(60L, expires.asLong() - 60L)) * 1000L;
			return this.token;
		}
	}

	/**
	 * {@code awssecrets:<region>/<secret id>[?versionStage=...|versionId=...][#json.path]}.
	 * Credentials use the standard JVM properties ({@code aws.accessKeyId},
	 * {@code aws.secretAccessKey}, {@code aws.sessionToken}) or their AWS environment-variable
	 * equivalents. Workload/container credentials are added separately and are never emulated by
	 * silently accepting anonymous requests.
	 */
	static final class AwsSecretsManagerResolver implements SecretResolver
	{
		@Override
		public String getScheme()
		{
			return "awssecrets";
		}

		@Override
		public String resolve(final String pReference) throws Exception
		{
			final Reference reference = Reference.parse(pReference);
			final AwsCredentials credentials = AwsCredentials.load();

			final ObjectNode request = Json.object();
			request.put("SecretId", reference.secretId);
			if (reference.versionStage != null) request.put("VersionStage", reference.versionStage);
			if (reference.versionId != null) request.put("VersionId", reference.versionId);
			final String body = Json.write(request);
			final String endpointOverride = SecretResolvers.setting("ebx.connectors.aws.secrets.endpoint", null);
			final String endpoint = endpointOverride == null
				? "https://secretsmanager." + reference.region + ".amazonaws.com/"
				: endpointOverride.replaceAll("/+$", "") + "/";
			final Map<String, String> headers = new LinkedHashMap<String, String>();
			headers.put("Content-Type", "application/x-amz-json-1.1");
			headers.put("X-Amz-Target", "secretsmanager.GetSecretValue");
			final Map<String, String> signed = AwsSigV4.sign("POST", endpoint, headers,
				body.getBytes("UTF-8"), credentials.getAccessKeyId(), credentials.getSecretAccessKey(), reference.region,
				"secretsmanager", credentials.getSessionToken());
			final HttpJsonClient.Response response = new HttpJsonClient(5000, 10000,
				"EBX-Connectors/1.0 (awssecrets)").send("POST", endpoint, signed, body);
			if (!response.isSuccess())
			{
				throw new IllegalStateException("AWS Secrets Manager answered HTTP " + response.getStatus()
					+ " for secret " + safeSecretId(reference.secretId));
			}
			final JsonNode answer = response.getBody();
			final JsonNode secretString = answer == null ? null : answer.get("SecretString");
			final JsonNode secretBinary = answer == null ? null : answer.get("SecretBinary");
			final String value;
			if (secretString != null && secretString.isTextual()) value = secretString.asText();
			else if (secretBinary != null && secretBinary.isTextual())
				value = new String(Base64.getDecoder().decode(secretBinary.asText()), "UTF-8");
			else throw new IllegalStateException("AWS Secrets Manager returned neither SecretString nor SecretBinary for secret "
				+ safeSecretId(reference.secretId));

			if (reference.field == null) return value;
			final JsonNode selected;
			try { selected = Json.at(Json.parse(value), reference.field); }
			catch (final Exception ex) { throw new IllegalStateException("AWS secret " + safeSecretId(reference.secretId)
				+ " is not valid JSON for field selection"); }
			if (selected == null || selected.isNull())
				throw new IllegalStateException("field '" + reference.field + "' was not found in AWS secret "
					+ safeSecretId(reference.secretId));
			return selected.isTextual() ? selected.asText() : Json.write(selected);
		}

		private static String firstSetting(final String pModuleProperty, final String pAwsProperty,
			final String pEnvironment)
		{
			final String module = SecretResolvers.setting(pModuleProperty, null);
			return module != null ? module : SecretResolvers.setting(pAwsProperty, pEnvironment);
		}

		static final class Credentials
		{
			private static final long MAX_CREDENTIAL_FILE_BYTES = 1024L * 1024L;
			final String accessKey;
			final String secretKey;
			final String sessionToken;
			final String source;

			Credentials(final String pAccessKey, final String pSecretKey, final String pSessionToken,
				final String pSource)
			{
				this.accessKey = pAccessKey;
				this.secretKey = pSecretKey;
				this.sessionToken = pSessionToken;
				this.source = pSource;
			}

			static Credentials load() throws Exception
			{
				final String access = firstSetting("ebx.connectors.aws.accessKeyId", "aws.accessKeyId", "AWS_ACCESS_KEY_ID");
				final String secret = firstSetting("ebx.connectors.aws.secretAccessKey", "aws.secretAccessKey", "AWS_SECRET_ACCESS_KEY");
				final String session = firstSetting("ebx.connectors.aws.sessionToken", "aws.sessionToken", "AWS_SESSION_TOKEN");
				if (access != null || secret != null)
				{
					if (access == null || secret == null)
						throw new IllegalStateException("AWS access key and secret key must be configured together");
					return new Credentials(access, secret, session, "properties/environment");
				}
				final String configuredFile = firstSetting("ebx.connectors.aws.credentialsFile",
					"aws.sharedCredentialsFile", "AWS_SHARED_CREDENTIALS_FILE");
				final File file = configuredFile == null
					? new File(new File(System.getProperty("user.home"), ".aws"), "credentials")
					: new File(configuredFile);
				final String profile = firstSetting("ebx.connectors.aws.profile", "aws.profile", "AWS_PROFILE");
				final Credentials fromFile = fromProfile(file, profile == null ? "default" : profile);
				if (fromFile != null) return fromFile;
				final Credentials container = fromContainer();
				if (container != null) return container;
				final Credentials instance = fromImdsV2();
				if (instance != null) return instance;
				throw new IllegalStateException("AWS credentials not set in properties/environment, profile '"
					+ (profile == null ? "default" : profile) + "', ECS container metadata, or EC2 IMDSv2");
			}

			static Credentials fromProfile(final File pFile, final String pProfile) throws Exception
			{
				if (pFile == null || !pFile.isFile()) return null;
				if (pFile.length() > MAX_CREDENTIAL_FILE_BYTES)
					throw new IllegalStateException("AWS shared credentials file exceeds 1 MiB");
				String section = null, access = null, secret = null, session = null;
				final BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(pFile), "UTF-8"));
				try
				{
					for (String line = reader.readLine(); line != null; line = reader.readLine())
					{
						final String trimmed = line.trim();
						if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue;
						if (trimmed.startsWith("[") && trimmed.endsWith("]"))
						{
							section = trimmed.substring(1, trimmed.length() - 1).trim();
							continue;
						}
						if (!pProfile.equals(section)) continue;
						final int equals = trimmed.indexOf('=');
						if (equals <= 0) continue;
						final String name = trimmed.substring(0, equals).trim();
						final String value = trimmed.substring(equals + 1).trim();
						if ("aws_access_key_id".equals(name)) access = value;
						else if ("aws_secret_access_key".equals(name)) secret = value;
						else if ("aws_session_token".equals(name)) session = value;
					}
				}
				finally { reader.close(); }
				if (access == null && secret == null && session == null) return null;
				if (access == null || access.isEmpty() || secret == null || secret.isEmpty())
					throw new IllegalStateException("AWS profile '" + pProfile + "' must contain both access and secret keys");
				return new Credentials(access, secret, session, "shared profile " + pProfile);
			}

			static Credentials fromContainer() throws Exception
			{
				final String relative = SecretResolvers.setting("ebx.connectors.aws.containerCredentialsRelativeUri",
					"AWS_CONTAINER_CREDENTIALS_RELATIVE_URI");
				final String full = SecretResolvers.setting("ebx.connectors.aws.containerCredentialsFullUri",
					"AWS_CONTAINER_CREDENTIALS_FULL_URI");
				if (relative == null && full == null) return null;
				final String url;
				if (relative != null)
				{
					if (!relative.startsWith("/") || relative.contains("://"))
						throw new IllegalStateException("AWS container relative credential URI must start with /");
					final String base = System.getProperty("ebx.connectors.aws.containerMetadataEndpoint",
						"http://169.254.170.2").replaceAll("/+$", "");
					validateRelativeContainerBase(base);
					url = base + relative;
				}
				else
				{
					validateContainerUri(full);
					url = full;
				}
				final Map<String, String> headers = new LinkedHashMap<String, String>();
				String authorization = SecretResolvers.setting("ebx.connectors.aws.containerAuthorizationToken",
					"AWS_CONTAINER_AUTHORIZATION_TOKEN");
				final String tokenFile = SecretResolvers.setting("ebx.connectors.aws.containerAuthorizationTokenFile",
					"AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE");
				if (authorization == null && tokenFile != null)
				{
					final File file = new File(tokenFile);
					if (!file.isFile() || file.length() > 16384L)
						throw new IllegalStateException("AWS container authorization token file is missing or exceeds 16 KiB");
					authorization = SecretResolvers.readFile(file);
				}
				if (authorization != null)
				{
					authorization = authorization.trim();
					if (authorization.isEmpty() || authorization.length() > 16384
						|| authorization.indexOf('\r') >= 0 || authorization.indexOf('\n') >= 0)
						throw new IllegalStateException("AWS container authorization token is blank, oversized, or contains a line break");
					headers.put("Authorization", authorization);
				}
				final HttpJsonClient.Response response = metadataClient("aws-container").sendBytesWithoutRedirects(
					"GET", url, headers, null, 65536);
				if (!response.isSuccess())
					throw new IllegalStateException("AWS container credential endpoint answered HTTP " + response.getStatus());
				return fromMetadataAnswer(response.getBody(), "ECS container metadata");
			}

			static Credentials fromImdsV2() throws Exception
			{
				if ("true".equalsIgnoreCase(SecretResolvers.setting("ebx.connectors.aws.disableEc2Metadata",
					"AWS_EC2_METADATA_DISABLED"))) return null;
				final String endpoint = System.getProperty("ebx.connectors.aws.imdsEndpoint",
					"http://169.254.169.254").replaceAll("/+$", "");
				validateImdsUri(endpoint);
				final HttpJsonClient client = metadataClient("aws-imdsv2");
				final Map<String, String> tokenHeaders = new LinkedHashMap<String, String>();
				tokenHeaders.put("X-aws-ec2-metadata-token-ttl-seconds", "21600");
				final HttpJsonClient.Response tokenResponse = client.sendBytesWithoutRedirects("PUT",
					endpoint + "/latest/api/token", tokenHeaders, new byte[0], 16384);
				if (!tokenResponse.isSuccess() || tokenResponse.getRawBody() == null
					|| tokenResponse.getRawBody().trim().isEmpty()) return null;
				final Map<String, String> headers = new LinkedHashMap<String, String>();
				headers.put("X-aws-ec2-metadata-token", tokenResponse.getRawBody().trim());
				final String rolePath = endpoint + "/latest/meta-data/iam/security-credentials/";
				final HttpJsonClient.Response roleResponse = client.sendBytesWithoutRedirects("GET", rolePath,
					headers, null, 16384);
				if (!roleResponse.isSuccess() || roleResponse.getRawBody() == null) return null;
				final String role = roleResponse.getRawBody().trim();
				if (role.isEmpty() || role.contains("/") || role.contains("\\") || role.contains("\n") || role.contains("\r"))
					throw new IllegalStateException("EC2 metadata returned an invalid IAM role name");
				final HttpJsonClient.Response credentials = client.sendBytesWithoutRedirects("GET",
					rolePath + AwsSigV4.encode(role), headers, null, 65536);
				if (!credentials.isSuccess())
					throw new IllegalStateException("EC2 IMDSv2 credential endpoint answered HTTP " + credentials.getStatus());
				return fromMetadataAnswer(credentials.getBody(), "EC2 IMDSv2");
			}

			private static HttpJsonClient metadataClient(final String pName)
			{
				return new HttpJsonClient(1000, 2000, "EBX-Connectors/1.0 (" + pName + ")").forPlatformMetadata();
			}

			private static Credentials fromMetadataAnswer(final JsonNode pAnswer, final String pSource)
			{
				final JsonNode access = pAnswer == null ? null : pAnswer.get("AccessKeyId");
				final JsonNode secret = pAnswer == null ? null : pAnswer.get("SecretAccessKey");
				final JsonNode token = pAnswer == null ? null : pAnswer.get("Token");
				if (access == null || !access.isTextual() || access.asText().isEmpty()
					|| secret == null || !secret.isTextual() || secret.asText().isEmpty()
					|| token == null || !token.isTextual() || token.asText().isEmpty())
					throw new IllegalStateException(pSource + " returned incomplete AWS credentials");
				return new Credentials(access.asText(), secret.asText(), token.asText(), pSource);
			}

			private static void validateContainerUri(final String pUrl)
			{
				if (pUrl == null) throw new IllegalStateException("AWS container credential URI is blank");
				final URI uri;
				try { uri = URI.create(pUrl); }
				catch (final RuntimeException ex) { throw new IllegalStateException("AWS container credential URI is invalid"); }
				final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
				final boolean safeHttpHost = "127.0.0.1".equals(host) || "localhost".equals(host)
					|| "::1".equals(host) || "169.254.170.2".equals(host);
				if (uri.getUserInfo() != null || uri.getFragment() != null)
					throw new IllegalStateException("AWS container credential URI cannot contain user information or a fragment");
				if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()) && safeHttpHost))
					throw new IllegalStateException("AWS container credential URI must use HTTPS or an approved local HTTP host");
				Egress.checkPlatformMetadata(pUrl);
			}

			private static void validateRelativeContainerBase(final String pUrl)
			{
				final URI uri;
				try { uri = URI.create(pUrl); }
				catch (final RuntimeException ex) { throw new IllegalStateException("AWS container metadata endpoint is invalid"); }
				final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
				final boolean testOverride = System.getProperty("ebx.connectors.aws.containerMetadataEndpoint") != null;
				if (!"http".equalsIgnoreCase(uri.getScheme()) || !("169.254.170.2".equals(host)
					|| testOverride && ("127.0.0.1".equals(host) || "localhost".equals(host))))
					throw new IllegalStateException("AWS container metadata endpoint must be the ECS link-local address");
				Egress.checkPlatformMetadata(pUrl);
			}

			private static void validateImdsUri(final String pUrl)
			{
				final URI uri;
				try { uri = URI.create(pUrl); }
				catch (final RuntimeException ex) { throw new IllegalStateException("EC2 IMDS endpoint is invalid"); }
				final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
				final boolean testOverride = System.getProperty("ebx.connectors.aws.imdsEndpoint") != null;
				if (!"http".equalsIgnoreCase(uri.getScheme()) || !("169.254.169.254".equals(host)
					|| testOverride && ("127.0.0.1".equals(host) || "localhost".equals(host))))
					throw new IllegalStateException("EC2 IMDS endpoint must be the IMDS link-local address");
				Egress.checkPlatformMetadata(pUrl);
			}
		}

		private static String safeSecretId(final String pSecretId)
		{
			if (pSecretId.startsWith("arn:")) return "ARN ending " + pSecretId.substring(Math.max(0, pSecretId.length() - 12));
			return pSecretId;
		}

		static final class Reference
		{
			final String region;
			final String secretId;
			final String versionStage;
			final String versionId;
			final String field;

			Reference(final String pRegion, final String pSecretId, final String pVersionStage,
				final String pVersionId, final String pField)
			{
				this.region = pRegion;
				this.secretId = pSecretId;
				this.versionStage = pVersionStage;
				this.versionId = pVersionId;
				this.field = pField;
			}

			static Reference parse(final String pReference)
			{
				String text = pReference == null ? "" : pReference.trim();
				final int hash = text.lastIndexOf('#');
				final String field = hash < 0 ? null : nonBlank(text.substring(hash + 1), "AWS JSON field");
				if (hash >= 0) text = text.substring(0, hash);
				final int query = text.indexOf('?');
				final String queryText = query < 0 ? null : text.substring(query + 1);
				if (query >= 0) text = text.substring(0, query);
				final int slash = text.indexOf('/');
				if (slash <= 0 || slash == text.length() - 1)
					throw new IllegalStateException("an AWS secret reference is <region>/<secret id>[#json.path]");
				final String region = text.substring(0, slash).trim();
				final String secretId = text.substring(slash + 1).trim();
				if (!region.matches("[a-z0-9-]+")) throw new IllegalStateException("invalid AWS region in secret reference");
				String stage = null, version = null;
				if (queryText != null && !queryText.isEmpty())
				{
					for (final String pair : queryText.split("&"))
					{
						final int equals = pair.indexOf('=');
						if (equals <= 0) throw new IllegalStateException("invalid AWS secret version selector");
						final String name = pair.substring(0, equals);
						final String value = nonBlank(pair.substring(equals + 1), "AWS secret version selector");
						if ("versionStage".equals(name)) stage = value;
						else if ("versionId".equals(name)) version = value;
						else throw new IllegalStateException("unsupported AWS secret selector '" + name + "'");
					}
				}
				if (stage != null && version != null)
					throw new IllegalStateException("use either versionStage or versionId for an AWS secret, not both");
				return new Reference(region, secretId, stage, version, field);
			}

			private static String nonBlank(final String pValue, final String pLabel)
			{
				if (pValue == null || pValue.trim().isEmpty()) throw new IllegalStateException(pLabel + " is blank");
				return pValue.trim();
			}
		}
	}

	/** {@code gcpsecret:projects/<project>/secrets/<secret>[/versions/<version>]}. */
	static final class GoogleSecretManagerResolver implements SecretResolver
	{
		private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
		private String token;
		private long tokenExpiry;

		@Override public String getScheme() { return "gcpsecret"; }

		@Override
		public String resolve(final String pReference) throws Exception
		{
			final String resource = normalizeResource(pReference);
			final String endpoint = System.getProperty("ebx.connectors.gcp.secretManagerEndpoint",
				"https://secretmanager.googleapis.com/v1").replaceAll("/+$", "");
			validateSecretManagerEndpoint(endpoint);
			final Map<String, String> headers = new LinkedHashMap<String, String>();
			headers.put("Authorization", "Bearer " + this.accessToken());
			final HttpJsonClient.Response response = new HttpJsonClient(5000, 10000,
				"EBX-Connectors/1.0 (gcpsecret)").sendBytesWithoutRedirects("GET",
				endpoint + "/" + resource + ":access", headers, null, 1048576);
			if (!response.isSuccess())
				throw new IllegalStateException("Google Secret Manager answered HTTP " + response.getStatus()
					+ " for " + safeResource(resource));
			final JsonNode data = Json.at(response.getBody(), "payload.data");
			if (data == null || !data.isTextual() || data.asText().isEmpty())
				throw new IllegalStateException("Google Secret Manager returned no payload for " + safeResource(resource));
			try
			{
				final byte[] decoded = Base64.getDecoder().decode(data.asText());
				if (decoded.length > 65536) throw new IllegalStateException("Google secret payload exceeds 64 KiB");
				final JsonNode expected = Json.at(response.getBody(), "payload.dataCrc32c");
				if (expected != null && !expected.isNull())
				{
					final long wanted;
					try { wanted = Long.parseLong(expected.asText()); }
					catch (final NumberFormatException ex) { throw new IllegalStateException("Google secret payload has an invalid CRC32C"); }
					if (crc32c(decoded) != wanted) throw new IllegalStateException("Google secret payload failed CRC32C verification");
				}
				return new String(decoded, "UTF-8");
			}
			catch (final IllegalArgumentException ex)
			{
				throw new IllegalStateException("Google Secret Manager returned an invalid base64 payload for "
					+ safeResource(resource));
			}
		}

		synchronized String accessToken() throws Exception
		{
			if (this.token != null && System.currentTimeMillis() < this.tokenExpiry) return this.token;
			final String explicit = SecretResolvers.setting("ebx.connectors.gcp.accessToken", "GOOGLE_OAUTH_ACCESS_TOKEN");
			if (explicit != null)
			{
				this.token = explicit;
				this.tokenExpiry = System.currentTimeMillis() + 5L * 60L * 1000L;
				return this.token;
			}
			final String credentialsFile = SecretResolvers.setting("ebx.connectors.gcp.credentialsFile",
				"GOOGLE_APPLICATION_CREDENTIALS");
			if (credentialsFile != null)
			{
				final File file = new File(credentialsFile);
				if (!file.isFile() || file.length() > 1024L * 1024L)
					throw new IllegalStateException("Google application credentials file is missing or exceeds 1 MiB");
				final String json = SecretResolvers.readFile(file);
				final JsonNode document = Json.parse(json);
				if (document == null) throw new IllegalStateException("Google application credentials are not valid JSON");
				if ("service_account".equals(document.path("type").asText()))
				{
					final String tokenEndpoint = System.getProperty("ebx.connectors.gcp.tokenEndpoint",
						document.path("token_uri").asText("https://oauth2.googleapis.com/token"));
					validateGoogleTokenEndpoint(tokenEndpoint);
					this.token = Jwt.googleAccessToken(json, SCOPE, 10000, tokenEndpoint);
					this.tokenExpiry = System.currentTimeMillis() + 50L * 60L * 1000L;
				}
				else if ("external_account".equals(document.path("type").asText()))
				{
					final AccessToken external = externalAccountToken(document);
					this.token = external.value;
					this.tokenExpiry = external.expiry;
				}
				else throw new IllegalStateException("Google application credentials must be a service_account or external_account document");
				return this.token;
			}
			final String metadataEndpoint = System.getProperty("ebx.connectors.gcp.metadataEndpoint",
				"http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token");
			validateMetadataEndpoint(metadataEndpoint);
			final Map<String, String> headers = new LinkedHashMap<String, String>();
			headers.put("Metadata-Flavor", "Google");
			final HttpJsonClient.Response response = new HttpJsonClient(1000, 2000,
				"EBX-Connectors/1.0 (gcp-metadata)").forPlatformMetadata().sendBytesWithoutRedirects("GET",
				metadataEndpoint, headers, null, 65536);
			final JsonNode access = response.getBody() == null ? null : response.getBody().get("access_token");
			if (!response.isSuccess() || access == null || !access.isTextual() || access.asText().isEmpty()
				|| !"Google".equals(response.getHeader("Metadata-Flavor")))
				throw new IllegalStateException("Google metadata identity token unavailable (HTTP " + response.getStatus() + ")");
			final long expires = Math.max(60L, response.getBody().path("expires_in").asLong(300L) - 60L);
			this.token = access.asText();
			this.tokenExpiry = System.currentTimeMillis() + expires * 1000L;
			return this.token;
		}

		private static AccessToken externalAccountToken(final JsonNode pDocument) throws Exception
		{
			final String audience = requiredText(pDocument, "audience");
			final String subjectType = requiredText(pDocument, "subject_token_type");
			final JsonNode source = pDocument.get("credential_source");
			if (source == null || !source.isObject()) throw new IllegalStateException("Google external_account credential_source is required");
			if (source.has("url") || source.has("environment_id") || source.has("executable"))
				throw new IllegalStateException("Google external_account supports only a local-file credential_source");
			final String subjectPath = requiredText(source, "file");
			if (subjectPath.contains("://")) throw new IllegalStateException("Google external_account subject token must use a local file");
			final File subjectFile = new File(subjectPath);
			if (!subjectFile.isFile() || subjectFile.length() > 65536L)
				throw new IllegalStateException("Google external_account subject token file is missing or exceeds 64 KiB");
			String subjectToken = SecretResolvers.readFile(subjectFile);
			final JsonNode format = source.get("format");
			if (format != null && !format.isNull())
			{
				final String type = format.path("type").asText("text");
				if ("json".equals(type))
				{
					final String field = requiredText(format, "subject_token_field_name");
					final JsonNode tokenDocument = Json.parse(subjectToken);
					if (tokenDocument == null || tokenDocument.get(field) == null || !tokenDocument.get(field).isTextual())
						throw new IllegalStateException("Google external_account subject token JSON field is missing");
					subjectToken = tokenDocument.get(field).asText();
				}
				else if (!"text".equals(type)) throw new IllegalStateException("Google external_account subject token format is unsupported");
			}
			subjectToken = subjectToken.trim();
			if (subjectToken.isEmpty()) throw new IllegalStateException("Google external_account subject token is empty");

			final String configuredSts = requiredText(pDocument, "token_url");
			final String sts = System.getProperty("ebx.connectors.gcp.stsEndpoint", configuredSts);
			validateStsEndpoint(sts);
			final String form = "audience=" + HttpJsonClient.encode(audience)
				+ "&grant_type=" + HttpJsonClient.encode("urn:ietf:params:oauth:grant-type:token-exchange")
				+ "&requested_token_type=" + HttpJsonClient.encode("urn:ietf:params:oauth:token-type:access_token")
				+ "&scope=" + HttpJsonClient.encode(SCOPE)
				+ "&subject_token_type=" + HttpJsonClient.encode(subjectType)
				+ "&subject_token=" + HttpJsonClient.encode(subjectToken);
			final Map<String, String> headers = new LinkedHashMap<String, String>();
			headers.put("Content-Type", "application/x-www-form-urlencoded");
			final HttpJsonClient.Response response = new HttpJsonClient(5000, 10000,
				"EBX-Connectors/1.0 (gcp-sts)").sendBytesWithoutRedirects("POST", sts, headers,
				form.getBytes("UTF-8"), 65536);
			final AccessToken federated = parsedAccessToken(response, "Google STS token exchange");
			if (!pDocument.hasNonNull("service_account_impersonation_url")) return federated;

			final String configuredImpersonation = pDocument.get("service_account_impersonation_url").asText();
			final String impersonation = System.getProperty("ebx.connectors.gcp.impersonationEndpoint", configuredImpersonation);
			validateImpersonationEndpoint(impersonation);
			final Map<String, String> impersonationHeaders = new LinkedHashMap<String, String>();
			impersonationHeaders.put("Authorization", "Bearer " + federated.value);
			impersonationHeaders.put("Content-Type", "application/json");
			final byte[] request = ("{\"scope\":[\"" + SCOPE + "\"],\"lifetime\":\"3600s\"}").getBytes("UTF-8");
			final HttpJsonClient.Response impersonated = new HttpJsonClient(5000, 10000,
				"EBX-Connectors/1.0 (gcp-impersonation)").sendBytesWithoutRedirects("POST", impersonation,
				impersonationHeaders, request, 65536);
			if (!impersonated.isSuccess() || impersonated.getBody() == null
				|| !impersonated.getBody().path("accessToken").isTextual())
				throw new IllegalStateException("Google service-account impersonation failed (HTTP " + impersonated.getStatus() + ")");
			long expiry = System.currentTimeMillis() + 50L * 60L * 1000L;
			final String expireTime = impersonated.getBody().path("expireTime").asText();
			try { if (!expireTime.isEmpty()) expiry = java.time.Instant.parse(expireTime).toEpochMilli() - 60000L; }
			catch (final RuntimeException ignored) { /* retain the conservative default */ }
			return new AccessToken(impersonated.getBody().path("accessToken").asText(), expiry);
		}

		private static AccessToken parsedAccessToken(final HttpJsonClient.Response pResponse, final String pOperation)
		{
			final JsonNode body = pResponse.getBody();
			if (!pResponse.isSuccess() || body == null || !body.path("access_token").isTextual()
				|| body.path("access_token").asText().isEmpty())
				throw new IllegalStateException(pOperation + " failed (HTTP " + pResponse.getStatus() + ")");
			final long seconds = Math.max(60L, body.path("expires_in").asLong(3600L) - 60L);
			return new AccessToken(body.path("access_token").asText(), System.currentTimeMillis() + seconds * 1000L);
		}

		private static String requiredText(final JsonNode pNode, final String pField)
		{
			final JsonNode value = pNode == null ? null : pNode.get(pField);
			if (value == null || !value.isTextual() || value.asText().trim().isEmpty())
				throw new IllegalStateException("Google external_account " + pField + " is required");
			return value.asText().trim();
		}

		private static final class AccessToken
		{
			final String value;
			final long expiry;
			AccessToken(final String pValue, final long pExpiry)
			{
				this.value = pValue;
				this.expiry = pExpiry;
			}
		}

		static String normalizeResource(final String pReference)
		{
			String resource = pReference == null ? "" : pReference.trim().replaceAll("^/+|/+$", "");
			if (resource.matches("projects/[A-Za-z0-9._~-]+/secrets/[A-Za-z0-9._~-]+"))
				resource += "/versions/latest";
			if (!resource.matches("projects/[A-Za-z0-9._~-]+/secrets/[A-Za-z0-9._~-]+/versions/[A-Za-z0-9._~-]+"))
				throw new IllegalStateException("a Google secret reference is projects/<project>/secrets/<secret>[/versions/<version>]");
			return resource;
		}

		private static String safeResource(final String pResource)
		{
			final String[] parts = pResource.split("/");
			return parts.length < 4 ? "secret resource" : "secret " + parts[3];
		}

		private static void validateMetadataEndpoint(final String pEndpoint)
		{
			final URI uri;
			try { uri = URI.create(pEndpoint); }
			catch (final RuntimeException ex) { throw new IllegalStateException("Google metadata endpoint is invalid"); }
			final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
			final boolean testOverride = System.getProperty("ebx.connectors.gcp.metadataEndpoint") != null;
			if (!"http".equalsIgnoreCase(uri.getScheme()) || !("metadata.google.internal".equals(host)
				|| "169.254.169.254".equals(host) || testOverride && ("127.0.0.1".equals(host) || "localhost".equals(host))))
				throw new IllegalStateException("Google metadata endpoint must be the metadata service address");
			Egress.checkPlatformMetadata(pEndpoint);
		}

		private static void validateGoogleTokenEndpoint(final String pEndpoint)
		{
			final URI uri;
			try { uri = URI.create(pEndpoint); }
			catch (final RuntimeException ex) { throw new IllegalStateException("Google token endpoint is invalid"); }
			final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
			final boolean testOverride = System.getProperty("ebx.connectors.gcp.tokenEndpoint") != null;
			final boolean official = "https".equalsIgnoreCase(uri.getScheme())
				&& "oauth2.googleapis.com".equals(host) && "/token".equals(uri.getPath());
			final boolean localTest = testOverride && "http".equalsIgnoreCase(uri.getScheme())
				&& ("127.0.0.1".equals(host) || "localhost".equals(host));
			if (!official && !localTest) throw new IllegalStateException("Google token endpoint must be the official OAuth endpoint");
			Egress.check(pEndpoint);
		}

		private static void validateStsEndpoint(final String pEndpoint)
		{
			validateOfficialOrLocalEndpoint(pEndpoint, "ebx.connectors.gcp.stsEndpoint",
				"sts.googleapis.com", "/v1/token", "Google STS endpoint must be the official token exchange endpoint");
		}

		private static void validateImpersonationEndpoint(final String pEndpoint)
		{
			final URI uri;
			try { uri = URI.create(pEndpoint); }
			catch (final RuntimeException ex) { throw new IllegalStateException("Google impersonation endpoint is invalid"); }
			final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
			final boolean official = "https".equalsIgnoreCase(uri.getScheme()) && "iamcredentials.googleapis.com".equals(host)
				&& uri.getPath() != null && uri.getPath().matches("/v1/projects/-/serviceAccounts/[^/]+:generateAccessToken");
			final boolean override = System.getProperty("ebx.connectors.gcp.impersonationEndpoint") != null;
			final boolean local = override && "http".equalsIgnoreCase(uri.getScheme())
				&& ("127.0.0.1".equals(host) || "localhost".equals(host));
			if (!official && !local) throw new IllegalStateException("Google impersonation endpoint must be the official IAM Credentials endpoint");
			Egress.check(pEndpoint);
		}

		private static void validateOfficialOrLocalEndpoint(final String pEndpoint, final String pOverride,
			final String pHost, final String pPath, final String pMessage)
		{
			final URI uri;
			try { uri = URI.create(pEndpoint); }
			catch (final RuntimeException ex) { throw new IllegalStateException(pMessage); }
			final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
			final boolean official = "https".equalsIgnoreCase(uri.getScheme()) && pHost.equals(host) && pPath.equals(uri.getPath());
			final boolean override = System.getProperty(pOverride) != null;
			final boolean local = override && "http".equalsIgnoreCase(uri.getScheme())
				&& ("127.0.0.1".equals(host) || "localhost".equals(host));
			if (!official && !local) throw new IllegalStateException(pMessage);
			Egress.check(pEndpoint);
		}

		private static void validateSecretManagerEndpoint(final String pEndpoint)
		{
			final URI uri;
			try { uri = URI.create(pEndpoint); }
			catch (final RuntimeException ex) { throw new IllegalStateException("Google Secret Manager endpoint is invalid"); }
			final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
			final boolean testOverride = System.getProperty("ebx.connectors.gcp.secretManagerEndpoint") != null;
			final boolean official = "https".equalsIgnoreCase(uri.getScheme())
				&& "secretmanager.googleapis.com".equals(host) && "/v1".equals(uri.getPath());
			final boolean localTest = testOverride && "http".equalsIgnoreCase(uri.getScheme())
				&& ("127.0.0.1".equals(host) || "localhost".equals(host));
			if (!official && !localTest) throw new IllegalStateException("Google Secret Manager endpoint must be the official API endpoint");
			Egress.check(pEndpoint);
		}

		/** CRC-32C (Castagnoli), returned as the unsigned uint32 used by Secret Manager. */
		static long crc32c(final byte[] pData)
		{
			int crc = 0xFFFFFFFF;
			for (final byte value : pData)
			{
				crc ^= value & 0xFF;
				for (int bit = 0; bit < 8; bit++)
					crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0x82F63B78);
			}
			return (~crc) & 0xFFFFFFFFL;
		}
	}
}
