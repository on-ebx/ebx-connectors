package com.on.ebx.connect.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Ownership-safe lifecycle operations over a persistent template store. */
public final class ConnectorTemplateService
{
	private final ConnectorTemplateStore store;

	public ConnectorTemplateService(final ConnectorTemplateStore pStore)
	{
		if (pStore == null) throw new IllegalArgumentException("store is required");
		this.store = pStore;
	}

	public List<ConnectorTemplate> list() throws IOException { return this.store.list(); }

	public String exportTemplates() throws IOException
	{
		final List<ConnectorTemplate> safe = new ArrayList<ConnectorTemplate>();
		for (final ConnectorTemplate template : list())
		{
			safe.add(copyWithConfiguration(template, redact(template.getConfiguration())));
		}
		return ConnectorTemplateTransfer.exportTemplates(safe);
	}

	/** Imports new customer templates and upgrades packaged templates; no existing customer row is replaced. */
	public int importTemplates(final String pDocument, final String pActor) throws IOException
	{
		int changed = 0;
		for (final ConnectorTemplate incoming : ConnectorTemplateTransfer.importTemplates(pDocument))
		{
			rejectSecrets(incoming.getConfiguration());
			final ConnectorTemplate existing = this.store.find(incoming.getId());
			if (incoming.getOwnership() == ConnectorTemplate.Ownership.CUSTOMER)
			{
				if (existing != null) throw new IllegalStateException("Import will not overwrite template " + incoming.getId());
				this.store.create(incoming, pActor);
				changed++;
			}
			else if (existing == null)
			{
				this.store.create(incoming, pActor);
				changed++;
			}
			else
			{
				if (existing.getOwnership() != ConnectorTemplate.Ownership.PACKAGED)
					throw new IllegalStateException("Packaged import cannot replace customer template " + incoming.getId());
				if (incoming.getVersion() < existing.getVersion()) throw new IllegalArgumentException("Packaged templates cannot be downgraded");
				if (incoming.getVersion() > existing.getVersion())
				{
					this.store.replacePackaged(incoming, pActor);
					changed++;
				}
			}
		}
		return changed;
	}

	public ConnectorTemplate copy(final String pSourceId, final String pNewId, final String pActor) throws IOException
	{
		if (this.store.find(pNewId) != null) throw new IllegalArgumentException("Template id already exists: " + pNewId);
		final ConnectorTemplate source = required(pSourceId);
		final String origin = source.getOwnership() == ConnectorTemplate.Ownership.PACKAGED ? source.getId() : source.getBasedOn();
		final int originVersion = source.getOwnership() == ConnectorTemplate.Ownership.PACKAGED ? source.getVersion() : source.getBasedOnVersion();
		final ConnectorTemplate copy = new ConnectorTemplate(pNewId, 1, ConnectorTemplate.Ownership.CUSTOMER,
			origin, originVersion, source.getConfiguration());
		this.store.create(copy, pActor);
		return copy;
	}

	/** Creates a new customer copy based on the newest packaged revision, preserving the old copy. */
	public ConnectorTemplate upgradeCopy(final String pCustomerId, final String pNewId, final String pActor) throws IOException
	{
		final ConnectorTemplate customer = required(pCustomerId);
		if (customer.getOwnership() != ConnectorTemplate.Ownership.CUSTOMER) throw new IllegalArgumentException("Only customer copies can be upgraded");
		final ConnectorTemplate packaged = required(customer.getBasedOn());
		if (packaged.getOwnership() != ConnectorTemplate.Ownership.PACKAGED) throw new IllegalStateException("Packaged origin is unavailable");
		if (packaged.getVersion() <= customer.getBasedOnVersion()) throw new IllegalStateException("No newer packaged version is available");
		return copy(packaged.getId(), pNewId, pActor);
	}

	private ConnectorTemplate required(final String pId) throws IOException
	{
		final ConnectorTemplate template = this.store.find(pId);
		if (template == null) throw new IllegalArgumentException("No template " + pId);
		return template;
	}

	private static ConnectorTemplate copyWithConfiguration(final ConnectorTemplate pTemplate, final ObjectNode pConfiguration)
	{
		return new ConnectorTemplate(pTemplate.getId(), pTemplate.getVersion(), pTemplate.getOwnership(),
			pTemplate.getBasedOn(), pTemplate.getBasedOnVersion(), pConfiguration);
	}

	static ObjectNode redact(final ObjectNode pConfiguration)
	{
		final ObjectNode copy = pConfiguration.deepCopy();
		redactObject(copy);
		return copy;
	}

	private static void redactObject(final ObjectNode pObject)
	{
		final java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = pObject.fields();
		while (fields.hasNext())
		{
			final java.util.Map.Entry<String, JsonNode> field = fields.next();
			if (secretName(field.getKey())) pObject.put(field.getKey(), "***");
			else if (field.getValue().isObject()) redactObject((ObjectNode) field.getValue());
			else if (field.getValue().isArray()) redactArray((ArrayNode) field.getValue());
		}
	}

	private static void redactArray(final ArrayNode pArray)
	{
		for (final JsonNode value : pArray)
		{
			if (value.isObject()) redactObject((ObjectNode) value);
			else if (value.isArray()) redactArray((ArrayNode) value);
		}
	}

	private static void rejectSecrets(final ObjectNode pConfiguration)
	{
		if (containsSecret(pConfiguration)) throw new IllegalArgumentException("Template imports must not contain credentials or secret values");
	}

	private static boolean containsSecret(final JsonNode pNode)
	{
		if (pNode == null) return false;
		if (pNode.isObject())
		{
			final java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = pNode.fields();
			while (fields.hasNext())
			{
				final java.util.Map.Entry<String, JsonNode> field = fields.next();
				if (secretName(field.getKey()) && !field.getValue().isNull() && !"***".equals(field.getValue().asText())) return true;
				if (containsSecret(field.getValue())) return true;
			}
		}
		else if (pNode.isArray()) for (final JsonNode value : pNode) if (containsSecret(value)) return true;
		return false;
	}

	private static boolean secretName(final String pName)
	{
		final String name = pName == null ? "" : pName.toLowerCase(Locale.ENGLISH).replace("_", "").replace("-", "");
		return name.contains("password") || name.contains("secret") || name.contains("apikey") || name.contains("token")
			|| name.contains("privatekey") || name.contains("credential");
	}
}
