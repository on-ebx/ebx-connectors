package com.on.ebx.connect.spi;

/** Immutable description of a connector's paging convention. */
public final class PaginationDescriptor
{
	public enum Strategy { NONE, OFFSET, PAGE_NUMBER, CURSOR, LINK, TOKEN }

	private final Strategy strategy;
	private final int defaultPageSize;
	private final int maximumPageSize;
	private final String cursorProperty;

	public PaginationDescriptor(final Strategy pStrategy, final int pDefaultPageSize,
		final int pMaximumPageSize, final String pCursorProperty)
	{
		this.strategy = pStrategy == null ? Strategy.NONE : pStrategy;
		if (pDefaultPageSize < 0 || pMaximumPageSize < 0
			|| (pMaximumPageSize > 0 && pDefaultPageSize > pMaximumPageSize))
		{
			throw new IllegalArgumentException("Invalid pagination limits");
		}
		this.defaultPageSize = pDefaultPageSize;
		this.maximumPageSize = pMaximumPageSize;
		this.cursorProperty = pCursorProperty == null ? null : pCursorProperty.trim();
	}

	public static PaginationDescriptor none() { return new PaginationDescriptor(Strategy.NONE, 0, 0, null); }
	public Strategy getStrategy() { return this.strategy; }
	public int getDefaultPageSize() { return this.defaultPageSize; }
	public int getMaximumPageSize() { return this.maximumPageSize; }
	public String getCursorProperty() { return this.cursorProperty; }
}
