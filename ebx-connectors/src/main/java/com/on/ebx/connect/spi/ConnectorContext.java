package com.on.ebx.connect.spi;

import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;

/** What a connector may need from EBX while executing: the session, the repository, a logger. */
public final class ConnectorContext
{
	private final Session session;
	private final Repository repository;
	private final LoggingCategory log;
	private final Adaptation dataset;

	public ConnectorContext(final Session pSession, final Repository pRepository,
		final LoggingCategory pLog, final Adaptation pDataset)
	{
		this.session = pSession;
		this.repository = pRepository;
		this.log = pLog;
		this.dataset = pDataset;
	}

	public Session getSession()
	{
		return this.session;
	}

	public Repository getRepository()
	{
		return this.repository;
	}

	/** Never null: falls back to the kernel category. */
	public LoggingCategory getLog()
	{
		return this.log == null ? LoggingCategory.getKernel() : this.log;
	}

	/** The dataset of the record being synchronised, or null for a raw call. */
	public Adaptation getDataset()
	{
		return this.dataset;
	}
}
