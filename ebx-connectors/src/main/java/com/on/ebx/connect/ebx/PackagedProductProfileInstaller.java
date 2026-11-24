package com.on.ebx.connect.ebx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorTemplate;
import com.on.ebx.connect.core.ConnectorTemplateService;
import com.on.ebx.connect.core.ConnectorTemplateTransfer;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.ProductConnectorTemplates;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;

/** Installs versioned product profiles after the baseline connector catalog is available. */
public final class PackagedProductProfileInstaller
{
	private static final String ACTOR = "ebx-connectors-packaged-profile-installer";

	private PackagedProductProfileInstaller() { }

	/**
	 * Installs only template rows. The referenced connector catalog records remain owned by
	 * {@link ConnectorsInstaller}; this deliberately cannot duplicate or rewrite them.
	 */
	public static void install(final Repository pRepository, final Session pSession, final LoggingCategory pLog)
	{
		try
		{
			final Adaptation dataset = new ConnectorRepository(pRepository).dataset();
			if (dataset == null)
			{
				pLog.warn("[connectors] packaged profiles skipped: Connectors dataset is unavailable");
				return;
			}
			final AdaptationTable connectors = dataset.getTable(ConnectorsPaths.CONNECTOR);
			final Set<String> installedConnectorIds = new HashSet<String>();
			for (final ConnectorTemplate template : ProductConnectorTemplates.packaged())
			{
				final String connectorId = template.getConfiguration().path("connectorId").asText();
				if (connectors.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(connectorId)) != null) installedConnectorIds.add(connectorId);
			}
			final List<ConnectorTemplate> available = selectAvailable(ProductConnectorTemplates.packaged(), installedConnectorIds);
			for (final ConnectorTemplate template : ProductConnectorTemplates.packaged())
			{
				final String connectorId = template.getConfiguration().path("connectorId").asText();
				if (!installedConnectorIds.contains(connectorId)) pLog.warn("[connectors] packaged profile " + template.getId() + " skipped: connector " + connectorId + " is not installed");
			}
			final ConnectorTemplateService service = new ConnectorTemplateService(new EbxConnectorTemplateStore(pRepository, pSession));
			int changed = 0;
			for (final ConnectorTemplate template : available)
			{
				try { changed += service.importTemplates(ConnectorTemplateTransfer.exportTemplates(java.util.Collections.singletonList(template)), ACTOR); }
				catch (final RuntimeException ex)
				{
					// A conflicting customer-owned id is preserved; it must not block unrelated profiles.
					pLog.error("[connectors] packaged profile " + template.getId() + " was not installed: " + ex.getMessage());
				}
			}
			pLog.info("[connectors] packaged product profiles ready: " + available.size() + " validated, " + changed + " installed or upgraded");
		}
		catch (final IOException ex)
		{
			pLog.warn("[connectors] packaged product profiles are unavailable: " + ex.getMessage());
		}
		catch (final RuntimeException ex) { pLog.error("[connectors] packaged product profile installation stopped: " + ex.getMessage()); }
	}

	static List<ConnectorTemplate> selectAvailable(final Iterable<ConnectorTemplate> pTemplates, final Set<String> pInstalledConnectorIds)
	{
		final List<ConnectorTemplate> available = new ArrayList<ConnectorTemplate>();
		for (final ConnectorTemplate template : pTemplates)
		{
			if (pInstalledConnectorIds.contains(template.getConfiguration().path("connectorId").asText())) available.add(template);
		}
		return available;
	}
}
