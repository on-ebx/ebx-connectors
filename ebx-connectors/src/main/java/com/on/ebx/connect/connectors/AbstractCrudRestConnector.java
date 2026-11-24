package com.on.ebx.connect.connectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.AbstractRestConnector;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.Operation;

/**
 * Base for object-store style APIs (CRM, ITSM, ERP REST): get by id, query, create, update,
 * upsert, with a {@code {results:[...], count}} normalisation for lists.
 */
abstract class AbstractCrudRestConnector extends AbstractRestConnector
{
	static final List<Operation> CRUD = Arrays.asList(
		Operation.read("get", "One object by id (inputs object, id)", "object", "id"),
		Operation.search("query", "Objects of a type matching a filter (inputs object, filter, max)", "object"),
		Operation.write("create", "Create an object from the outbound document", "object"),
		Operation.write("update", "Update an object by id from the outbound document", "object", "id"),
		Operation.write("upsert", "Update when the object exists, create otherwise (object, id or lookup field + value)", "object"));

	@Override
	public List<Operation> getOperations()
	{
		return new ArrayList<Operation>(AbstractCrudRestConnector.CRUD);
	}

	static JsonNode list(final JsonNode pArray, final JsonNode pTotal)
	{
		if (pArray == null || !pArray.isArray() || pArray.size() == 0)
		{
			return null;
		}
		final ObjectNode out = Json.object();
		out.set("results", pArray);
		out.put("count", pArray.size());
		if (pTotal != null && pTotal.isNumber())
		{
			out.put("totalCount", pTotal.asLong());
		}
		return out;
	}

	static String safeName(final String pName)
	{
		return pName.trim().replaceAll("[^A-Za-z0-9_]", "");
	}
}
