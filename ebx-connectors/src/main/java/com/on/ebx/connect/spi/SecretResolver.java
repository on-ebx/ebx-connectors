package com.on.ebx.connect.spi;

/**
 * Resolves a secret reference of the form {@code <scheme>:<reference>} written in a secret field
 * (API key, password) or in a connector property, so that the secret itself lives in a vault and
 * not in the EBX repository. Built-in schemes: {@code env}, {@code file}, {@code vault} (HashiCorp KV),
 * {@code azurekv} (Azure Key Vault), {@code awssecrets} (AWS Secrets Manager), and
 * {@code gcpsecret} (Google Secret Manager). Third parties add a scheme by implementing this interface
 * and declaring the class in {@code META-INF/services/com.on.ebx.connect.spi.SecretResolver}.
 * Resolved values are cached a few minutes (system property {@code ebx.connectors.secrets.cacheSeconds}).
 */
public interface SecretResolver
{
	/** The scheme this resolver handles, lower case, without the colon (e.g. {@code vault}). */
	String getScheme();

	/**
	 * The secret designated by {@code pReference} (the part after the colon).
	 *
	 * @throws Exception when the reference cannot be resolved; the message is shown to the
	 *         administrator, so it should say what is missing (address, token, key...).
	 */
	String resolve(String pReference) throws Exception;
}
