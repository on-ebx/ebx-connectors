package com.on.ebx.connect.spi;

/** One field of an external document, as shown in the mapping designer. */
public final class FieldInfo
{
	private final String path;
	private final String label;
	private final String type;
	private final String sample;

	public FieldInfo(final String pPath, final String pLabel, final String pType, final String pSample)
	{
		this.path = pPath;
		this.label = pLabel == null ? pPath : pLabel;
		this.type = pType == null ? "" : pType;
		this.sample = pSample;
	}

	public static FieldInfo of(final String pPath, final String pLabel, final String pType)
	{
		return new FieldInfo(pPath, pLabel, pType, null);
	}

	/** JSON path relative to the mapping's response root (inbound) or to the payload root (outbound). */
	public String getPath()
	{
		return this.path;
	}

	public String getLabel()
	{
		return this.label;
	}

	public String getType()
	{
		return this.type;
	}

	public String getSample()
	{
		return this.sample;
	}

	public FieldInfo withSample(final String pSample)
	{
		return new FieldInfo(this.path, this.label, this.type, pSample);
	}
}
