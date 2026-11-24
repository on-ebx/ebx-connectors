package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** Ordered, normalized outcome of a connector batch. One failed item does not hide its peers. */
public final class BatchResult
{
	private final List<ItemResult> items;
	private final boolean nativeBatch;

	public BatchResult(final List<ItemResult> pItems, final boolean pNativeBatch)
	{
		this.items = Collections.unmodifiableList(new ArrayList<ItemResult>(pItems == null ? Collections.<ItemResult>emptyList() : pItems));
		this.nativeBatch = pNativeBatch;
	}

	public List<ItemResult> getItems() { return this.items; }
	public boolean isNativeBatch() { return this.nativeBatch; }
	public boolean isSuccess()
	{
		for (final ItemResult item : this.items) if (!item.isSuccess()) return false;
		return true;
	}

	public static final class ItemResult
	{
		private final String id;
		private final boolean success;
		private final int status;
		private final JsonNode document;
		private final String error;

		private ItemResult(final String pId, final boolean pSuccess, final int pStatus, final JsonNode pDocument, final String pError)
		{
			id = pId;
			success = pSuccess;
			status = pStatus;
			document = pDocument;
			error = pError;
		}
		public static ItemResult success(final String pId, final int pStatus, final JsonNode pDocument) { return new ItemResult(pId, true, pStatus, pDocument, null); }
		public static ItemResult error(final String pId, final int pStatus, final String pError) { return new ItemResult(pId, false, pStatus, null, pError == null ? "Connector operation failed" : pError); }
		public String getId() { return id; }
		public boolean isSuccess() { return success; }
		public int getStatus() { return status; }
		public JsonNode getDocument() { return document; }
		public String getError() { return error; }
	}
}
