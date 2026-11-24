package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Ownership-aware template store. Packaged upgrades replace only the packaged record; customer
 * copies retain their content and the exact packaged version from which they were created.
 */
public final class ConnectorTemplateLibrary
{
	public enum InstallResult { INSTALLED, UPGRADED, UNCHANGED }
	private final Map<String, ConnectorTemplate> templates = new LinkedHashMap<String, ConnectorTemplate>();

	public synchronized InstallResult installPackaged(final ConnectorTemplate pTemplate)
	{
		if (pTemplate.getOwnership() != ConnectorTemplate.Ownership.PACKAGED)
		{
			throw new IllegalArgumentException("Only packaged templates can be installed as product content");
		}
		final ConnectorTemplate existing = this.templates.get(pTemplate.getId());
		if (existing != null && existing.getOwnership() != ConnectorTemplate.Ownership.PACKAGED)
		{
			throw new IllegalStateException("A customer template already owns id " + pTemplate.getId());
		}
		if (existing != null && pTemplate.getVersion() < existing.getVersion())
		{
			throw new IllegalArgumentException("Packaged templates cannot be downgraded");
		}
		if (existing != null && pTemplate.getVersion() == existing.getVersion()) { return InstallResult.UNCHANGED; }
		this.templates.put(pTemplate.getId(), pTemplate);
		return existing == null ? InstallResult.INSTALLED : InstallResult.UPGRADED;
	}

	public synchronized ConnectorTemplate customize(final String pPackagedId, final String pCustomerId,
		final ObjectNode pConfiguration)
	{
		if (this.templates.containsKey(pCustomerId)) { throw new IllegalArgumentException("Template id already exists: " + pCustomerId); }
		final ConnectorTemplate origin = this.templates.get(pPackagedId);
		if (origin == null || origin.getOwnership() != ConnectorTemplate.Ownership.PACKAGED)
		{
			throw new IllegalArgumentException("No packaged template " + pPackagedId);
		}
		final ObjectNode configuration = pConfiguration == null ? origin.getConfiguration() : pConfiguration;
		final ConnectorTemplate copy = new ConnectorTemplate(pCustomerId, 1, ConnectorTemplate.Ownership.CUSTOMER,
			origin.getId(), origin.getVersion(), configuration);
		this.templates.put(copy.getId(), copy);
		return copy;
	}

	/** Imported packaged content follows upgrade rules; customer content may never replace another owner. */
	public synchronized void importTemplates(final Iterable<ConnectorTemplate> pTemplates)
	{
		for (final ConnectorTemplate template : pTemplates)
		{
			if (template.getOwnership() == ConnectorTemplate.Ownership.PACKAGED) { this.installPackaged(template); }
			else
			{
				final ConnectorTemplate existing = this.templates.get(template.getId());
				if (existing != null && existing.getOwnership() != ConnectorTemplate.Ownership.CUSTOMER)
				{
					throw new IllegalStateException("Customer import cannot replace packaged template " + template.getId());
				}
				if (existing != null && template.getVersion() < existing.getVersion())
				{
					throw new IllegalArgumentException("Customer templates cannot be downgraded");
				}
				this.templates.put(template.getId(), template);
			}
		}
	}

	public synchronized ConnectorTemplate get(final String pId) { return this.templates.get(pId); }
	public synchronized List<ConnectorTemplate> all()
	{
		return Collections.unmodifiableList(new ArrayList<ConnectorTemplate>(this.templates.values()));
	}
}
