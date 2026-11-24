package com.on.ebx.connect.spi;

/** One non-persistent field correspondence suggested by a connector. */
public final class DraftMappingField
{
	private final String sourcePath;
	private final String targetPath;
	private final double confidence;

	public DraftMappingField(final String pSourcePath, final String pTargetPath,
		final double pConfidence)
	{
		this.sourcePath = pSourcePath == null ? "" : pSourcePath.trim();
		this.targetPath = pTargetPath == null ? "" : pTargetPath.trim();
		this.confidence = Math.max(0.0d, Math.min(1.0d, pConfidence));
	}

	public String getSourcePath() { return this.sourcePath; }
	public String getTargetPath() { return this.targetPath; }
	public double getConfidence() { return this.confidence; }
}
