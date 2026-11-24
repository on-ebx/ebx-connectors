package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.LoggingCategory;

/**
 * Finds the connections that hold a secret in clear text: the {@code password} and {@code apiKey} of a
 * {@code Connector} row that EBX did not encrypt (a value written through Data Services or an import), and the
 * {@code ConnectorProperty} values whose key names credential material ({@link Secrets#isSecretProperty}) and
 * that are not a {@code vault:}/{@code env:}/{@code file:} reference. Since 2026-10-06
 * {@code ebx.connectors.secrets.allowClear} is off by default, so such a connection is refused at use
 * ({@link Secrets#reveal}, {@link #propertyProblem}); this class is what tells the administrator which ones, at
 * startup (kernel log) and on {@code GET /v1/status}. It reads the stored values to classify them and never
 * returns or logs one.
 */
public final class ClearSecrets
{
	/** Most findings kept in a report: the count stays exact, the list is bounded. */
	public static final int MAX_LISTED = 50;

	/** One secret held in clear: the connector id and where ({@code password}, {@code apiKey} or {@code property <key>}). */
	public static final class Finding
	{
		private final String connectorId;
		private final String where;

		Finding(final String pConnectorId, final String pWhere)
		{
			this.connectorId = pConnectorId;
			this.where = pWhere;
		}

		public String connectorId()
		{
			return this.connectorId;
		}

		public String where()
		{
			return this.where;
		}

		@Override
		public String toString()
		{
			return this.connectorId + " (" + this.where + ")";
		}
	}

	/** The outcome of a scan: the exact count, and the first {@link #MAX_LISTED} findings. */
	public static final class Report
	{
		private final int count;
		private final List<Finding> listed;

		Report(final int pCount, final List<Finding> pListed)
		{
			this.count = pCount;
			this.listed = pListed;
		}

		public int count()
		{
			return this.count;
		}

		public List<Finding> listed()
		{
			return this.listed;
		}
	}

	private static volatile Object[] cache; // {Adaptation dataset, Long scannedAt, Report}
	private static final long CACHE_MILLIS = 60000L;

	private ClearSecrets()
	{
	}

	/** Every secret of the dataset held in clear; never throws for a dataset it cannot read fully (returns what it read). */
	public static Report scan(final Adaptation pDataset)
	{
		final List<Finding> listed = new ArrayList<Finding>();
		int count = 0;
		if (pDataset == null)
		{
			return new Report(0, listed);
		}
		if (pDataset.getTable(ConnectorsPaths.CONNECTOR) != null)
		{
			final RequestResult rows = pDataset.getTable(ConnectorsPaths.CONNECTOR).createRequest().execute();
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					final String id = row.getString(ConnectorsPaths.Connector.ID);
					for (final Object[] field : new Object[][] { { ConnectorsPaths.Connector.PASSWORD, "password" },
						{ ConnectorsPaths.Connector.API_KEY, "apiKey" } })
					{
						if (Secrets.isStoredInClear(row.getString((com.orchestranetworks.schema.Path) field[0])))
						{
							count++;
							if (listed.size() < ClearSecrets.MAX_LISTED)
							{
								listed.add(new Finding(id, (String) field[1]));
							}
						}
					}
				}
			}
			finally
			{
				rows.close();
			}
		}
		if (pDataset.getTable(ConnectorsPaths.CONNECTOR_PROPERTY) != null)
		{
			final RequestResult rows = pDataset.getTable(ConnectorsPaths.CONNECTOR_PROPERTY).createRequest().execute();
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					final String key = row.getString(ConnectorsPaths.ConnectorProperty.KEY);
					if (Secrets.isSecretProperty(key) && Secrets.isStoredInClear(row.getString(ConnectorsPaths.ConnectorProperty.VALUE)))
					{
						count++;
						if (listed.size() < ClearSecrets.MAX_LISTED)
						{
							listed.add(new Finding(row.getString(ConnectorsPaths.ConnectorProperty.CONNECTOR), "property " + key));
						}
					}
				}
			}
			finally
			{
				rows.close();
			}
		}
		return new Report(count, listed);
	}

	/**
	 * The connections that hold a {@code file:} or {@code env:} reference this instance refuses ({@link SecretResolvers#referenceRefusal}):
	 * same shape as {@link #scan}, {@code where} says the field and the reason. Never a value.
	 */
	public static Report scanRefusedReferences(final Adaptation pDataset)
	{
		final List<Finding> listed = new ArrayList<Finding>();
		int count = 0;
		if (pDataset == null)
		{
			return new Report(0, listed);
		}
		if (pDataset.getTable(ConnectorsPaths.CONNECTOR) != null)
		{
			final RequestResult rows = pDataset.getTable(ConnectorsPaths.CONNECTOR).createRequest().execute();
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					for (final Object[] field : new Object[][] { { ConnectorsPaths.Connector.PASSWORD, "password" },
						{ ConnectorsPaths.Connector.API_KEY, "apiKey" } })
					{
						final String reason = SecretResolvers.referenceRefusal(row.getString((com.orchestranetworks.schema.Path) field[0]));
						if (reason != null)
						{
							count++;
							if (listed.size() < ClearSecrets.MAX_LISTED)
							{
								listed.add(new Finding(row.getString(ConnectorsPaths.Connector.ID), field[1] + ": " + reason));
							}
						}
					}
				}
			}
			finally
			{
				rows.close();
			}
		}
		if (pDataset.getTable(ConnectorsPaths.CONNECTOR_PROPERTY) != null)
		{
			final RequestResult rows = pDataset.getTable(ConnectorsPaths.CONNECTOR_PROPERTY).createRequest().execute();
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					final String reason = SecretResolvers.referenceRefusal(row.getString(ConnectorsPaths.ConnectorProperty.VALUE));
					if (reason != null)
					{
						count++;
						if (listed.size() < ClearSecrets.MAX_LISTED)
						{
							listed.add(new Finding(row.getString(ConnectorsPaths.ConnectorProperty.CONNECTOR),
								"property " + row.getString(ConnectorsPaths.ConnectorProperty.KEY) + ": " + reason));
						}
					}
				}
			}
			finally
			{
				rows.close();
			}
		}
		return new Report(count, listed);
	}

	/** {@link #scan} at most once a minute per dataset: the status route is probed. */
	public static Report cachedScan(final Adaptation pDataset)
	{
		final long now = System.currentTimeMillis();
		final Object[] held = ClearSecrets.cache;
		if (held != null && held[0] == pDataset && now - ((Long) held[1]).longValue() < ClearSecrets.CACHE_MILLIS)
		{
			return (Report) held[2];
		}
		final Report report = ClearSecrets.scan(pDataset);
		ClearSecrets.cache = new Object[] { pDataset, Long.valueOf(now), report };
		return report;
	}

	/**
	 * Why a connector must be refused because of a {@code ConnectorProperty} secret held in clear, null when none is
	 * (or when clear secrets are accepted). Read from the stored rows, because by the time a configuration is built
	 * a vault reference has already been replaced by its value. The message names the property, never its value.
	 */
	public static String propertyProblem(final Adaptation pDataset, final String pConnectorId)
	{
		if (Secrets.clearSecretsAllowed() || pDataset == null || pDataset.getTable(ConnectorsPaths.CONNECTOR_PROPERTY) == null)
		{
			return null;
		}
		final Request request = pDataset.getTable(ConnectorsPaths.CONNECTOR_PROPERTY).createRequest();
		request.setXPathFilter("./connector=" + Xpath.quote(pConnectorId));
		final RequestResult rows = request.execute();
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String key = row.getString(ConnectorsPaths.ConnectorProperty.KEY);
				if (Secrets.isSecretProperty(key) && Secrets.isStoredInClear(row.getString(ConnectorsPaths.ConnectorProperty.VALUE)))
				{
					return ClearSecrets.propertyMessage(key);
				}
			}
		}
		finally
		{
			rows.close();
		}
		return null;
	}

	/** The refusal for a secret property held in clear. A property value is not encrypted by EBX: only a reference keeps it out of the repository. */
	static String propertyMessage(final String pKey)
	{
		return "property " + pKey + " holds a secret stored in clear text, which is refused because " + Secrets.ALLOW_CLEAR_PROPERTY
			+ " is not true. A property value is not encrypted by EBX: replace it with a vault:, env: or file: reference"
			+ " (an OAuth client secret can instead go in the connector's API key field, which EBX encrypts)";
	}

	/** One kernel WARN at startup naming the connections that hold a clear secret and what to do; nothing when there are none. */
	public static void logAtStartup(final Repository pRepository)
	{
		try
		{
			final Adaptation dataset = new ConnectorRepository(pRepository).dataset();
			final Report report = ClearSecrets.scan(dataset);
			if (report.count() == 0)
			{
				return;
			}
			final StringBuilder names = new StringBuilder();
			for (final Finding finding : report.listed())
			{
				names.append(names.length() == 0 ? "" : ", ").append(finding);
			}
			LoggingCategory.getKernel().warn("[connectors] " + report.count() + " secret(s) are stored in clear text: " + names
				+ (report.count() > report.listed().size() ? ", ..." : "") + ". "
				+ (Secrets.clearSecretsAllowed() ? "They are accepted because -D" + Secrets.ALLOW_CLEAR_PROPERTY + "=true is set."
					: "They are REFUSED at use (-D" + Secrets.ALLOW_CLEAR_PROPERTY + " is not true).")
				+ " Re-enter each password or API key in the Connector form so that EBX encrypts it, or replace it (and any secret property)"
				+ " with a vault:, env: or file: reference. GET /ebx-connectors/rest/v1/status lists them too.");
		}
		catch (final RuntimeException ex)
		{
			LoggingCategory.getKernel().warn("[connectors] the connections holding a clear secret could not be listed: "
				+ Redaction.redact(String.valueOf(ex.getMessage())));
		}
	}
}
