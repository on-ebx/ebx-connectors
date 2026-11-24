package com.on.ebx.connect.core;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.addon.mame.common.util.metadata.MetadataHelper;
import com.orchestranetworks.addon.mame.model.State;
import com.orchestranetworks.addon.mame.schema.path.LinkedFieldPaths;
import com.orchestranetworks.schema.Path;

/**
 * The only class in this module referencing {@code com.orchestranetworks.addon.mame.*}: every other class reaches Match and Merge only through
 * {@link GoldenRecords}. Constructing this class touches the add-on immediately (rather than on the
 * first record it is asked about), so that on an instance without {@code ebx-addons.jar} on the
 * classpath the failure happens once, in {@link GoldenRecords.Holder}, as a {@link LinkageError} the
 * holder catches to fall back to a no-op implementation — never on the first record lookup.
 */
final class MameGoldenRecords implements GoldenRecords
{
	/** Failures in a row, on a table where the add-on never answered, before the table is held unmanaged. */
	private static final int UNMANAGED_AFTER = 25;

	/** dataset adaptation name + '#' + table path -> the table is not managed by Match and Merge:
	 * remembered so the add-on's exception is paid a few times per table, not once per row. */
	private final Set<String> unmanagedTables = ConcurrentHashMap.newKeySet();
	/** Tables where the add-on answered at least once: never held unmanaged afterwards. One record the
	 * add-on fails on (e.g. created by a feed, without Match and Merge metadata) must not let the MERGED
	 * records that follow it through. */
	private final Set<String> managedTables = ConcurrentHashMap.newKeySet();
	private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<String, AtomicInteger>();

	MameGoldenRecords()
	{
		// Forces classloading of the add-on now: with ebx-addons.jar absent this throws
		// NoClassDefFoundError right here, not lazily on the first real record lookup.
		State.UNSET.name();
	}

	@Override
	public String state(final Adaptation pRecord)
	{
		if (pRecord == null)
		{
			return GoldenRecords.UNMANAGED;
		}
		final String tableKey = MameGoldenRecords.tableKey(pRecord);
		if (this.unmanagedTables.contains(tableKey))
		{
			return GoldenRecords.UNMANAGED;
		}
		try
		{
			final State state = MetadataHelper.getState(pRecord);
			this.managedTables.add(tableKey);
			return state == null ? GoldenRecords.UNMANAGED : state.name();
		}
		catch (final Exception | LinkageError ex)
		{
			// InvalidParameterException (checked) and PathAccessException (unchecked) are declared; what the
			// add-on answers on a table it does not manage is not documented. A failure means "not managed"
			// for this record; the whole table only once the add-on failed UNMANAGED_AFTER times on it
			// without ever answering.
			if (!this.managedTables.contains(tableKey)
				&& this.failures.computeIfAbsent(tableKey, k -> new AtomicInteger()).incrementAndGet() >= MameGoldenRecords.UNMANAGED_AFTER)
			{
				this.unmanagedTables.add(tableKey);
			}
			return GoldenRecords.UNMANAGED;
		}
	}

	/**
	 * The state is the {@code Linked_Field_State} field of the business record itself (javap on
	 * {@code MetadataHelper.getState}: {@code Adaptation.get_int}), a plain read of the record the caller already
	 * holds, no lookup in the metadataset. Checked once per table that the field exists; a table where it cannot be
	 * confirmed keeps the guarded per-record {@link #state}, so a record is never silently taken for unmanaged.
	 */
	@Override
	public StateReader stateReaderFor(final AdaptationTable pTable)
	{
		try
		{
			final Path field = Path.parse("." + LinkedFieldPaths._BusinessTable._Linked_Field_State.format());
			if (pTable.getTableOccurrenceRootNode().getNode(field) != null)
			{
				return new StateReader()
				{
					@Override
					public String state(final Adaptation pRecord)
					{
						try
						{
							final State state = MetadataHelper.getState(pRecord);
							return state == null ? GoldenRecords.UNMANAGED : state.name();
						}
						catch (final Exception | LinkageError ex)
						{
							return MameGoldenRecords.this.state(pRecord);
						}
					}
				};
			}
		}
		catch (final RuntimeException | LinkageError ex)
		{
			// cannot be confirmed: the per-record path below decides
		}
		return this::state;
	}

	@Override
	public Adaptation golden(final Adaptation pRecord)
	{
		if (pRecord == null)
		{
			return null;
		}
		return GoldenRecords.followMerge(pRecord, this::state, this::targetOf);
	}

	/** {@code MetadataHelper.getTargetRecord}, or null on any failure -- {@link GoldenRecords#followMerge}
	 * treats a null target as "stop, return the last node reached", which is exactly the contract here. */
	private Adaptation targetOf(final Adaptation pRecord)
	{
		try
		{
			return MetadataHelper.getTargetRecord(pRecord);
		}
		catch (final Exception | LinkageError ex)
		{
			return null;
		}
	}

	/**
	 * Real-time mastering: the add-on's public API for one table ({@code OperationsFactory}, javap on the 6.3.0 RC
	 * {@code ebx-addons.jar}). A table without a Match and Merge configuration ({@code NotFoundException}) or an add-on
	 * without these methods ({@code LinkageError}) gives null.
	 */
	@Override
	public CdcMatching.Engine matchingEngine(final com.orchestranetworks.service.Session pSession, final AdaptationTable pTable)
	{
		if (pSession == null || pTable == null)
			return null;
		try
		{
			com.orchestranetworks.addon.mame.service.OperationsFactory.getConfigurationOperations().getTableConfiguration(pTable);
		}
		catch (final Exception | LinkageError ex)
		{
			return null;
		}
		return new CdcMatching.Engine()
		{
			@Override
			public boolean isPaused() throws Exception
			{
				return com.orchestranetworks.addon.mame.service.OperationsFactory.getConfigurationOperations().getTableConfiguration(pTable).isPauseMatchingTrigger();
			}

			@Override
			public void pause() throws Exception
			{
				com.orchestranetworks.addon.mame.service.OperationsFactory.getConfigurationOperations().pauseMatchingTrigger(pTable);
			}

			@Override
			public void resume() throws Exception
			{
				com.orchestranetworks.addon.mame.service.OperationsFactory.getConfigurationOperations().resumeMatchingTrigger(pTable);
			}

			@Override
			public String defaultPolicy() throws Exception
			{
				final com.orchestranetworks.addon.mame.service.configuration.TableMatchingPolicy policy = com.orchestranetworks.addon.mame.service.OperationsFactory
					.getConfigurationOperations().getTableConfiguration(pTable).getDefaultMatchingPolicy();
				return policy == null ? null : policy.getPolicyCode();
			}

			@Override
			public CdcMatching.Process match(final String pXPathFilter, final String pPolicy) throws Exception
			{
				final com.onwbp.adaptation.Request request = pTable.createRequest();
				request.setSession(pSession);
				request.setXPathFilter(pXPathFilter);
				final com.orchestranetworks.addon.mame.service.matching.MatchingProcess process = com.orchestranetworks.addon.mame.service.OperationsFactory
					.getMatchingOperations(pSession).matchRecords(pTable, request, pPolicy);
				return new CdcMatching.Process()
				{
					@Override
					public String status()
					{
						// never getDetail(): it throws once the process has ended (rc2..rc4)
						final com.orchestranetworks.addon.mame.common.process.ProcessStatus status = process.getMatchingStatus();
						return status == null ? "INITIALIZING" : status.name();
					}

					@Override
					public void cancel()
					{
						process.cancel();
					}
				};
			}

			@Override
			public CdcMatching.Tally tally(final java.util.Collection<String> pPredicates) throws Exception
			{
				final CdcMatching.Tally tally = new CdcMatching.Tally();
				final Path suspects = Path.parse("." + LinkedFieldPaths._BusinessTable._Linked_Field_HasSuspects.format());
				final boolean hasSuspectsField = pTable.getTableOccurrenceRootNode().getNode(suspects) != null;
				final java.util.List<String> all = new java.util.ArrayList<String>(pPredicates);
				for (int from = 0; from < all.size(); from += 200)
				{
					final com.onwbp.adaptation.Request request = pTable.createRequest();
					request.setSession(pSession);
					request.setXPathFilter(CdcMatching.filter(all.subList(from, Math.min(all.size(), from + 200))));
					final com.onwbp.adaptation.RequestResult rows = request.execute();
					try
					{
						for (Adaptation record = rows.nextAdaptation(); record != null; record = rows.nextAdaptation())
						{
							final String state = MameGoldenRecords.this.state(record);
							if ("GOLDEN".equals(state))
								tally.golden++;
							else if ("MERGED".equals(state))
								tally.merged++;
							else
								tally.other++;
							if (hasSuspectsField && Boolean.TRUE.equals(record.get(suspects)))
								tally.withSuspects++;
						}
					}
					finally
					{
						rows.close();
					}
				}
				return tally;
			}
		};
	}

	private static String tableKey(final Adaptation pRecord)
	{
		return pRecord.getContainer().getAdaptationName().getStringName() + '#' + pRecord.getContainerTable().getTablePath().format();
	}
}
