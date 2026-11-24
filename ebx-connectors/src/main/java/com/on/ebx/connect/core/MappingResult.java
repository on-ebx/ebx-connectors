package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;

/** What applying a mapping did to one record. */
public final class MappingResult
{
	public static final class Change
	{
		private final String path;
		private final String oldValue;
		private final String newValue;
		private final String source;

		public Change(final String pPath, final String pOldValue, final String pNewValue)
		{
			this(pPath, pOldValue, pNewValue, null);
		}

		/** Gartner lot A: {@code pSource} = where the value was read (document, page, snippet), kept in FieldProvenance.source. */
		public Change(final String pPath, final String pOldValue, final String pNewValue, final String pSource)
		{
			this.path = pPath;
			this.oldValue = pOldValue;
			this.newValue = pNewValue;
			this.source = pSource;
		}

		/** Where the value was read, null when the connector does not say (every connector but the document feed). */
		public String getSource()
		{
			return this.source;
		}

		public String getPath()
		{
			return this.path;
		}

		public String getOldValue()
		{
			return this.oldValue;
		}

		public String getNewValue()
		{
			return this.newValue;
		}

		@Override
		public String toString()
		{
			return this.path + ": " + (this.oldValue == null ? "" : this.oldValue) + " -> " + this.newValue;
		}
	}

	private final List<Change> changes = new ArrayList<Change>();
	private final List<String> errors = new ArrayList<String>();
	private int skipped;

	/** A more trusted value stood in the way of a TRUSTED rule. */
	public static final class Conflict
	{
		public final String path;
		public final String currentValue;
		public final String proposedValue;
		public final String currentConnector;
		public final int currentTrust;
		/** Gartner lot A: why this is a proposal (confidence, document, page, snippet); null for a trust conflict. */
		public final String comment;

		public Conflict(final String pPath, final String pCurrentValue, final String pProposedValue, final String pCurrentConnector, final int pCurrentTrust)
		{
			this(pPath, pCurrentValue, pProposedValue, pCurrentConnector, pCurrentTrust, null);
		}

		public Conflict(final String pPath, final String pCurrentValue, final String pProposedValue, final String pCurrentConnector, final int pCurrentTrust,
			final String pComment)
		{
			this.comment = pComment;
			this.path = pPath;
			this.currentValue = pCurrentValue;
			this.proposedValue = pProposedValue;
			this.currentConnector = pCurrentConnector;
			this.currentTrust = pCurrentTrust;
		}

		@Override
		public String toString()
		{
			return this.path + ": " + this.currentValue + " (" + (this.currentConnector == null ? "manual" : this.currentConnector) + ", trust " + this.currentTrust
					+ ") kept, proposed " + this.proposedValue;
		}
	}

	private final List<Conflict> conflicts = new java.util.ArrayList<Conflict>();

	public List<Conflict> getConflicts()
	{
		return this.conflicts;
	}

	public void addConflict(final String pPath, final String pCurrent, final String pProposed, final String pCurrentConnector, final int pCurrentTrust)
	{
		this.conflicts.add(new Conflict(pPath, pCurrent, pProposed, pCurrentConnector, pCurrentTrust));
	}

	public void addConflict(final String pPath, final String pCurrent, final String pProposed, final String pCurrentConnector, final int pCurrentTrust,
		final String pComment)
	{
		this.conflicts.add(new Conflict(pPath, pCurrent, pProposed, pCurrentConnector, pCurrentTrust, pComment));
	}

	public List<Change> getChanges()
	{
		return this.changes;
	}

	public List<String> getErrors()
	{
		return this.errors;
	}

	public int getSkipped()
	{
		return this.skipped;
	}

	void addChange(final String pPath, final String pOld, final String pNew)
	{
		this.changes.add(new Change(pPath, pOld, pNew));
	}

	void addChange(final String pPath, final String pOld, final String pNew, final String pSource)
	{
		this.changes.add(new Change(pPath, pOld, pNew, pSource));
	}

	void addError(final String pError)
	{
		this.errors.add(pError);
	}

	void skip()
	{
		this.skipped++;
	}

	public boolean hasChanges()
	{
		return !this.changes.isEmpty();
	}

	public String summary()
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(this.changes.size()).append(" change(s), ").append(this.skipped).append(" skipped");
		if (!this.errors.isEmpty())
		{
			sb.append(", ").append(this.errors.size()).append(" error(s)");
		}
		final String base = sb.toString();
			return this.conflicts.isEmpty() ? base : base + ", " + this.conflicts.size() + " conflict(s)";
	}
}
