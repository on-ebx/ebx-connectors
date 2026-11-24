package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.HttpJsonClient;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * INPI, Registre National des Entreprises ({@code https://registre-national-entreprises.inpi.fr/api}):
 * the official French register with the legal identity, the head office and the beneficial owners.
 * Free account: authentication HTTP Basic (username / password of the INPI account), exchanged for a
 * bearer token by {@code POST /sso/login} and cached ({@code token.ttl} seconds, default 3000).
 *
 * <p>Operations: {@code company} (siren) the whole record ({@code formality.content.personneMorale...});
 * {@code search} (name, optional postalCode) the first company matching the name.
 */
public final class InpiConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("company", "Company record by SIREN (identity, head office, beneficial owners)", "siren"),
		Operation.search("search", "First company matching a name (optional postalCode)", "name"));

	private String token;
	private long tokenUntil;

	@Override
	public String getType()
	{
		return "inpi";
	}

	@Override
	public String getLabel()
	{
		return "INPI - Registre National des Entreprises (France)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "CORPORATE_REGISTRIES").description("French National Business Register company identity and beneficial-owner data.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.BASIC);
		descriptor.configurationProperty(com.on.ebx.connect.spi.ConfigurationPropertyDefinition.builder("token.ttl",
			com.on.ebx.connect.spi.ConfigurationPropertyType.INTEGER).label("token.ttl").description("Authentication token lifetime in seconds").advanced(true).build());
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return InpiConnector.OPERATIONS;
	}

	private synchronized String token() throws ConnectorException
	{
		final ConnectorConfig config = this.getConfig();
		if (this.token != null && System.currentTimeMillis() < this.tokenUntil)
		{
			return this.token;
		}
		if (config.getUsername() == null || config.isPlaceholder(config.getUsername()) || config.getPassword() == null || config.isPlaceholder(config.getPassword()))
		{
			throw new ConnectorException(config.getId() + ": set the INPI account in username / password (authentication BASIC)");
		}
		final ObjectNode body = Json.object();
		body.put("username", config.getUsername());
		body.put("password", config.getPassword());
		final Map<String, String> noAuth = new LinkedHashMap<String, String>();
		noAuth.put("Authorization", "");
		final HttpJsonClient.Response response = this.send("POST", "/sso/login", null, body, noAuth);
		final JsonNode reply = this.bodyOrNull(response, "login");
		final String t = reply == null ? null : reply.path("token").asText(null);
		if (t == null)
		{
			throw new ConnectorException(config.getId() + ": login gave no token");
		}
		this.token = t;
		this.tokenUntil = System.currentTimeMillis() + Long.parseLong(config.getProperty("token.ttl", "3000").trim()) * 1000L;
		return t;
	}

	private Map<String, String> bearer() throws ConnectorException
	{
		final Map<String, String> headers = new LinkedHashMap<String, String>();
		headers.put("Authorization", "Bearer " + this.token());
		return headers;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		if ("company".equals(pOperation))
		{
			final String siren = AbstractRestConnector.require(pInputs, "siren").replaceAll("\\D", "");
			if (siren.length() != 9)
			{
				return null;
			}
			return this.bodyOrNull(this.send("GET", "/companies/" + siren, null, null, this.bearer()), "company " + siren);
		}
		if ("search".equals(pOperation))
		{
			final Map<String, String> query = new LinkedHashMap<String, String>();
			query.put("companyName", AbstractRestConnector.require(pInputs, "name"));
			final String postalCode = pInputs.get("postalCode");
			if (postalCode != null && !postalCode.trim().isEmpty())
			{
				query.put("codePostal", postalCode.trim());
			}
			query.put("pageSize", "1");
			final JsonNode reply = this.bodyOrNull(this.send("GET", "/companies", query, null, this.bearer()), "search");
			if (reply == null)
			{
				return null;
			}
			final JsonNode first = reply.isArray() ? (reply.size() > 0 ? reply.get(0) : null) : Json.at(reply, "results[0]");
			return first == null || first.isNull() ? null : first;
		}
		throw new ConnectorException(this.getConfig().getId() + ": unknown operation '" + pOperation + "'");
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final String pm = "formality.content.personneMorale.";
		return Arrays.asList(FieldInfo.of("formality.siren", "SIREN", "string"),
			FieldInfo.of(pm + "identite.entreprise.denomination", "Legal name", "string"),
			FieldInfo.of(pm + "identite.entreprise.formeJuridique", "Legal form code (INSEE)", "string"),
			FieldInfo.of(pm + "identite.entreprise.dateImmat", "Registration date", "string"),
			FieldInfo.of(pm + "identite.entreprise.dateDebutActiv", "Activity start", "string"),
			FieldInfo.of(pm + "identite.description.montantCapital", "Share capital", "number"),
			FieldInfo.of(pm + "identite.description.deviseCapital", "Capital currency", "string"),
			FieldInfo.of(pm + "identite.description.objet", "Corporate purpose", "string"),
			FieldInfo.of(pm + "adresseEntreprise.adresse.numVoie", "Street number", "string"),
			FieldInfo.of(pm + "adresseEntreprise.adresse.typeVoie", "Street type", "string"),
			FieldInfo.of(pm + "adresseEntreprise.adresse.voie", "Street", "string"),
			FieldInfo.of(pm + "adresseEntreprise.adresse.codePostal", "Postal code", "string"),
			FieldInfo.of(pm + "adresseEntreprise.adresse.commune", "City", "string"),
			FieldInfo.of(pm + "adresseEntreprise.adresse.pays", "Country", "string"),
			FieldInfo.of(pm + "beneficiairesEffectifs[0].beneficiaire.descriptionPersonne.nom", "Beneficial owner: last name", "string"),
			FieldInfo.of(pm + "beneficiairesEffectifs[0].beneficiaire.descriptionPersonne.prenoms[0]", "Beneficial owner: first name", "string"),
			FieldInfo.of(pm + "beneficiairesEffectifs[0].modalite.pourcentageParts", "Beneficial owner: share %", "number"),
			FieldInfo.of("formality.content.natureCessationEntreprise", "Cessation", "string"),
			FieldInfo.of("updatedAt", "Last update", "string"));
	}
}
