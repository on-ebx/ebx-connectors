package com.on.ebx.connect.ebx;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.ebx.ConnectorsAccess.Right;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaExtensionsContext;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.AccessRule;
import com.orchestranetworks.service.AccessRuleForCreate;
import com.orchestranetworks.service.AccessRuleForCreateContext;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;

/**
 * Audit C-01 (tables): the Connectors rights ({@link ConnectorsAccess}) applied to the rows of the Connectors dataset
 * themselves, so that the EBX screens, Data Services and the {@code data-access} MCP follow the same model as the
 * module's own tools, routes and services, whatever the EBX permissions of the dataspace say. Without a Connectors right
 * a table's rows are hidden; with a right below the one that writes the table they are read only; the right that
 * writes the table reads and writes it (as far as the EBX permissions also allow: an access rule only restricts).
 * <p>
 * Every table has a {@link Policy}: the right that READS it and the right that WRITES it by hand.
 * <ul>
 * <li>Configuration tables (connections, their properties, categories, templates, mappings, their inputs and rules,
 * schema refresh state, governance): read with READ, written with MANAGE.</li>
 * <li>Run-time tables the engine writes during a run (log, runs, publications, metrics) and packaged content (release
 * notes, seed markers): read with READ, written by hand by an EBX administrator only. The engine is not stopped by this
 * rule: it writes them from a procedure that disables the permission checks ({@code setAllPrivileges}) or with the
 * system user's session (see the decisions in {@link #TABLES}).</li>
 * <li>Run-time tables a designer's deletion of a mapping cleans in the designer's own transaction
 * ({@code ConfigTransfer.cascade}, no privileges): checkpoints and provenance (read with READ), outbound states and
 * conflicts (read with RUN, since they carry record values, as the tools that read them ask): written with MANAGE.
 * The engine writes them with all privileges, and a conflict decision (RUN) changes the conflict row through
 * {@code ConflictResolver}, which checks the RUN right and then writes with all privileges: a RUN holder decides
 * conflicts, but cannot rewrite a conflict (its proposed value, its status) by hand.</li>
 * </ul>
 * Creation is closed field by field ({@link #register}): a creation rule on a table node puts the whole model in error,
 * and the rule of a record does not apply to a record being created. Deletion follows the record's rule (a read-only
 * row cannot be deleted). The EBX administrator and the system user always pass; so does the platform itself (no
 * session: a trigger or a startup procedure).
 * <p>
 * Two tables keep their own record rule and only receive the creation rule here: {@code AccessRight} (administrators
 * only, as before) and {@code Favorite} (each user's own rows, written by the star of the explorer with all privileges).
 */
public final class ConnectorsTableAccessRule implements AccessRule, AccessRuleForCreate
{
	/** Who may do something, from the narrowest to the widest; {@link #ADMIN} = an EBX administrator or the system user. */
	public enum Level
	{
		READ, RUN, MANAGE, ADMIN;

		public boolean covers(final Level pNeeded)
		{
			return pNeeded != null && this.ordinal() >= pNeeded.ordinal();
		}
	}

	/** What a session may do with the rows of a table; kept apart from EBX types so that the decision is testable. */
	public enum Decision
	{
		READ_WRITE, READ_ONLY, HIDDEN
	}

	/** The right that reads a table and the right that writes it by hand, and why. */
	public static final class Policy
	{
		final Level read;
		final Level write;
		/** True when the table keeps its own record rule, registered elsewhere: only the creation rule comes from here. */
		final boolean ownRecordRule;
		final String reason;

		Policy(final Level pRead, final Level pWrite, final boolean pOwnRecordRule, final String pReason)
		{
			this.read = pRead;
			this.write = pWrite;
			this.ownRecordRule = pOwnRecordRule;
			this.reason = pReason;
		}

		public Level read()
		{
			return this.read;
		}

		public Level write()
		{
			return this.write;
		}

		public boolean hasOwnRecordRule()
		{
			return this.ownRecordRule;
		}

		public String reason()
		{
			return this.reason;
		}
	}

	/**
	 * The module's settings ({@code /root/Settings}, a group of dataset-level fields, not a table): read with READ, changed
	 * by an EBX administrator only (wave 12: the default language-model connector of the in-JVM contract).
	 */
	public static final Policy SETTINGS = new Policy(Level.READ, Level.ADMIN, false, "the module's settings: read with READ, changed by an EBX administrator only");

	/** A table this class does not know (added to the model without a decision here): READ reads it, MANAGE writes it. */
	public static final Policy DEFAULT = new Policy(Level.READ, Level.MANAGE, false, "no decision: configuration by default");

	/** The decision for every table of the Connectors model (a test compares this map with the XSD). */
	static final Map<Path, Policy> TABLES;
	static
	{
		final Map<Path, Policy> tables = new LinkedHashMap<Path, Policy>();
		final String config = "configuration: a designer's (MANAGE) table";
		tables.put(ConnectorsPaths.CONNECTOR_CATEGORY, new Policy(Level.READ, Level.MANAGE, false, config));
		tables.put(ConnectorsPaths.CONNECTOR, new Policy(Level.READ, Level.MANAGE, false,
			config + "; the connection state it carries is written by the diagnostics service, which asks MANAGE"));
		tables.put(ConnectorsPaths.CONNECTOR_PROPERTY, new Policy(Level.READ, Level.MANAGE, false,
			config + "; the value of a credential row is also hidden below MANAGE (ConnectorPropertyValueAccessRule, audit C-05)"));
		tables.put(ConnectorsPaths.CONNECTOR_TEMPLATE, new Policy(Level.READ, Level.MANAGE, false,
			config + "; written by the template routes (MANAGE) and the packaged profile installer (system user)"));
		tables.put(ConnectorsPaths.MAPPING, new Policy(Level.READ, Level.MANAGE, false, config));
		tables.put(ConnectorsPaths.MAPPING_INPUT, new Policy(Level.READ, Level.MANAGE, false, config));
		tables.put(ConnectorsPaths.MAPPING_RULE, new Policy(Level.READ, Level.MANAGE, false, config));
		tables.put(ConnectorsPaths.SCHEMA_REFRESH_STATE, new Policy(Level.READ, Level.MANAGE, false,
			"the stored schema of a connection; written with the session of the scheduled task RestSchemaRefreshScheduledTask"
				+ " (no privileges), whose profile must hold MANAGE (an administrator does)"));
		tables.put(ConnectorsPaths.CONNECTOR_GOVERNANCE, new Policy(Level.READ, Level.MANAGE, false,
			config + "; no production caller writes it (EbxConnectorGovernanceStore is not wired)"));
		tables.put(ConnectorsPaths.CONNECTOR_GOVERNANCE_AUDIT, new Policy(Level.READ, Level.MANAGE, false,
			"written together with ConnectorGovernance by the same store; no production caller"));
		tables.put(ConnectorsPaths.SYNCHRONIZATION_CHECKPOINT, new Policy(Level.READ, Level.MANAGE, false,
			"run time: a feed run saves its checkpoint with all privileges (ConnectorRepository.saveCheckpoint); deleting a mapping"
				+ " deletes its checkpoint in the designer's transaction (ConfigTransfer.cascade)"));
		tables.put(ConnectorsPaths.FIELD_PROVENANCE, new Policy(Level.READ, Level.MANAGE, false,
			"run time: written with all privileges (ConnectorRepository.recordProvenance); deleting a mapping unlinks its rows in the"
				+ " designer's transaction (ConfigTransfer.cascade)"));
		tables.put(ConnectorsPaths.OUTBOUND_STATE, new Policy(Level.RUN, Level.MANAGE, false,
			"run time, carries the documents sent (record values: read with RUN, as connectors_outbound_states asks); written with all"
				+ " privileges (ConnectorRepository.markOutbound*); deleting a mapping deletes its rows in the designer's transaction"));
		tables.put(ConnectorsPaths.CONFLICT, new Policy(Level.RUN, Level.MANAGE, false,
			"run time, carries record values (read with RUN, as connectors_conflicts_list asks); written with all privileges by a run"
				+ " (ConnectorRepository.logConflicts) and by a decision (ConflictResolver, which checks RUN first); deleting a mapping"
				+ " unlinks its rows in the designer's transaction"));
		final String engine = "run time: written only by the engine, with all privileges or the system user's session; by hand,"
			+ " administrators only";
		tables.put(ConnectorsPaths.SYNC_LOG, new Policy(Level.READ, Level.ADMIN, false,
			engine + " (ConnectorRepository.logBatch, Retention with the system user); changes and recordLabel are hidden when the session"
				+ " cannot read the origin record (OriginValueAccessRule, audit C2-03)"));
		tables.put(ConnectorsPaths.EXECUTION_RUN, new Policy(Level.READ, Level.ADMIN, false,
			engine + " (ConnectorRepository run state and stop, markInterruptedRunningRuns with the system user, Retention)"));
		tables.put(ConnectorsPaths.PUBLICATION, new Policy(Level.READ, Level.ADMIN, false, engine + " (ConnectorRepository.logPublication)"));
		tables.put(ConnectorsPaths.CONNECTOR_OPERATIONAL_METRICS, new Policy(Level.READ, Level.ADMIN, false,
			engine + " (the metrics observer runs with the system user's session 'connectors-metrics')"));
		final String packaged = "packaged content written by the installer (system user, all privileges); by hand, administrators only";
		tables.put(ConnectorsPaths.RELEASE, new Policy(Level.READ, Level.ADMIN, false, packaged));
		tables.put(ConnectorsPaths.RELEASE_ITEM, new Policy(Level.READ, Level.ADMIN, false, packaged));
		tables.put(ConnectorsPaths.RELEASE_METRIC, new Policy(Level.READ, Level.ADMIN, false, packaged));
		tables.put(ConnectorsPaths.RELEASE_PASS_STATUS, new Policy(Level.READ, Level.ADMIN, false, packaged));
		tables.put(ConnectorsPaths.RELEASE_PASS_STEP, new Policy(Level.READ, Level.ADMIN, false, packaged));
		tables.put(ConnectorsPaths.OUTBOUND_QUEUE, new Policy(Level.READ, Level.ADMIN, false,
			"what a table trigger queued for the background sender (core.OutboundQueue, system session with all privileges): read with READ, written by hand by an EBX administrator only"));
		tables.put(ConnectorsPaths.SEED_MARKER, new Policy(Level.READ, Level.ADMIN, false, packaged));
		tables.put(ConnectorsPaths.ACCESS_RIGHT, new Policy(Level.READ, Level.ADMIN, true,
			"who may use the connectors: administrators only (own record rule, unchanged); creation closed here too"));
		tables.put(ConnectorsPaths.FAVORITE, new Policy(Level.READ, Level.ADMIN, true,
			"each user's own rows, written by the star with all privileges (own record rule FavoriteAccessRule); creation by hand closed"));
		TABLES = Collections.unmodifiableMap(tables);
	}

	static final UserMessage READ_ONLY = UserMessage.createInfo("Connectors: your right does not cover changing this table.");
	static final UserMessage HIDDEN = UserMessage.createInfo("Connectors: this table needs a Connectors right you do not hold.");

	private final Policy policy;

	ConnectorsTableAccessRule(final Policy pPolicy)
	{
		this.policy = pPolicy;
	}

	/** The decision for a table, {@link #DEFAULT} when the table has none. */
	public static Policy policy(final Path pTable)
	{
		final Policy policy = pTable == null ? null : ConnectorsTableAccessRule.TABLES.get(pTable);
		return policy == null ? ConnectorsTableAccessRule.DEFAULT : policy;
	}

	/** The level a Connectors right gives (null: no right). */
	public static Level levelOf(final Right pRight)
	{
		return pRight == null ? null : pRight == Right.MANAGE ? Level.MANAGE : pRight == Right.RUN ? Level.RUN : Level.READ;
	}

	/** The level of a session: ADMIN for an administrator or the system user, else the level of its Connectors right. */
	static Level levelOf(final Session pSession)
	{
		try
		{
			if (pSession.isSystemUser() || pSession.isUserInRole(Profile.ADMINISTRATOR))
			{
				return Level.ADMIN;
			}
		}
		catch (final RuntimeException ex)
		{
			return null; // a session that cannot answer gets nothing
		}
		return ConnectorsTableAccessRule.levelOf(ConnectorsAccess.granted(pSession));
	}

	/** Pure: what a holder of {@code pGranted} (null = no right) may do with a table of policy {@code pPolicy}. */
	public static Decision decide(final Level pGranted, final Policy pPolicy)
	{
		if (pGranted != null && pGranted.covers(pPolicy.write))
		{
			return Decision.READ_WRITE;
		}
		return pGranted != null && pGranted.covers(pPolicy.read) ? Decision.READ_ONLY : Decision.HIDDEN;
	}

	/** The decision for one session; no session is the platform itself (a trigger context, a startup procedure). */
	Decision decide(final Session pSession)
	{
		return pSession == null ? Decision.READ_WRITE : ConnectorsTableAccessRule.decide(ConnectorsTableAccessRule.levelOf(pSession), this.policy);
	}

	private static AccessPermission permission(final Decision pDecision)
	{
		switch (pDecision)
		{
			case READ_WRITE:
				return AccessPermission.getReadWrite();
			case READ_ONLY:
				return AccessPermission.getReadOnly(ConnectorsTableAccessRule.READ_ONLY);
			default:
				return AccessPermission.getHidden(ConnectorsTableAccessRule.HIDDEN);
		}
	}

	@Override
	public AccessPermission getPermission(final Adaptation pRecord, final Session pSession, final SchemaNode pNode)
	{
		return ConnectorsTableAccessRule.permission(this.decide(pSession));
	}

	@Override
	public AccessPermission getPermission(final AccessRuleForCreateContext pContext)
	{
		return ConnectorsTableAccessRule.permission(this.decide(pContext == null ? null : pContext.getSession()));
	}

	/**
	 * Declares the rule on every table of the model: on its records (unless the table keeps its own record rule) and,
	 * for creation, on each terminal field of the record (a creation rule on the table node itself is a compilation
	 * error of the whole model). To be called once from the schema extensions.
	 */
	static void register(final SchemaExtensionsContext pContext)
	{
		ConnectorsTableAccessRule.register(pContext, pContext.getSchemaNode());
		// audit C2-03: the values of a record that the run-time tables keep follow the permissions of the origin table
		OriginValueAccessRule.register(pContext);
	}

	private static void register(final SchemaExtensionsContext pContext, final SchemaNode pNode)
	{
		if (pNode.isTableNode())
		{
			final Path table = pNode.getPathInSchema();
			final Policy policy = ConnectorsTableAccessRule.policy(table);
			final ConnectorsTableAccessRule rule = new ConnectorsTableAccessRule(policy);
			if (!policy.ownRecordRule)
			{
				pContext.setAccessRuleOnOccurrence(table, rule);
			}
			ConnectorsTableAccessRule.closeCreation(pContext, pNode.getTableOccurrenceRootNode(), rule);
			return;
		}
		for (final SchemaNode child : pNode.getNodeChildren())
		{
			ConnectorsTableAccessRule.register(pContext, child);
		}
	}

	private static void closeCreation(final SchemaExtensionsContext pContext, final SchemaNode pNode, final ConnectorsTableAccessRule pRule)
	{
		for (final SchemaNode child : pNode.getNodeChildren())
		{
			if (child.isAssociationNode() || child.isSelectNode())
			{
				continue; // not a value of the record
			}
			if (child.isTerminalValue())
			{
				pContext.setAccessRuleForCreateOnNode(child.getPathInSchema(), pRule);
			}
			else
			{
				ConnectorsTableAccessRule.closeCreation(pContext, child, pRule);
			}
		}
	}
}
