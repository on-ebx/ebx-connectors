package com.on.ebx.connect.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.spi.ConnectorException;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;

/**
 * The record lookups of a feed (its key predicate, the placeholders it reads from an item) and the log the
 * mapping engine writes to. Kept out of {@link ConnectorService} on purpose: the engine classes
 * ({@link FeedKeyLookup}, {@link ChildRowPreload}...) call these, and {@code build-mapping.sh} extracts the
 * engine as {@code on-ebx-mapping.jar} for ebx-mdm-factory without the rest of the module. A call from
 * the engine into {@code ConnectorService} would pull every connector into that jar.
 */
final class FeedLookups
{
	private FeedLookups()
	{
	}

	static LoggingCategory log()
	{
		return LoggingCategory.getKernel();
	}

	/**
	 * First record of a feed's key predicate. EBX's own messages ("An unknown predicate operator has
	 * been detected: =.") name neither the predicate nor the table, so they are wrapped with both.
	 */
	static Adaptation locate(final Session pSession, final AdaptationTable pTable, final String pPredicate) throws ConnectorException
	{
		try
		{
			return MappingEngine.first(pSession, pTable, pPredicate);
		}
		catch (final RuntimeException ex)
		{
			final String reason = ex.getMessage() == null ? ex.toString() : ex.getMessage();
			throw new ConnectorException(FeedLookups.predicateFailure(pTable.getTablePath().format(), pPredicate, reason), ex);
		}
	}

	static String predicateFailure(final String pTablePath, final String pPredicate, final String pReason)
	{
		final StringBuilder message = new StringBuilder("Key predicate rejected by EBX on ").append(pTablePath).append(": ")
			.append(pPredicate).append(" (").append(pReason).append(')');
		if (pReason != null && pReason.contains("unknown predicate operator"))
		{
			message.append(". A date, time or dateTime field is compared with date-equal(./field,'2026-01-31'), not '='.");
		}
		return message.toString();
	}

	/** What a placeholder stands for in a key predicate: the item's value at that path, empty when there is none. */
	static String placeholderText(final JsonNode pItem, final String pExpression)
	{
		final JsonNode value = Json.at(pItem, pExpression.trim());
		return value == null ? "" : (value.isValueNode() ? value.asText() : Json.write(value));
	}
}
