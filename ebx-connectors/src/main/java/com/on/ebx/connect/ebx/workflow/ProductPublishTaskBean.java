package com.on.ebx.connect.ebx.workflow;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.ProductPublisher;
import com.on.ebx.connect.ebx.rest.Mappings;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.workflow.ScriptTaskBean;
import com.orchestranetworks.workflow.ScriptTaskBeanContext;

/**
 * Workflow script task publishing a data product as a whole to a {@code TablePublisher} connector
 * (Snowflake, v1 scope): one atomic commit for every table of one or more root outbound mappings
 * ({@code core.ProductPublisher}, {@code SPEC-product-publication.md} §2.3/§2.4). {@code mappings} is
 * a comma-separated list of ids or labels, all on the same connector. {@code failOnError=true}
 * (default) makes the step fail when the report status is {@code ERROR} or {@code PARTIAL} (a table cut at the row cap); the full report always stays
 * readable from the {@code Publication} table by its {@code runId} (dry runs included), never passed
 * as a workflow variable. Declared in module.xml next to {@link ConnectorScriptTaskBean}.
 */
public final class ProductPublishTaskBean extends ScriptTaskBean
{
	private String mappings;
	private String schema;
	private String product;
	private String contractVersion = "1";
	private String dryRun = "false";
	private String recreate = "false";
	private String maxRecords = "0";
	private String description = "";
	private String owner = "";
	private String domain = "";
	private String title = "";
	private String failOnError = "true";
	private String runId;
	private String status;
	private String message;
	private String rowCount;

	@Override
	public void executeScript(final ScriptTaskBeanContext pContext) throws OperationException
	{
		com.on.ebx.connect.ebx.ConnectorsInstaller.ensure(pContext.getRepository(), pContext.getSession());
		if (this.mappings == null || this.mappings.trim().isEmpty())
		{
			throw OperationException.createError("mappings is required");
		}
		final ConnectorRepository repo = ConnectorService.get().repository(pContext.getRepository());
		final List<Integer> ids = new ArrayList<Integer>();
		for (final String token : this.mappings.split(","))
		{
			final String trimmed = token.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			final MappingDefinition m = Mappings.mappingNamed(repo, trimmed);
			if (m == null)
			{
				throw OperationException.createError("No mapping '" + trimmed + "'");
			}
			ids.add(Integer.valueOf(m.getId()));
		}
		if (ids.isEmpty())
		{
			throw OperationException.createError("mappings is required");
		}
		final ProductPublisher.Request request = new ProductPublisher.Request();
		request.setMappings(ids);
		request.setSchema(this.schema);
		request.setProduct(this.product);
		request.setContractVersion(this.contractVersion);
		request.setDryRun("true".equalsIgnoreCase(this.dryRun));
		request.setRecreate("true".equalsIgnoreCase(this.recreate));
		request.setMax(ProductPublishTaskBean.toInt(this.maxRecords, 0));
		request.setDescription(this.description);
		request.setOwner(this.owner);
		request.setDomain(this.domain);
		request.setTitle(this.title);
		final ProductPublisher.Report report = new ProductPublisher(pContext.getSession(), repo).publish(request);
		this.runId = report.getRunId();
		this.status = report.getStatus();
		// a workflow variable holds 180 characters at most (the instance record fails validation beyond);
		// the full report stays readable from the Publication table by runId (CLAUDE.md §7)
		final String text = report.getMessage();
		this.message = text == null || text.length() <= 180 ? text : text.substring(0, 177) + "...";
		this.rowCount = String.valueOf(report.getRowCount());
		if ((report.isError() || report.isPartial()) && !"false".equalsIgnoreCase(this.failOnError))
		{
			// PARTIAL: committed, but a table was cut at the row cap and the target holds the cut set (audit C-17)
			throw OperationException.createError("Publication " + report.getRunId() + (report.isPartial() ? " is partial: " : " failed: ") + report.getMessage());
		}
	}

	private static int toInt(final String pValue, final int pDefault)
	{
		try
		{
			return pValue == null || pValue.trim().isEmpty() ? pDefault : Integer.parseInt(pValue.trim());
		}
		catch (final NumberFormatException ex)
		{
			return pDefault;
		}
	}

	public String getMappings()
	{
		return this.mappings;
	}

	public void setMappings(final String pMappings)
	{
		this.mappings = pMappings;
	}

	public String getSchema()
	{
		return this.schema;
	}

	public void setSchema(final String pSchema)
	{
		this.schema = pSchema;
	}

	public String getProduct()
	{
		return this.product;
	}

	public void setProduct(final String pProduct)
	{
		this.product = pProduct;
	}

	public String getContractVersion()
	{
		return this.contractVersion;
	}

	public void setContractVersion(final String pContractVersion)
	{
		this.contractVersion = pContractVersion;
	}

	public String getDryRun()
	{
		return this.dryRun;
	}

	public void setDryRun(final String pDryRun)
	{
		this.dryRun = pDryRun;
	}

	public String getRecreate()
	{
		return this.recreate;
	}

	public void setRecreate(final String pRecreate)
	{
		this.recreate = pRecreate;
	}

	public String getDescription()
	{
		return this.description;
	}

	public void setDescription(final String pDescription)
	{
		this.description = pDescription;
	}

	public String getOwner()
	{
		return this.owner;
	}

	public void setOwner(final String pOwner)
	{
		this.owner = pOwner;
	}

	public String getTitle()
	{
		return this.title;
	}

	public void setTitle(final String pTitle)
	{
		this.title = pTitle;
	}

	public String getDomain()
	{
		return this.domain;
	}

	public void setDomain(final String pDomain)
	{
		this.domain = pDomain;
	}

	public String getMaxRecords()
	{
		return this.maxRecords;
	}

	public void setMaxRecords(final String pMaxRecords)
	{
		this.maxRecords = pMaxRecords;
	}

	public String getFailOnError()
	{
		return this.failOnError;
	}

	public void setFailOnError(final String pFailOnError)
	{
		this.failOnError = pFailOnError;
	}

	public String getRunId()
	{
		return this.runId;
	}

	public void setRunId(final String pRunId)
	{
		this.runId = pRunId;
	}

	public String getStatus()
	{
		return this.status;
	}

	public void setStatus(final String pStatus)
	{
		this.status = pStatus;
	}

	public String getMessage()
	{
		return this.message;
	}

	public void setMessage(final String pMessage)
	{
		this.message = pMessage;
	}

	public String getRowCount()
	{
		return this.rowCount;
	}

	public void setRowCount(final String pRowCount)
	{
		this.rowCount = pRowCount;
	}
}
