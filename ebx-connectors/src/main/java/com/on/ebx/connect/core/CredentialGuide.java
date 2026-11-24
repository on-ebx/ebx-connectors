package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConnectorConfig.AuthType;
import com.on.ebx.connect.spi.ConnectorDescriptor;

/**
 * What a connector's credential fields mean for a given authentication mode: which of
 * {@code username}, {@code password}, {@code apiKeyHeader} and {@code apiKey} must be filled,
 * which are ignored, and which {@code ConnectorProperty} rows the mode expects. Pure structure —
 * every human-readable text lives in the {@code Messages} bundles under {@code credentials.*} so
 * that the form stays bilingual.
 * <p>
 * The default rules follow the platform: {@code BASIC} = user + password, {@code API_KEY_HEADER} =
 * header name + key, {@code BEARER} = key only, {@code OAUTH2} = client secret in the key field plus
 * the {@code oauth.*} properties (flow-dependent), {@code NONE} = nothing. A connector type that
 * manages its own authentication ({@link AuthenticationMethod#CUSTOM}, service account, certificate,
 * SSH key) stays on {@code NONE} and reads its secret material itself; the types the module knows
 * are refined here (Google Geocoding, Loqate and Melissa require the key, Kafka never reads it,
 * Microsoft types derive the token endpoint from the tenant, Salesforce from the base URL).
 */
public final class CredentialGuide
{
	/** Meaning of a credential field or property for the selected mode. */
	public enum State
	{
		REQUIRED, OPTIONAL, DERIVED, UNUSED;

		public String code() { return this.name().toLowerCase(Locale.ENGLISH); }
	}

	/** The four credential fields of the {@code Connector} table, in form order. */
	public static final List<String> FIELDS = Collections.unmodifiableList(java.util.Arrays.asList("username", "password", "apiKeyHeader", "apiKey"));
	/** Longest value the encrypted fields accept ({@code maxLength} of {@code password} / {@code apiKey} in Connectors.xsd). */
	public static final int SECRET_MAX_LENGTH = 255;
	public static final String DEFAULT_FLOW = "client_credentials";
	private static final List<String> FLOWS = Collections.unmodifiableList(java.util.Arrays.asList(DEFAULT_FLOW, "password", "refresh_token", "jwt_bearer"));

	/** A {@code ConnectorProperty} row the mode expects; {@code derivedValue} is the pattern a derived one takes. */
	public static final class Property
	{
		private final String name;
		private final State state;
		private final String derivedValue;

		Property(final String pName, final State pState, final String pDerivedValue)
		{
			this.name = pName;
			this.state = pState;
			this.derivedValue = pDerivedValue;
		}

		public String getName() { return this.name; }
		public State getState() { return this.state; }
		public String getDerivedValue() { return this.derivedValue; }
	}

	private static final class ModeTweak
	{
		final Map<String, State> fields = new HashMap<String, State>();
		final List<Property> properties = new ArrayList<Property>();
		boolean replaceProperties;
	}

	private static final Map<String, Map<AuthType, ModeTweak>> TYPES = new HashMap<String, Map<AuthType, ModeTweak>>();

	static
	{
		// Types on NONE that still read the key field, and how strictly.
		requireKey("google-geocoding");
		requireKey("loqate");
		requireKey("melissa");
		requireKey("azure-service-bus");
		field("kafka", AuthType.NONE, "apiKey", State.UNUSED);
		field("kafka", AuthType.BASIC, "apiKey", State.UNUSED);
		properties("kafka", AuthType.BASIC, false, prop("security.protocol", State.OPTIONAL), prop("sasl.mechanism", State.OPTIONAL));
		// Microsoft Entra types: tenant + application, token endpoint and scope derived by configure().
		entra("powerbi", "https://analysis.windows.net/powerbi/api/.default");
		entra("sharepoint", "https://graph.microsoft.com/.default");
		entra("dynamics-business-central", "https://api.businesscentral.dynamics.com/.default");
		properties("dynamics365", AuthType.OAUTH2, true, prop("tenantId", State.REQUIRED), prop("clientId", State.REQUIRED),
			prop("oauth.tokenUrl", State.REQUIRED), prop("oauth.scope", State.REQUIRED));
		// Salesforce builds the token URL from the login server given as base URL.
		properties("salesforce", AuthType.OAUTH2, true, prop("oauth.flow", State.OPTIONAL), prop("clientId", State.REQUIRED),
			prop("oauth.tokenUrl", State.DERIVED, "{baseUrl}/services/oauth2/token"), prop("apiVersion", State.OPTIONAL));
		// Databases: a JNDI data source replaces URL and account.
		for (final String db : new String[] { "postgresql", "mysql", "h2", "jdbc", "sql-server", "azure-sql", "oracle-database" })
			properties(db, AuthType.BASIC, false, prop("jndi", State.OPTIONAL));
	}

	private CredentialGuide() { }

	private static void requireKey(final String pType) { field(pType, AuthType.NONE, "apiKey", State.REQUIRED); }

	private static void entra(final String pType, final String pScope)
	{
		properties(pType, AuthType.OAUTH2, true, prop("tenantId", State.REQUIRED), prop("clientId", State.REQUIRED),
			prop("oauth.tokenUrl", State.DERIVED, "https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token"),
			prop("oauth.scope", State.DERIVED, pScope));
	}

	private static Property prop(final String pName, final State pState) { return new Property(pName, pState, null); }
	private static Property prop(final String pName, final State pState, final String pDerived) { return new Property(pName, pState, pDerived); }

	private static ModeTweak tweak(final String pType, final AuthType pMode)
	{
		Map<AuthType, ModeTweak> modes = TYPES.get(pType);
		if (modes == null)
		{
			modes = new HashMap<AuthType, ModeTweak>();
			TYPES.put(pType, modes);
		}
		ModeTweak tweak = modes.get(pMode);
		if (tweak == null)
		{
			tweak = new ModeTweak();
			modes.put(pMode, tweak);
		}
		return tweak;
	}

	private static void field(final String pType, final AuthType pMode, final String pField, final State pState)
	{ tweak(pType, pMode).fields.put(pField, pState); }

	private static void properties(final String pType, final AuthType pMode, final boolean pReplace, final Property... pProperties)
	{
		final ModeTweak tweak = tweak(pType, pMode);
		tweak.replaceProperties = pReplace;
		Collections.addAll(tweak.properties, pProperties);
	}

	/** The form mode a declared authentication method maps to; self-managed methods stay on NONE. */
	public static AuthType modeOf(final AuthenticationMethod pMethod)
	{
		if (pMethod == null) return AuthType.NONE;
		switch (pMethod)
		{
			case BASIC: return AuthType.BASIC;
			case API_KEY_HEADER: return AuthType.API_KEY_HEADER;
			case BEARER_TOKEN: return AuthType.BEARER;
			case OAUTH2_CLIENT_CREDENTIALS:
			case OAUTH2_AUTHORIZATION_CODE:
			case OAUTH2_REFRESH_TOKEN: return AuthType.OAUTH2;
			default: return AuthType.NONE;
		}
	}

	/** Modes a connector type offers, in form order; every mode when the descriptor declares none. */
	public static List<AuthType> modesFor(final ConnectorDescriptor pDescriptor)
	{
		final Set<AuthType> modes = EnumSet.noneOf(AuthType.class);
		if (pDescriptor != null)
			for (final AuthenticationMethod method : pDescriptor.getAuthenticationMethods()) modes.add(modeOf(method));
		if (modes.isEmpty()) modes.addAll(EnumSet.allOf(AuthType.class));
		return Collections.unmodifiableList(new ArrayList<AuthType>(modes));
	}

	/** Whether the type reads its own secret material while the form says NONE. */
	public static boolean isSelfManaged(final ConnectorDescriptor pDescriptor)
	{
		if (pDescriptor == null) return false;
		for (final AuthenticationMethod method : pDescriptor.getAuthenticationMethods())
			switch (method)
			{
				case CUSTOM: case SERVICE_ACCOUNT: case CERTIFICATE: case SSH_KEY: return true;
				default: break;
			}
		return false;
	}

	public static String normalizeFlow(final String pFlow)
	{
		if (pFlow == null) return DEFAULT_FLOW;
		final String flow = pFlow.trim().toLowerCase(Locale.ENGLISH);
		return FLOWS.contains(flow) ? flow : DEFAULT_FLOW;
	}

	/** State of each credential field for a type, a mode and (OAUTH2) a flow, in form order. */
	public static Map<String, State> fieldStates(final String pType, final ConnectorDescriptor pDescriptor, final AuthType pMode, final String pFlow)
	{
		final AuthType mode = pMode == null ? AuthType.NONE : pMode;
		final Map<String, State> states = new LinkedHashMap<String, State>();
		for (final String field : FIELDS) states.put(field, State.UNUSED);
		switch (mode)
		{
			case BASIC: states.put("username", State.REQUIRED);
			states.put("password", State.REQUIRED);
			break;
			case API_KEY_HEADER: states.put("apiKeyHeader", State.REQUIRED);
			states.put("apiKey", State.REQUIRED);
			break;
			case BEARER: states.put("apiKey", State.REQUIRED);
			break;
			case OAUTH2:
				states.put("apiKey", State.REQUIRED);
				final String flow = normalizeFlow(pFlow);
				if ("password".equals(flow))
				{
					states.put("username", State.REQUIRED);
					states.put("password", State.REQUIRED);
					states.put("apiKey", State.OPTIONAL);
				}
				else if ("jwt_bearer".equals(flow))
				{
					states.put("apiKey", State.UNUSED);
					states.put("username", State.OPTIONAL);
				}
				break;
			default:
				if (isSelfManaged(pDescriptor)) states.put("apiKey", State.OPTIONAL);
				break;
		}
		final Map<AuthType, ModeTweak> modes = pType == null ? null : TYPES.get(pType);
		final ModeTweak tweak = modes == null ? null : modes.get(mode);
		if (tweak != null) states.putAll(tweak.fields);
		return states;
	}

	/** {@code ConnectorProperty} rows the mode expects, in display order (type refinements first). */
	public static List<Property> properties(final String pType, final AuthType pMode, final String pFlow)
	{
		final AuthType mode = pMode == null ? AuthType.NONE : pMode;
		final Map<AuthType, ModeTweak> modes = pType == null ? null : TYPES.get(pType);
		final ModeTweak tweak = modes == null ? null : modes.get(mode);
		final List<Property> result = new ArrayList<Property>();
		if (tweak != null) result.addAll(tweak.properties);
		if (mode == AuthType.OAUTH2 && (tweak == null || !tweak.replaceProperties))
		{
			add(result, prop("oauth.flow", State.OPTIONAL));
			add(result, prop("oauth.tokenUrl", State.REQUIRED));
			add(result, prop("clientId", State.REQUIRED));
			add(result, prop("oauth.scope", State.OPTIONAL));
		}
		if (mode == AuthType.OAUTH2)
		{
			final String flow = normalizeFlow(pFlow);
			if ("refresh_token".equals(flow)) add(result, prop("oauth.refreshToken", State.REQUIRED));
			else if ("jwt_bearer".equals(flow)) add(result, prop("oauth.privateKey", State.REQUIRED));
		}
		return Collections.unmodifiableList(result);
	}

	private static void add(final List<Property> pList, final Property pProperty)
	{
		for (final Property existing : pList) if (existing.getName().equals(pProperty.getName())) return;
		pList.add(pProperty);
	}

	/** Types with refinements, for the contract test. */
	public static Set<String> refinedTypes() { return Collections.unmodifiableSet(TYPES.keySet()); }
}
