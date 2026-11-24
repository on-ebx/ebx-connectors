package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * INSEE Sirene API 3.11 (https://api.insee.fr/api-sirene/3.11). Needs an API key sent in the
 * {@code X-INSEE-Api-Key-Integration} header: configure the connector with authType
 * API_KEY_HEADER, that header name and the key.
 */
public final class SireneConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("siren", "Legal unit by SIREN (uniteLegale)", "siren"),
		Operation.read("siret", "Establishment by SIRET (etablissement), with its address", "siret"),
		Operation.read("headOffice", "Head office establishment of a SIREN (siren + nicSiegeUniteLegale)", "siren"));

	@Override
	public String getType()
	{
		return "sirene";
	}

	@Override
	public String getLabel()
	{
		return "INSEE Sirene";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "CORPORATE_REGISTRIES").description("French legal-unit and establishment data from the INSEE Sirene API.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.API_KEY_HEADER);
		descriptor.apiVersion("3.11");
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return SireneConnector.OPERATIONS;
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation,
		final Map<String, String> pInputs, final JsonNode pPayload) throws ConnectorException
	{
		this.requireSecret();
		if ("siren".equals(pOperation))
		{
			final String siren = SireneConnector.digits(AbstractRestConnector.require(pInputs, "siren"), 9, "siren");
			return this.bodyOrNull(this.get("/siren/" + siren, null), "siren/" + siren);
		}
		if ("siret".equals(pOperation))
		{
			final String siret = SireneConnector.digits(AbstractRestConnector.require(pInputs, "siret"), 14, "siret");
			return this.bodyOrNull(this.get("/siret/" + siret, null), "siret/" + siret);
		}
		if ("headOffice".equals(pOperation))
		{
			final String siren = SireneConnector.digits(AbstractRestConnector.require(pInputs, "siren"), 9, "siren");
			final JsonNode unit = this.bodyOrNull(this.get("/siren/" + siren, null), "siren/" + siren);
			final JsonNode nic = com.on.ebx.connect.core.Json.at(unit, "uniteLegale.periodesUniteLegale[0].nicSiegeUniteLegale");
			if (nic == null)
			{
				return null;
			}
			final String siret = siren + nic.asText();
			return this.bodyOrNull(this.get("/siret/" + siret, null), "siret/" + siret);
		}
		throw new ConnectorException("Sirene: unknown operation '" + pOperation + "'");
	}

	private static final String[][] UNIT_FIELDS = {
		{ "uniteLegale.siren", "SIREN", "string" }, { "uniteLegale.dateCreationUniteLegale", "Creation date", "date" },
		{ "uniteLegale.categorieEntreprise", "Company category (PME/ETI/GE)", "string" }, { "uniteLegale.trancheEffectifsUniteLegale", "Headcount bracket", "string" },
		{ "uniteLegale.periodesUniteLegale[0].denominationUniteLegale", "Legal name", "string" },
		{ "uniteLegale.periodesUniteLegale[0].categorieJuridiqueUniteLegale", "Legal category (INSEE)", "string" },
		{ "uniteLegale.periodesUniteLegale[0].activitePrincipaleUniteLegale", "Main activity (NAF)", "string" },
		{ "uniteLegale.periodesUniteLegale[0].etatAdministratifUniteLegale", "Administrative state (A/C)", "string" },
		{ "uniteLegale.periodesUniteLegale[0].nicSiegeUniteLegale", "Head office NIC", "string" } };
	private static final String[][] ESTABLISHMENT_FIELDS = {
		{ "etablissement.siret", "SIRET", "string" }, { "etablissement.siren", "SIREN", "string" }, { "etablissement.etablissementSiege", "Head office?", "boolean" },
		{ "etablissement.adresseEtablissement.numeroVoieEtablissement", "Street number", "string" },
		{ "etablissement.adresseEtablissement.typeVoieEtablissement", "Street type", "string" },
		{ "etablissement.adresseEtablissement.libelleVoieEtablissement", "Street name", "string" },
		{ "etablissement.adresseEtablissement.codePostalEtablissement", "Postal code", "string" },
		{ "etablissement.adresseEtablissement.libelleCommuneEtablissement", "City", "string" },
		{ "etablissement.adresseEtablissement.codeCommuneEtablissement", "INSEE city code", "string" },
		{ "etablissement.periodesEtablissement[0].activitePrincipaleEtablissement", "Establishment activity (NAF)", "string" },
		{ "etablissement.periodesEtablissement[0].etatAdministratifEtablissement", "Establishment state (A/F)", "string" } };

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		for (final String[] f : "siren".equals(pOperation) ? SireneConnector.UNIT_FIELDS : SireneConnector.ESTABLISHMENT_FIELDS)
		{
			fields.add(FieldInfo.of(f[0], f[1], f[2]));
		}
		return fields;
	}

	private static String digits(final String pValue, final int pLength, final String pName) throws ConnectorException
	{
		final String digits = pValue.replaceAll("[^0-9]", "");
		if (digits.length() != pLength)
		{
			throw new ConnectorException("Sirene: '" + pValue + "' is not a " + pLength + "-digit " + pName);
		}
		return digits;
	}
}
