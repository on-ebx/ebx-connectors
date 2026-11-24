package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.List;

/** Validation and backward-compatible sequential execution for the batch SPI. */
public final class ConnectorBatchExecutor
{
	private ConnectorBatchExecutor() { }

	public static BatchResult sequential(final Connector pConnector, final ConnectorContext pContext,
		final BatchRequest pRequest) throws ConnectorException
	{
		validate(pConnector, pRequest);
		final List<BatchResult.ItemResult> results = new ArrayList<BatchResult.ItemResult>();
		for (final BatchRequest.Item item : pRequest.getItems())
		{
			try { results.add(BatchResult.ItemResult.success(item.getId(), 200, pConnector.execute(pContext, item.getOperation(), item.getInputs(), item.getPayload()))); }
			catch (final ConnectorException ex) { results.add(BatchResult.ItemResult.error(item.getId(), ex.getStatus(), ex.getMessage())); }
			catch (final RuntimeException ex) { results.add(BatchResult.ItemResult.error(item.getId(), 0, ex.getMessage())); }
		}
		return new BatchResult(results, false);
	}

	public static void validate(final Connector pConnector, final BatchRequest pRequest) throws ConnectorException
	{
		if (pRequest == null) throw new ConnectorException("A batch request is required");
		int writes = 0, bytes = 0;
		for (final BatchRequest.Item item : pRequest.getItems())
		{
			final Operation operation = operation(pConnector, item.getOperation());
			if (operation == null) throw new ConnectorException("Unknown batch operation '" + item.getOperation() + "'");
			for (final String required : operation.getRequiredInputs())
			{
				final String value = item.getInputs().get(required);
				if (value == null || value.trim().isEmpty()) throw new ConnectorException("Batch item '" + item.getId() + "' requires input '" + required + "'");
			}
			if (operation.getKind() == Operation.Kind.WRITE)
			{
				writes++;
				if (item.getPayload() == null) throw new ConnectorException("Batch write item '" + item.getId() + "' requires a payload");
			}
			bytes += utf8(item.getOperation());
			for (final java.util.Map.Entry<String, String> input : item.getInputs().entrySet())
				bytes += utf8(input.getKey()) + utf8(input.getValue());
			if (item.getPayload() != null) bytes += utf8(item.getPayload().toString());
			if (bytes > pRequest.getMaximumBytes()) throw new ConnectorException("Batch request exceeds byte limit " + pRequest.getMaximumBytes());
		}
		if (writes > BatchRequest.HARD_MAX_WRITE_ITEMS) throw new ConnectorException("Batch cannot exceed " + BatchRequest.HARD_MAX_WRITE_ITEMS + " write items");
	}

	public static Operation operation(final Connector pConnector, final String pName)
	{
		for (final Operation operation : pConnector.getOperations()) if (operation != null && operation.getName().equals(pName)) return operation;
		return null;
	}

	private static int utf8(final String pValue)
	{
		try { return pValue == null ? 0 : pValue.getBytes("UTF-8").length; }
		catch (final java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
	}
}
