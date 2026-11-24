package com.on.ebx.connect.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.Session;

/** What a transformation may look at: the dataset (for {@code fk(...)} checks) and the record. */
public final class TransformationContext
{
	private final Adaptation dataset;
	private final Adaptation record;
	private final Session session;
	private TrustOracle oracle;
	private int sourceTrust = -1;
	private String sourceConnector;
	private GoldenRecords goldenRecords;
	private JsonNode documentRoot;
	private TransformationRunCache cache;

	public TransformationContext(final Adaptation pDataset, final Adaptation pRecord, final Session pSession)
	{
		this.dataset = pDataset;
		this.record = pRecord;
		this.session = pSession;
	}

	public Adaptation getDataset()
	{
		return this.dataset;
	}

	public Adaptation getRecord()
	{
		return this.record;
	}

	public Session getSession()
	{
		return this.session;
	}

	/** Set by the service before a mapping runs: the source's trust and where to read provenance. */
	public void setTrust(final TrustOracle pOracle, final int pSourceTrust, final String pSourceConnector)
	{
		this.oracle = pOracle;
		this.sourceTrust = pSourceTrust;
		this.sourceConnector = pSourceConnector;
	}

	public TrustOracle getOracle()
	{
		return this.oracle;
	}

	public int getSourceTrust()
	{
		return this.sourceTrust;
	}

	public String getSourceConnector()
	{
		return this.sourceConnector;
	}

	/** Behind {@code golden(...)}/{@code mdmState(...)} (Transformations): {@link GoldenRecords#get()}
	 * unless {@link #setGoldenRecords} injected a fake, e.g. in a test with no add-on or repository. */
	GoldenRecords getGoldenRecords()
	{
		return this.goldenRecords == null ? GoldenRecords.get() : this.goldenRecords;
	}

	/** Test seam: substitutes the Match and Merge lookup behind {@code golden(...)}/{@code mdmState(...)}. */
	void setGoldenRecords(final GoldenRecords pGoldenRecords)
	{
		this.goldenRecords = pGoldenRecords;
	}

	/** Set by {@link MappingEngine#compute} before the rules of a document (or child row) run: behind {@code concat(...)}. */
	public JsonNode getDocumentRoot()
	{
		return this.documentRoot;
	}

	public void setDocumentRoot(final JsonNode pDocumentRoot)
	{
		this.documentRoot = pDocumentRoot;
	}

	/**
	 * Volume lot 5, task 4: the per-run cache ({@code table()}/pipeline/regex/{@code lookup(...)}
	 * results) shared by every {@code TransformationContext} of one {@code ConnectorService.importDocument}
	 * run. {@code null} outside a cached run path (a single record sync, a test): every cache-aware call
	 * site treats {@code null} as "no cache" and falls back to computing directly, exactly as before this
	 * cache existed.
	 */
	TransformationRunCache getCache()
	{
		return this.cache;
	}

	void setCache(final TransformationRunCache pCache)
	{
		this.cache = pCache;
	}
}
