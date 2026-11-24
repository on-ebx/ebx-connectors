package com.on.ebx.connect.core;

/**
 * One field rule of a mapping. Inbound: {@code sourcePath} is a JSON path in the connector's
 * document, {@code targetPath} an EBX path relative to the record ({@code ./Group/field}).
 * Outbound: {@code sourcePath} is the EBX path and {@code targetPath} the JSON path.
 */
public final class MappingRule
{
	public enum Policy
	{
		/** Write only when the EBX field is empty (default). */
		IF_EMPTY,
		/** Always write, overwriting what is there. */
		ALWAYS,
		/** Write when empty, or when the source is at least as trusted as the one that wrote the current value; else log a conflict. */
		TRUSTED
	}

	private final int position;
	private final String sourcePath;
	private final String targetPath;
	private final String transformation;
	private final Policy policy;
	private final String comment;

	public MappingRule(final int pPosition, final String pSourcePath, final String pTargetPath,
		final String pTransformation, final Policy pPolicy, final String pComment)
	{
		this.position = pPosition;
		this.sourcePath = pSourcePath == null ? "" : pSourcePath.trim();
		this.targetPath = pTargetPath == null ? "" : pTargetPath.trim();
		this.transformation = pTransformation == null ? "" : pTransformation.trim();
		this.policy = pPolicy == null ? Policy.IF_EMPTY : pPolicy;
		this.comment = pComment;
	}

	public int getPosition()
	{
		return this.position;
	}

	public String getSourcePath()
	{
		return this.sourcePath;
	}

	public String getTargetPath()
	{
		return this.targetPath;
	}

	public String getTransformation()
	{
		return this.transformation;
	}

	public Policy getPolicy()
	{
		return this.policy;
	}

	public String getComment()
	{
		return this.comment;
	}

	@Override
	public String toString()
	{
		return this.sourcePath + " -> " + this.targetPath
				+ (this.transformation.isEmpty() ? "" : " [" + this.transformation + "]") + " " + this.policy;
	}
}
