package com.on.ebx.connect.core;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.on.ebx.connect.core.CredentialGuide.State;
import com.on.ebx.connect.spi.ConnectorConfig.AuthType;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.Connector;

/** Offline checks of the credential guide: field states per mode, type refinements, bundle coverage, form wiring. */
public final class CredentialGuideTest
{
	private static int checks;

	private CredentialGuideTest() { }

	public static void main(final String[] pArgs) throws Exception
	{
		// Generic modes.
		Map<String, State> s = CredentialGuide.fieldStates("rest", null, AuthType.BASIC, null);
		eq(State.REQUIRED, s.get("username"), "BASIC needs a user name");
		eq(State.REQUIRED, s.get("password"), "BASIC needs a password");
		eq(State.UNUSED, s.get("apiKey"), "BASIC ignores the key");
		s = CredentialGuide.fieldStates("rest", null, AuthType.API_KEY_HEADER, null);
		eq(State.REQUIRED, s.get("apiKeyHeader"), "API_KEY_HEADER needs the header name");
		eq(State.REQUIRED, s.get("apiKey"), "API_KEY_HEADER needs the key");
		eq(State.UNUSED, s.get("username"), "API_KEY_HEADER ignores the user");
		s = CredentialGuide.fieldStates("rest", null, AuthType.BEARER, null);
		eq(State.REQUIRED, s.get("apiKey"), "BEARER needs the token");
		eq(State.UNUSED, s.get("apiKeyHeader"), "BEARER ignores the header name");
		s = CredentialGuide.fieldStates("rest", null, AuthType.NONE, null);
		for (final String field : CredentialGuide.FIELDS) eq(State.UNUSED, s.get(field), "NONE uses no " + field);

		// OAuth 2.0 flows.
		s = CredentialGuide.fieldStates("rest", null, AuthType.OAUTH2, null);
		eq(State.REQUIRED, s.get("apiKey"), "client_credentials needs the client secret");
		eq(State.UNUSED, s.get("password"), "client_credentials ignores the password");
		s = CredentialGuide.fieldStates("rest", null, AuthType.OAUTH2, "password");
		eq(State.REQUIRED, s.get("username"), "password flow needs the user");
		eq(State.OPTIONAL, s.get("apiKey"), "password flow may omit the client secret");
		s = CredentialGuide.fieldStates("rest", null, AuthType.OAUTH2, "jwt_bearer");
		eq(State.UNUSED, s.get("apiKey"), "jwt_bearer signs with a private key, no shared secret");
		eq("client_credentials", CredentialGuide.normalizeFlow("nonsense"), "unknown flow falls back to client_credentials");
		List<CredentialGuide.Property> props = CredentialGuide.properties("rest", AuthType.OAUTH2, "refresh_token");
		yes(has(props, "oauth.tokenUrl", State.REQUIRED), "generic OAuth needs oauth.tokenUrl");
		yes(has(props, "oauth.refreshToken", State.REQUIRED), "refresh_token flow needs oauth.refreshToken");
		yes(CredentialGuide.properties("rest", AuthType.BASIC, null).isEmpty(), "BASIC expects no property");

		// Type refinements.
		props = CredentialGuide.properties("powerbi", AuthType.OAUTH2, null);
		yes(has(props, "tenantId", State.REQUIRED), "Power BI needs tenantId");
		yes(has(props, "oauth.tokenUrl", State.DERIVED), "Power BI derives oauth.tokenUrl");
		yes(has(props, "oauth.scope", State.DERIVED), "Power BI derives oauth.scope");
		yes(!has(props, "oauth.flow", State.OPTIONAL), "Power BI replaces the generic OAuth properties");
		eq(State.REQUIRED, CredentialGuide.fieldStates("google-geocoding", null, AuthType.NONE, null).get("apiKey"), "Google Geocoding reads its key on NONE");
		eq(State.UNUSED, CredentialGuide.fieldStates("kafka", null, AuthType.BASIC, null).get("apiKey"), "Kafka never reads the key");
		yes(has(CredentialGuide.properties("salesforce", AuthType.OAUTH2, null), "oauth.tokenUrl", State.DERIVED), "Salesforce derives its token URL");

		// Descriptors: modes offered, self-managed secrets.
		final ConnectorDescriptor powerBi = descriptor("com.on.ebx.connect.connectors.PowerBiConnector");
		final List<AuthType> modes = CredentialGuide.modesFor(powerBi);
		yes(modes.contains(AuthType.OAUTH2) && modes.contains(AuthType.BEARER) && !modes.contains(AuthType.BASIC), "Power BI offers OAUTH2 and BEARER only: " + modes);
		eq(AuthType.values().length, CredentialGuide.modesFor(null).size(), "an unknown type offers every mode");
		final ConnectorDescriptor serviceBus = descriptor("com.on.ebx.connect.connectors.AzureServiceBusConnector");
		yes(CredentialGuide.isSelfManaged(serviceBus), "Service Bus manages its own secret");

		// Every mode and every refined type is documented in both bundles, keys identical.
		final Properties en = bundle("Messages_en.properties");
		final Properties fr = bundle("Messages_fr.properties");
		for (final AuthType mode : AuthType.values())
			for (final String suffix : new String[] { ".name", ".short", ".desc", ".send" })
			{
				yes(en.containsKey("credentials.mode." + mode.name() + suffix), "en: credentials.mode." + mode.name() + suffix);
				yes(fr.containsKey("credentials.mode." + mode.name() + suffix), "fr: credentials.mode." + mode.name() + suffix);
			}
		for (final Object key : en.keySet())
			if (String.valueOf(key).startsWith("credentials.")) yes(fr.containsKey(key), "fr bundle misses " + key);
		for (final Object key : fr.keySet())
			if (String.valueOf(key).startsWith("credentials.")) yes(en.containsKey(key), "en bundle misses " + key);
		yes(String.valueOf(en.get("credentials.mode.NONE.code")).contains("configured endpoint"), "generic NONE example must be provider-neutral");
		yes(String.valueOf(en.get("credentials.type.gleif.NONE.code")).contains("api.gleif.org"), "GLEIF keeps its own public example");
		yes(String.valueOf(en.get("credentials.type.vies.NONE.code")).contains("check-vat-number"), "VIES exposes its own public example");
		for (final String type : CredentialGuide.refinedTypes())
			if (!type.equals("dynamics365") && !isDatabase(type) && !type.equals("sharepoint") && !type.equals("dynamics-business-central"))
				yes(en.containsKey("credentials.type." + type + ".hint"), "refined type without a hint: " + type);

		// Form wiring and page script.
		final Path module = Paths.get(System.getProperty("ebx.connectors.moduleDir", "."));
		final String form = read(module.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ConnectorForm.java"));
		yes(form.contains("credentials.writeFields(pWriter)") && form.contains("credentials.writeGuide(pWriter)") && form.contains("credentials.writeScript(pWriter)"),
			"the Connection tab must render the credential guide");
		final String pane = read(module.resolve("src/main/java/com/on/ebx/connect/ebx/ui/ConnectorCredentialsPane.java"));
		yes(pane.contains("row.setRowId(\"ftcc_\""), "native credential rows must carry a stable id");
		yes(pane.contains("connect/connector-credentials.js"), "the pane must load its page script");
		final String js = read(module.resolve("src/main/webapp/www/common/jscripts/connect/connector-credentials.js"));
		yes(js.contains("window.ftccModel") && js.contains("data-ftcc-mode"), "page script must follow the model and the mode panes");
		yes(!js.contains(".submit(") && !js.contains("XMLHttpRequest") && !js.contains("fetch("), "page script must never submit or send anything");
		System.out.println("PASS: credential guide (" + checks + " checks)");
	}

	private static boolean isDatabase(final String pType)
	{
		return pType.equals("mysql") || pType.equals("h2") || pType.equals("jdbc") || pType.equals("sql-server") || pType.equals("azure-sql") || pType.equals("oracle-database");
	}

	private static ConnectorDescriptor descriptor(final String pClass) throws Exception
	{
		return ((Connector) Class.forName(pClass).newInstance()).getDescriptor();
	}

	private static Properties bundle(final String pName) throws Exception
	{
		final Properties properties = new Properties();
		final InputStream in = CredentialGuideTest.class.getResourceAsStream("/com/on/ebx/connect/ebx/ui/" + pName);
		if (in == null) throw new AssertionError("bundle not on the classpath: " + pName);
		try { properties.load(new InputStreamReader(in, StandardCharsets.UTF_8)); } finally { in.close(); }
		return properties;
	}

	private static boolean has(final List<CredentialGuide.Property> pProperties, final String pName, final State pState)
	{
		for (final CredentialGuide.Property property : pProperties) if (property.getName().equals(pName) && property.getState() == pState) return true;
		return false;
	}

	private static String read(final Path pPath) throws Exception { return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8); }

	private static void eq(final Object pExpected, final Object pActual, final String pMessage)
	{
		checks++;
		if (pExpected == null ? pActual != null : !pExpected.equals(pActual)) throw new AssertionError(pMessage + ": expected " + pExpected + ", got " + pActual);
	}

	private static void yes(final boolean pCondition, final String pMessage)
	{
		checks++;
		if (!pCondition) throw new AssertionError(pMessage);
	}
}
