package com.on.ebx.connect.connectors;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.Operation;

/**
 * openFDA ({@code https://api.fda.gov}), the public US FDA API, no key needed (an optional key raises
 * the quota: put it in apiKey, it is sent as the {@code api_key} parameter). Operations:
 * {@code udi} (di: the device identifier of a UDI, GUDID record), {@code deviceSearch} (brand, optional
 * company), {@code drug} (ndc: product NDC). The first result is returned as is; the designer
 * catalogue lists the usual fields.
 */
public final class OpenFdaConnector extends AbstractRestConnector
{
	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.read("udi", "GUDID device record by device identifier (DI)", "di"),
		Operation.search("deviceSearch", "GUDID device by brand name (optional company)", "brand"),
		Operation.read("drug", "Drug product by NDC (National Drug Code)", "ndc"));

	@Override
	public String getType()
	{
		return "openfda";
	}

	@Override
	public String getLabel()
	{
		return "openFDA (US FDA: devices GUDID, drugs)";
	}

	@Override
	public com.on.ebx.connect.spi.ConnectorDescriptor getDescriptor()
	{
		final com.on.ebx.connect.spi.ConnectorDescriptor.Builder descriptor = com.on.ebx.connect.spi.ConnectorDescriptor.builder(
			this.getType(), this.getLabel(), "RISK_COMPLIANCE").description("US FDA device and drug reference-data lookup through openFDA.");
		for (final Operation operation : this.getOperations())
		{
			final com.on.ebx.connect.spi.ConnectorDirection direction = operation.getKind() == Operation.Kind.WRITE
				? com.on.ebx.connect.spi.ConnectorDirection.OUTBOUND : com.on.ebx.connect.spi.ConnectorDirection.INBOUND;
			descriptor.direction(direction).operation(new com.on.ebx.connect.spi.ConnectorOperationDescriptor(operation.getName(),
				operation.getKind(), operation.getDescription(), java.util.Collections.singleton(direction),
				java.util.Collections.<com.on.ebx.connect.spi.ConnectorCapability>emptySet()));
		}
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.NONE);
		descriptor.authentication(com.on.ebx.connect.spi.AuthenticationMethod.CUSTOM);
		return descriptor.build();
	}

	@Override
	public List<Operation> getOperations()
	{
		return OpenFdaConnector.OPERATIONS;
	}

	private static String quoted(final String pValue)
	{
		return "\"" + pValue.replace("\"", "") + "\"";
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		final Map<String, String> query = new LinkedHashMap<String, String>();
		final String path;
		if ("udi".equals(pOperation))
		{
			query.put("search", "identifiers.id:" + OpenFdaConnector.quoted(AbstractRestConnector.require(pInputs, "di")));
			path = "/device/udi.json";
		}
		else if ("deviceSearch".equals(pOperation))
		{
			String search = "brand_name:" + OpenFdaConnector.quoted(AbstractRestConnector.require(pInputs, "brand"));
			final String company = pInputs.get("company");
			if (company != null && !company.trim().isEmpty())
			{
				search += "+AND+company_name:" + OpenFdaConnector.quoted(company.trim());
			}
			query.put("search", search);
			path = "/device/udi.json";
		}
		else if ("drug".equals(pOperation))
		{
			query.put("search", "product_ndc:" + OpenFdaConnector.quoted(AbstractRestConnector.require(pInputs, "ndc")));
			path = "/drug/ndc.json";
		}
		else
		{
			throw new ConnectorException(this.getConfig().getId() + ": unknown operation '" + pOperation + "'");
		}
		query.put("limit", "1");
		final String key = this.getConfig().getApiKey();
		if (key != null && !this.getConfig().isPlaceholder(key))
		{
			query.put("api_key", key);
		}
		final JsonNode reply = this.bodyOrNull(this.get(path, query), pOperation);
		final JsonNode first = Json.at(reply, "results[0]");
		return first == null || first.isNull() ? null : first;
	}

	@Override
	public List<FieldInfo> describeFields(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs)
	{
		if ("drug".equals(pOperation))
		{
			return Arrays.asList(FieldInfo.of("product_ndc", "Product NDC", "string"), FieldInfo.of("brand_name", "Brand name", "string"),
				FieldInfo.of("generic_name", "Generic name", "string"), FieldInfo.of("labeler_name", "Labeler", "string"),
				FieldInfo.of("dosage_form", "Dosage form", "string"), FieldInfo.of("route[0]", "Route", "string"),
				FieldInfo.of("product_type", "Product type", "string"), FieldInfo.of("marketing_category", "Marketing category", "string"),
				FieldInfo.of("active_ingredients[0].name", "Active ingredient", "string"), FieldInfo.of("active_ingredients[0].strength", "Strength", "string"),
				FieldInfo.of("packaging[0].package_ndc", "Package NDC", "string"), FieldInfo.of("packaging[0].description", "Package", "string"));
		}
		return Arrays.asList(FieldInfo.of("identifiers[0].id", "Primary DI", "string"), FieldInfo.of("brand_name", "Brand name", "string"),
			FieldInfo.of("company_name", "Company", "string"), FieldInfo.of("device_description", "Description", "string"),
			FieldInfo.of("version_or_model_number", "Model", "string"), FieldInfo.of("catalog_number", "Catalog number", "string"),
			FieldInfo.of("record_status", "Record status", "string"), FieldInfo.of("publish_date", "Publish date", "string"),
			FieldInfo.of("is_rx", "Prescription", "boolean"), FieldInfo.of("is_otc", "Over the counter", "boolean"),
			FieldInfo.of("is_single_use", "Single use", "boolean"), FieldInfo.of("sterilization.is_sterile", "Sterile", "boolean"),
			FieldInfo.of("gmdn_terms[0].name", "GMDN term", "string"), FieldInfo.of("gmdn_terms[0].definition", "GMDN definition", "string"),
			FieldInfo.of("product_codes[0].code", "FDA product code", "string"), FieldInfo.of("product_codes[0].name", "Product code name", "string"),
			FieldInfo.of("product_codes[0].openfda.device_class", "Device class", "string"),
			FieldInfo.of("product_codes[0].openfda.medical_specialty_description", "Medical specialty", "string"),
			FieldInfo.of("public_device_record_key", "GUDID record key", "string"), FieldInfo.of("mri_safety", "MRI safety", "string"));
	}
}
