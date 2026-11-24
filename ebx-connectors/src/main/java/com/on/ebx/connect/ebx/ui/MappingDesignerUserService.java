package com.on.ebx.connect.ebx.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.on.ebx.connect.core.Expressions;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.MappingResult;
import com.on.ebx.connect.core.MappingRule;
import com.on.ebx.connect.core.SyncResult;
import com.on.ebx.connect.core.Transformations;
import com.on.ebx.connect.core.MappingDefinition.Direction;
import com.on.ebx.connect.ebx.DatasetLocator;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.FieldInfo;
import com.on.ebx.connect.spi.DiscoveredObject;
import com.on.ebx.connect.spi.ObjectDiscoveryRequest;
import com.on.ebx.connect.spi.ObjectDiscoveryResult;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.ValueContext;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.schema.SchemaTypeName;
import com.orchestranetworks.schema.dynamic.BeanDefinition;
import com.orchestranetworks.schema.dynamic.BeanElement;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.service.ValueContextForUpdate;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.UIButtonSpecSubmit;
import com.orchestranetworks.ui.selection.RecordEntitySelection;
import com.orchestranetworks.userservice.ObjectKey;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceDisplayConfigurator;
import com.orchestranetworks.userservice.UserServiceEvent;
import com.orchestranetworks.userservice.UserServiceEventContext;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;
import com.orchestranetworks.userservice.UserServiceProcessEventOutcomeContext;
import com.orchestranetworks.userservice.UserServiceSetupDisplayContext;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;
import com.orchestranetworks.userservice.UserServiceValidateContext;

/**
 * Graphical mapping designer, in the spirit of the Data Integration field mapping: the source
 * fields on the left, the target fields on the right, a link per rule, and a transformation
 * pipeline on each link. EBX sits on the right for an INBOUND mapping and on the left for an
 * OUTBOUND one; the direction can be switched, and the reverse mapping generated in one click.
 * <p>
 * The page is plain HTML and JavaScript written by this service; the rules travel back in the
 * hidden {@code ftmdRules} field as JSON on every submit (save, test, sample load).
 */
public final class MappingDesignerUserService implements UserService<RecordEntitySelection>
{
	static final ObjectKey OPTIONS = ObjectKey.forName("ftMappingDesignerOptions");
	private static final Path P_SAMPLE = Path.parse("./sampleKey");
	static final String RULES_PARAM = "ftmdRules";
	static final String DIRECTION_PARAM = "ftmdDirection";
	static final String RESPONSE_ROOT_PARAM = "ftmdResponseRoot";
	static final String INPUTS_PARAM = "ftmdInputs";
	private static final int MAX_SAMPLE_FIELDS = 400;

	private static final String[][] TRANSFORMATION_HINTS = { { "trim", "trim" }, { "upper", "upper" }, { "lower", "lower" },
		{ "capitalize", "capitalize" }, { "left", "left(n)" }, { "right", "right(n)" }, { "substring", "substring(start,end)" },
		{ "replace", "replace(a,b)" }, { "regex", "regex(pattern,group)" }, { "digits", "digits" }, { "prefix", "prefix(p)" },
		{ "suffix", "suffix(s)" }, { "default", "default(v)" }, { "constant", "constant(v)" }, { "nullIfEmpty", "nullIfEmpty" },
		{ "nullIf", "nullIf(v)" }, { "isoDate", "isoDate" }, { "isoDateTime", "isoDateTime" }, { "date", "date(inFormat,outFormat)" },
		{ "map", "map(A=B;C=D;*=default)" }, { "join", "join(', ')" }, { "first", "first" }, { "count", "count" },
		{ "boolean", "boolean" }, { "number", "number" }, { "round", "round(n)" }, { "fk", "fk(/root/Table)" },
		{ "lookup", "lookup(/root/Table,./field,./result)" }, { "golden", "golden(/root/Table)" }, { "mdmState", "mdmState(/root/Table)" } };

	private RecordEntitySelection selection;
	private Adaptation mapping;
	private String direction;
	private String responseRoot;
	private String sampleKey = "";
	private String postedRules;
	private String postedInputs;
	private List<FieldInfo> externalFields;
	private String externalFieldsOrigin = "";
	private String message;
	private boolean messageIsError;
	private SyncResult testResult;

	// ------------------------------------------------------------------ lifecycle

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<RecordEntitySelection> pContext,
		final UserServiceObjectContextBuilder pBuilder)
	{
		this.selection = pContext.getEntitySelection();
		this.mapping = this.selection.getRecord();
		if (this.direction == null)
		{
			this.direction = this.mapping.getString(ConnectorsPaths.Mapping.DIRECTION);
			this.responseRoot = this.mapping.getString(ConnectorsPaths.Mapping.RESPONSE_ROOT);
		}
		final Locale locale = pContext.getSession().getLocale();
		final BeanDefinition definition = pBuilder.createBeanDefinition();
		final BeanElement sample = definition.createElement(MappingDesignerUserService.P_SAMPLE, SchemaTypeName.XS_STRING);
		sample.setLabel(MappingDesignerUserService.label(locale, "designer.sampleKey"));
		sample.setDescription(MappingDesignerUserService.label(locale, "designer.sampleKey.help"));
		sample.setDefaultValue(this.sampleKey);
		pBuilder.registerBean(MappingDesignerUserService.OPTIONS, definition);
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<RecordEntitySelection> pContext,
		final UserServiceDisplayConfigurator pConfigurator)
	{
		final Locale locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(MappingDesignerUserService.label(locale, "designer.title",
			this.mapping.getString(ConnectorsPaths.Mapping.LABEL)));
		final UIButtonSpecSubmit save = pConfigurator.newSubmitButton(MappingDesignerUserService.label(locale, "designer.save"), new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				MappingDesignerUserService.this.readPosted(pEventContext);
				MappingDesignerUserService.this.onSave(pEventContext);
				return null;
			}
		});
		save.setId("ftmdSave");
		final UIButtonSpecSubmit test = pConfigurator.newSubmitButton(MappingDesignerUserService.label(locale, "designer.test"), new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				MappingDesignerUserService.this.readPosted(pEventContext);
				MappingDesignerUserService.this.onTest(pEventContext);
				return null;
			}
		});
		test.setId("ftmdTest");
		final UIButtonSpecSubmit sample = pConfigurator.newSubmitButton(MappingDesignerUserService.label(locale, "designer.loadSample"), new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				MappingDesignerUserService.this.readPosted(pEventContext);
				MappingDesignerUserService.this.onLoadSample(pEventContext);
				return null;
			}
		});
		sample.setId("ftmdSample");
		final UIButtonSpecSubmit invert = pConfigurator.newSubmitButton(MappingDesignerUserService.label(locale, "designer.invert"), new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				MappingDesignerUserService.this.readPosted(pEventContext);
				MappingDesignerUserService.this.onInvert(pEventContext);
				return null;
			}
		});
		invert.setId("ftmdInvert");
		final UIButtonSpecSubmit child = pConfigurator.newSubmitButton(MappingDesignerUserService.label(locale, "designer.createChild"), new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				MappingDesignerUserService.this.readPosted(pEventContext);
				MappingDesignerUserService.this.onCreateChild(pEventContext);
				return null;
			}
		});
		child.setId("ftmdChild");
		final UIButtonSpecSubmit response = pConfigurator.newSubmitButton(MappingDesignerUserService.label(locale, "designer.createResponse"), new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				MappingDesignerUserService.this.readPosted(pEventContext);
				MappingDesignerUserService.this.onCreateResponse(pEventContext);
				return null;
			}
		});
		response.setId("ftmdResponse");
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		close.setId("ftmdClose");
		pConfigurator.setLeftButtons(save, test, sample, invert, child, response);
		pConfigurator.setRightButtons(close);
		pConfigurator.setContent(new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				ThemeTokens.write(pWriter, pPaneContext);
				MappingDesignerUserService.this.writeContent(pWriter, pPaneContext.getSession());
			}
		});
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<RecordEntitySelection> pContext,
		final UserServiceEventOutcome pEventOutcome)
	{
		return pEventOutcome;
	}

	@Override
	public void validate(final UserServiceValidateContext<RecordEntitySelection> pContext)
	{
		// nothing: the options are free text
	}

	// ------------------------------------------------------------------ events

	private void readPosted(final UserServiceEventContext pContext)
	{
		this.message = null;
		this.messageIsError = false;
		this.testResult = null;
		final String rules = pContext.getParameter(MappingDesignerUserService.RULES_PARAM);
		if (rules != null && !rules.trim().isEmpty())
		{
			this.postedRules = rules;
		}
		final String inputs = pContext.getParameter(MappingDesignerUserService.INPUTS_PARAM);
		if (inputs != null && !inputs.trim().isEmpty())
		{
			this.postedInputs = inputs;
		}
		final String direction = pContext.getParameter(MappingDesignerUserService.DIRECTION_PARAM);
		if (direction != null && ("INBOUND".equals(direction) || "OUTBOUND".equals(direction)))
		{
			if (!direction.equals(this.direction))
			{
				this.externalFields = null; // the external side changes role, its catalog too
			}
			this.direction = direction;
		}
		final String root = pContext.getParameter(MappingDesignerUserService.RESPONSE_ROOT_PARAM);
		if (root != null)
		{
			this.responseRoot = root.trim();
		}
		final ValueContext values = pContext.getValueContext(MappingDesignerUserService.OPTIONS);
		final Object sample = values.getValue(MappingDesignerUserService.P_SAMPLE);
		this.sampleKey = sample == null ? "" : sample.toString().trim();
	}

	private void onSave(final UserServiceEventContext pContext)
	{
		final Locale locale = pContext.getSession().getLocale();
		final List<MappingRule> rules;
		try
		{
			rules = this.parseRules();
		}
		catch (final Exception ex)
		{
			this.fail(MappingDesignerUserService.label(locale, "designer.badRules", ex.getMessage()));
			return;
		}
		final Map<String, String> inputs = this.parseInputs();
		final Adaptation dataset = this.mapping.getContainer();
		final AdaptationTable ruleTable = dataset.getTable(ConnectorsPaths.MAPPING_RULE);
		final String mappingId = String.valueOf(this.mapping.get(ConnectorsPaths.Mapping.ID));
		final String newDirection = this.direction;
		final String newRoot = this.responseRoot;
		final Adaptation mappingRecord = this.mapping;
		final ProcedureResult result = ProgrammaticService.createForSession(pContext.getSession(), dataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pProcedure) throws Exception
			{
				// mapping header
				final ValueContextForUpdate header = pProcedure.getContext(mappingRecord.getAdaptationName());
				header.setValue(newDirection, ConnectorsPaths.Mapping.DIRECTION);
				header.setValue(newRoot == null || newRoot.isEmpty() ? null : newRoot, ConnectorsPaths.Mapping.RESPONSE_ROOT);
				pProcedure.doModifyContent(mappingRecord, header);
				// existing rules of this mapping
				final Map<Integer, Adaptation> existing = new LinkedHashMap<Integer, Adaptation>();
				final com.onwbp.adaptation.Request request = ruleTable.createRequest();
				request.setXPathFilter("./mapping='" + mappingId + "'");
				final RequestResult rows = request.execute();
				try
				{
					for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
					{
						existing.put((Integer) row.get(ConnectorsPaths.MappingRule.ID), row);
					}
				}
				finally
				{
					rows.close();
				}
				int position = 10;
				final List<Integer> kept = new ArrayList<Integer>();
				for (final MappingRule rule : rules)
				{
					final Adaptation row = rule.getPosition() > 0 ? existing.get(Integer.valueOf(rule.getPosition())) : null;
					final ValueContextForUpdate values = row == null ? pProcedure.getContextForNewOccurrence(ruleTable)
							: pProcedure.getContext(row.getAdaptationName());
					values.setValue(mappingId, ConnectorsPaths.MappingRule.MAPPING);
					values.setValue(Integer.valueOf(position), ConnectorsPaths.MappingRule.POSITION);
					values.setValue(rule.getSourcePath().isEmpty() ? null : rule.getSourcePath(), ConnectorsPaths.MappingRule.SOURCE_PATH);
					values.setValue(rule.getTargetPath(), ConnectorsPaths.MappingRule.TARGET_PATH);
					values.setValue(rule.getTransformation().isEmpty() ? null : rule.getTransformation(), ConnectorsPaths.MappingRule.TRANSFORMATION);
					values.setValue(rule.getPolicy().name(), ConnectorsPaths.MappingRule.POLICY);
					values.setValue(Boolean.TRUE, ConnectorsPaths.MappingRule.ACTIVE);
					values.setValue(rule.getComment(), ConnectorsPaths.MappingRule.COMMENT);
					if (row == null)
					{
						pProcedure.doCreateOccurrence(values, ruleTable);
					}
					else
					{
						pProcedure.doModifyContent(row, values);
						kept.add(Integer.valueOf(rule.getPosition()));
					}
					position += 10;
				}
				for (final Map.Entry<Integer, Adaptation> entry : existing.entrySet())
				{
					if (!kept.contains(entry.getKey()))
					{
						pProcedure.doDelete(entry.getValue().getAdaptationName(), false);
					}
				}
				// service inputs: replaced as a whole when the page posted them
				if (inputs != null)
				{
					final AdaptationTable inputTable = dataset.getTable(ConnectorsPaths.MAPPING_INPUT);
					final com.onwbp.adaptation.Request inputRequest = inputTable.createRequest();
					inputRequest.setXPathFilter("./mapping='" + mappingId + "'");
					final RequestResult inputRows = inputRequest.execute();
					final List<Adaptation> old = new ArrayList<Adaptation>();
					try
					{
						for (Adaptation row = inputRows.nextAdaptation(); row != null; row = inputRows.nextAdaptation())
						{
							old.add(row);
						}
					}
					finally
					{
						inputRows.close();
					}
					for (final Adaptation row : old)
					{
						pProcedure.doDelete(row.getAdaptationName(), false);
					}
					for (final Map.Entry<String, String> input : inputs.entrySet())
					{
						final ValueContextForUpdate values = pProcedure.getContextForNewOccurrence(inputTable);
						values.setValue(mappingId, ConnectorsPaths.MappingInput.MAPPING);
						values.setValue(input.getKey(), ConnectorsPaths.MappingInput.NAME);
						values.setValue(input.getValue(), ConnectorsPaths.MappingInput.EXPRESSION);
						pProcedure.doCreateOccurrence(values, inputTable);
					}
				}
			}
		});
		if (result.hasFailed())
		{
			this.fail(MappingDesignerUserService.label(locale, "designer.saveFailed", result.getExceptionFullMessage(locale)));
			return;
		}
		this.postedRules = null; // re-read from the database, with the new ids
		this.postedInputs = null;
		this.message = MappingDesignerUserService.label(locale, "designer.saved", Integer.valueOf(rules.size()));
	}

	private void onLoadSample(final UserServiceEventContext pContext)
	{
		final Locale locale = pContext.getSession().getLocale();
		try
		{
			final MappingDefinition definition = this.rootDefinition();
			final ConnectorRepository repo = ConnectorService.get().repository(pContext.getRepository());
			final Connector connector = ConnectorRegistry.get().connector(definition.getConnectorId(), repo);
			final Adaptation dataset = DatasetLocator.find(pContext.getRepository(), definition.getDataspace(), definition.getDataset(), definition.getTablePath());
			final Adaptation record = this.sampleRecordOf(dataset, definition.getTablePath());
			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			// A source-object selection changes the SQL in the page and immediately submits the
			// existing bounded preview action. Use those posted values for this preview; the
			// repository definition still contains the previous SQL until the user explicitly
			// saves the mapping.
			final Map<String, String> posted = this.parseInputs();
			final Map<String, String> effectiveInputs = posted == null ? definition.getInputs() : posted;
			for (final Map.Entry<String, String> input : effectiveInputs.entrySet())
			{
				inputs.put(input.getKey(), Expressions.resolve(input.getValue(), record, null).trim());
			}
			final ConnectorContext cctx = new ConnectorContext(pContext.getSession(), pContext.getRepository(), null, dataset);
			final List<FieldInfo> catalog = connector.describeFields(cctx, definition.getOperation(), inputs);
			if ("OUTBOUND".equals(this.direction))
			{
				// the external side is the document the rules build: show it as produced for the sample record
				final Map<String, FieldInfo> merged = new LinkedHashMap<String, FieldInfo>();
				for (final FieldInfo field : catalog)
				{
					merged.put(field.getPath(), field);
				}
				if (record != null)
				{
					final JsonNode built = com.on.ebx.connect.core.MappingEngine.buildOutbound(definition, record,
						new com.on.ebx.connect.core.TransformationContext(dataset, record, pContext.getSession()), new MappingResult());
					for (final String[] flat : Json.flatten(built, MappingDesignerUserService.MAX_SAMPLE_FIELDS))
					{
						final FieldInfo known = merged.get(flat[0]);
						merged.put(flat[0], known == null ? new FieldInfo(flat[0], flat[0], flat[2], flat[1]) : known.withSample(flat[1]));
					}
				}
				this.externalFields = MappingDesignerUserService.withRuleTargets(new ArrayList<FieldInfo>(merged.values()), definition);
				this.externalFieldsOrigin = record == null ? "catalog" : "sample";
				this.message = record == null ? MappingDesignerUserService.label(locale, "designer.noSampleRecord")
						: MappingDesignerUserService.label(locale, "designer.sampleLoaded", record.getLabel(locale), Integer.valueOf(this.externalFields.size()));
				return;
			}
			if (record == null)
			{
				this.externalFields = catalog;
				this.externalFieldsOrigin = "catalog";
				this.message = MappingDesignerUserService.label(locale, "designer.noSampleRecord");
				return;
			}
			JsonNode document = connector.execute(cctx, definition.getOperation(), inputs, null);
			// a sub-object mapping shows the first element of its collection in the parent document
			document = this.narrowToCollection(document);
			if (document == null)
			{
				this.externalFields = catalog;
				this.externalFieldsOrigin = "catalog";
				this.fail(MappingDesignerUserService.label(locale, "designer.noSampleData", inputs.toString()));
				return;
			}
			final Map<String, FieldInfo> merged = new LinkedHashMap<String, FieldInfo>();
			for (final FieldInfo field : catalog)
			{
				merged.put(field.getPath(), field);
			}
			for (final String[] flat : Json.flatten(document, MappingDesignerUserService.MAX_SAMPLE_FIELDS))
			{
				final FieldInfo known = merged.get(flat[0]);
				merged.put(flat[0], known == null ? new FieldInfo(flat[0], flat[0], flat[2], flat[1]) : known.withSample(flat[1]));
			}
			this.externalFields = new ArrayList<FieldInfo>(merged.values());
			this.externalFieldsOrigin = "sample";
			this.message = MappingDesignerUserService.label(locale, "designer.sampleLoaded", record.getLabel(locale),
				Integer.valueOf(this.externalFields.size()));
		}
		catch (final Exception ex)
		{
			this.fail(MappingDesignerUserService.label(locale, "designer.sampleFailed", com.on.ebx.connect.core.Redaction.failure(ex)));
		}
	}

	/** For a sub-object mapping, walks down the parent chain to the first element of the collection. */
	private JsonNode narrowToCollection(final JsonNode pDocument)
	{
		final MappingDefinition stored = ConnectorService.get().repository(this.mapping.getHome().getRepository())
				.mapping(((Integer) this.mapping.get(ConnectorsPaths.Mapping.ID)).intValue());
		if (stored == null || !stored.isChild() || pDocument == null)
		{
			return pDocument;
		}
		final ConnectorRepository repo = ConnectorService.get().repository(this.mapping.getHome().getRepository());
		final List<MappingDefinition> chain = new ArrayList<MappingDefinition>();
		MappingDefinition current = stored;
		while (current != null && current.isChild())
		{
			chain.add(0, current);
			current = repo.mapping(current.getParentId().intValue());
		}
		JsonNode node = pDocument;
		if (current != null && !current.getResponseRoot().isEmpty())
		{
			node = Json.at(node, current.getResponseRoot());
		}
		for (final MappingDefinition link : chain)
		{
			final JsonNode array = Json.at(node, link.getCollectionPath());
			node = array == null ? null : (array.isArray() ? (array.size() > 0 ? array.get(0) : null) : array);
			if (node != null && !link.getResponseRoot().isEmpty())
			{
				node = Json.at(node, link.getResponseRoot());
			}
			if (node == null)
			{
				return null;
			}
		}
		return node;
	}

	/** The root mapping of the chain: its inputs and operation are the ones to call. */
	private MappingDefinition rootDefinition()
	{
		final ConnectorRepository repo = ConnectorService.get().repository(this.mapping.getHome().getRepository());
		MappingDefinition current = repo.mapping(((Integer) this.mapping.get(ConnectorsPaths.Mapping.ID)).intValue());
		while (current != null && current.isChild())
		{
			current = repo.mapping(current.getParentId().intValue());
		}
		return current == null ? this.definition() : current;
	}

	/** The service inputs posted by the page ({@code [{name, expression}]}), or null when nothing was posted. */
	private Map<String, String> parseInputs()
	{
		if (this.postedInputs == null)
		{
			return null;
		}
		final Map<String, String> out = new LinkedHashMap<String, String>();
		try
		{
			final JsonNode array = Json.parse(this.postedInputs);
			if (array != null && array.isArray())
			{
				for (final JsonNode node : array)
				{
					final String name = MappingDesignerUserService.text(node, "name", "").trim();
					final String expression = MappingDesignerUserService.text(node, "expression", "").trim();
					if (!name.isEmpty() && !expression.isEmpty())
					{
						out.put(name, expression);
					}
				}
			}
		}
		catch (final Exception ex)
		{
			return null;
		}
		return out;
	}

	/** The INBOUND twin of an OUTBOUND mapping: what the service answers is written back with it. */
	private MappingDefinition responseMapping()
	{
		if (!"OUTBOUND".equals(this.direction))
		{
			return null;
		}
		final ConnectorRepository repo = ConnectorService.get().repository(this.mapping.getHome().getRepository());
		final int self = ((Integer) this.mapping.get(ConnectorsPaths.Mapping.ID)).intValue();
		for (final MappingDefinition candidate : repo.mappings())
		{
			if (candidate.getId() != self && candidate.getDirection() == MappingDefinition.Direction.INBOUND && !candidate.isChild()
				&& candidate.getConnectorId().equals(this.mapping.getString(ConnectorsPaths.Mapping.CONNECTOR))
				&& candidate.getOperation().equals(this.mapping.getString(ConnectorsPaths.Mapping.OPERATION))
				&& candidate.getTablePath().equals(this.mapping.getString(ConnectorsPaths.Mapping.TABLE_PATH))
				&& candidate.getDataset().equals(this.mapping.getString(ConnectorsPaths.Mapping.DATASET)))
			{
				return candidate;
			}
		}
		return null;
	}

	private void onCreateResponse(final UserServiceEventContext pContext)
	{
		final Locale locale = pContext.getSession().getLocale();
		if (!"OUTBOUND".equals(this.direction))
		{
			this.fail(MappingDesignerUserService.label(locale, "designer.responseOnlyOutbound"));
			return;
		}
		final MappingDefinition existing = this.responseMapping();
		if (existing != null)
		{
			this.message = MappingDesignerUserService.label(locale, "designer.responseExists", Integer.valueOf(existing.getId()), existing.getLabel());
			return;
		}
		final Adaptation dataset = this.mapping.getContainer();
		final Adaptation source = this.mapping;
		final String root = this.responseRoot;
		final int[] created = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pContext.getSession(), dataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pProcedure) throws Exception
			{
				final AdaptationTable mappings = dataset.getTable(ConnectorsPaths.MAPPING);
				final ValueContextForUpdate header = pProcedure.getContextForNewOccurrence(mappings);
				header.setValue(source.getString(ConnectorsPaths.Mapping.CONNECTOR), ConnectorsPaths.Mapping.CONNECTOR);
				header.setValue(source.getString(ConnectorsPaths.Mapping.OPERATION), ConnectorsPaths.Mapping.OPERATION);
				header.setValue("INBOUND", ConnectorsPaths.Mapping.DIRECTION);
				header.setValue(source.getString(ConnectorsPaths.Mapping.LABEL) + " / " + MappingDesignerUserService.label(locale, "designer.responseSuffix"),
					ConnectorsPaths.Mapping.LABEL);
				header.setValue(source.getString(ConnectorsPaths.Mapping.DATASPACE), ConnectorsPaths.Mapping.DATASPACE);
				header.setValue(source.getString(ConnectorsPaths.Mapping.DATASET), ConnectorsPaths.Mapping.DATASET);
				header.setValue(source.getString(ConnectorsPaths.Mapping.TABLE_PATH), ConnectorsPaths.Mapping.TABLE_PATH);
				header.setValue(root == null || root.isEmpty() ? null : root, ConnectorsPaths.Mapping.RESPONSE_ROOT);
				header.setValue(Boolean.TRUE, ConnectorsPaths.Mapping.ACTIVE);
				final Adaptation twin = pProcedure.doCreateOccurrence(header, mappings);
				created[0] = ((Integer) twin.get(ConnectorsPaths.Mapping.ID)).intValue();
			}
		});
		if (result.hasFailed())
		{
			this.fail(MappingDesignerUserService.label(locale, "designer.saveFailed", result.getExceptionFullMessage(locale)));
			return;
		}
		this.message = MappingDesignerUserService.label(locale, "designer.responseCreated", Integer.valueOf(created[0]));
	}

	private void onCreateChild(final UserServiceEventContext pContext)
	{
		final Locale locale = pContext.getSession().getLocale();
		final String collection = pContext.getParameter("ftmdChildCollection");
		final String table = pContext.getParameter("ftmdChildTable");
		final String link = pContext.getParameter("ftmdChildLink");
		final String keys = pContext.getParameter("ftmdChildKeys");
		if (Expressions.isBlank(collection) || Expressions.isBlank(table) || Expressions.isBlank(link))
		{
			this.fail(MappingDesignerUserService.label(locale, "designer.childMissing"));
			return;
		}
		final Adaptation dataset = this.mapping.getContainer();
		final Adaptation source = this.mapping;
		final String direction = this.direction;
		final int[] created = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pContext.getSession(), dataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pProcedure) throws Exception
			{
				final AdaptationTable mappings = dataset.getTable(ConnectorsPaths.MAPPING);
				final ValueContextForUpdate header = pProcedure.getContextForNewOccurrence(mappings);
				header.setValue(source.getString(ConnectorsPaths.Mapping.CONNECTOR), ConnectorsPaths.Mapping.CONNECTOR);
				header.setValue(source.getString(ConnectorsPaths.Mapping.OPERATION), ConnectorsPaths.Mapping.OPERATION);
				header.setValue(direction, ConnectorsPaths.Mapping.DIRECTION);
				header.setValue(source.getString(ConnectorsPaths.Mapping.LABEL) + " / " + collection.trim(), ConnectorsPaths.Mapping.LABEL);
				header.setValue(source.getString(ConnectorsPaths.Mapping.DATASPACE), ConnectorsPaths.Mapping.DATASPACE);
				header.setValue(source.getString(ConnectorsPaths.Mapping.DATASET), ConnectorsPaths.Mapping.DATASET);
				header.setValue(table.trim(), ConnectorsPaths.Mapping.TABLE_PATH);
				header.setValue(Boolean.TRUE, ConnectorsPaths.Mapping.ACTIVE);
				header.setValue(String.valueOf(source.get(ConnectorsPaths.Mapping.ID)), ConnectorsPaths.Mapping.PARENT);
				header.setValue(collection.trim(), ConnectorsPaths.Mapping.COLLECTION_PATH);
				header.setValue(link.trim(), ConnectorsPaths.Mapping.PARENT_LINK_FIELD);
				header.setValue(Expressions.isBlank(keys) ? null : keys.trim(), ConnectorsPaths.Mapping.CHILD_KEY_FIELDS);
				final Adaptation child = pProcedure.doCreateOccurrence(header, mappings);
				created[0] = ((Integer) child.get(ConnectorsPaths.Mapping.ID)).intValue();
			}
		});
		if (result.hasFailed())
		{
			this.fail(MappingDesignerUserService.label(locale, "designer.saveFailed", result.getExceptionFullMessage(locale)));
			return;
		}
		this.message = MappingDesignerUserService.label(locale, "designer.childCreated", Integer.valueOf(created[0]), collection.trim(), table.trim());
	}

	private void onTest(final UserServiceEventContext pContext)
	{
		final Locale locale = pContext.getSession().getLocale();
		try
		{
			final MappingDefinition definition = this.definition();
			for (final MappingRule rule : this.parseRules())
			{
				definition.addRule(rule);
			}
			final Adaptation dataset = this.targetDataset(pContext.getRepository());
			final Adaptation record = this.sampleRecord(dataset);
			if (record == null)
			{
				this.fail(MappingDesignerUserService.label(locale, "designer.noSampleRecord"));
				return;
			}
			this.testResult = ConnectorService.get().syncRecord(pContext.getSession(), record, definition, true, "designer");
			this.message = MappingDesignerUserService.label(locale, "designer.tested", record.getLabel(locale),
				this.testResult.getStatus().name(), this.testResult.getMessage());
			this.messageIsError = this.testResult.hasFailure();
		}
		catch (final Exception ex)
		{
			this.fail(MappingDesignerUserService.label(locale, "designer.testFailed", com.on.ebx.connect.core.Redaction.failure(ex)));
		}
	}

	private void onInvert(final UserServiceEventContext pContext)
	{
		final Locale locale = pContext.getSession().getLocale();
		final List<MappingRule> rules;
		try
		{
			rules = this.parseRules();
		}
		catch (final Exception ex)
		{
			this.fail(MappingDesignerUserService.label(locale, "designer.badRules", ex.getMessage()));
			return;
		}
		final Adaptation dataset = this.mapping.getContainer();
		final Adaptation source = this.mapping;
		final String newDirection = "INBOUND".equals(this.direction) ? "OUTBOUND" : "INBOUND";
		final int[] created = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pContext.getSession(), dataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pProcedure) throws Exception
			{
				final AdaptationTable mappings = dataset.getTable(ConnectorsPaths.MAPPING);
				final ValueContextForUpdate header = pProcedure.getContextForNewOccurrence(mappings);
				header.setValue(source.getString(ConnectorsPaths.Mapping.CONNECTOR), ConnectorsPaths.Mapping.CONNECTOR);
				header.setValue(source.getString(ConnectorsPaths.Mapping.OPERATION), ConnectorsPaths.Mapping.OPERATION);
				header.setValue(newDirection, ConnectorsPaths.Mapping.DIRECTION);
				header.setValue(source.getString(ConnectorsPaths.Mapping.LABEL) + " (" + newDirection.toLowerCase(Locale.ROOT) + ")", ConnectorsPaths.Mapping.LABEL);
				header.setValue(source.getString(ConnectorsPaths.Mapping.DATASPACE), ConnectorsPaths.Mapping.DATASPACE);
				header.setValue(source.getString(ConnectorsPaths.Mapping.DATASET), ConnectorsPaths.Mapping.DATASET);
				header.setValue(source.getString(ConnectorsPaths.Mapping.TABLE_PATH), ConnectorsPaths.Mapping.TABLE_PATH);
				header.setValue(Boolean.FALSE, ConnectorsPaths.Mapping.ACTIVE);
				final Adaptation newMapping = pProcedure.doCreateOccurrence(header, mappings);
				final String newId = String.valueOf(newMapping.get(ConnectorsPaths.Mapping.ID));
				created[0] = ((Integer) newMapping.get(ConnectorsPaths.Mapping.ID)).intValue();
				final AdaptationTable inputs = dataset.getTable(ConnectorsPaths.MAPPING_INPUT);
				final com.onwbp.adaptation.Request request = inputs.createRequest();
				request.setXPathFilter("./mapping='" + source.get(ConnectorsPaths.Mapping.ID) + "'");
				final RequestResult rows = request.execute();
				try
				{
					for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
					{
						final ValueContextForUpdate copy = pProcedure.getContextForNewOccurrence(inputs);
						copy.setValue(newId, ConnectorsPaths.MappingInput.MAPPING);
						copy.setValue(row.getString(ConnectorsPaths.MappingInput.NAME), ConnectorsPaths.MappingInput.NAME);
						copy.setValue(row.getString(ConnectorsPaths.MappingInput.EXPRESSION), ConnectorsPaths.MappingInput.EXPRESSION);
						pProcedure.doCreateOccurrence(copy, inputs);
					}
				}
				finally
				{
					rows.close();
				}
				final AdaptationTable ruleTable = dataset.getTable(ConnectorsPaths.MAPPING_RULE);
				int position = 10;
				for (final MappingRule rule : rules)
				{
					if (rule.getSourcePath().isEmpty())
					{
						continue; // a constant has no inverse
					}
					final ValueContextForUpdate values = pProcedure.getContextForNewOccurrence(ruleTable);
					values.setValue(newId, ConnectorsPaths.MappingRule.MAPPING);
					values.setValue(Integer.valueOf(position), ConnectorsPaths.MappingRule.POSITION);
					values.setValue(rule.getTargetPath(), ConnectorsPaths.MappingRule.SOURCE_PATH);
					values.setValue(rule.getSourcePath(), ConnectorsPaths.MappingRule.TARGET_PATH);
					values.setValue("IF_EMPTY", ConnectorsPaths.MappingRule.POLICY);
					values.setValue(Boolean.TRUE, ConnectorsPaths.MappingRule.ACTIVE);
					pProcedure.doCreateOccurrence(values, ruleTable);
					position += 10;
				}
			}
		});
		if (result.hasFailed())
		{
			this.fail(MappingDesignerUserService.label(locale, "designer.saveFailed", result.getExceptionFullMessage(locale)));
			return;
		}
		this.message = MappingDesignerUserService.label(locale, "designer.inverted", Integer.valueOf(created[0]), newDirection);
	}

	private void fail(final String pMessage)
	{
		this.message = pMessage;
		this.messageIsError = true;
	}

	// ------------------------------------------------------------------ model access

	/** An inbound mapping whose rules write rows found by a key: a feed (keyPredicate) or a sub-object mapping (parent). */
	private boolean isFeedLike()
	{
		if ("OUTBOUND".equals(this.direction) || this.mapping == null)
		{
			return false;
		}
		final String keyPredicate = this.mapping.getString(ConnectorsPaths.Mapping.KEY_PREDICATE);
		final Object parent = this.mapping.get(ConnectorsPaths.Mapping.PARENT);
		return keyPredicate != null && !keyPredicate.trim().isEmpty() || parent != null && !String.valueOf(parent).trim().isEmpty();
	}

	/** The target fields that stay IF_EMPTY: what the key predicate reads, the child key fields, the parent link field. */
	private java.util.Set<String> keyTargets()
	{
		if (this.mapping == null)
		{
			return java.util.Collections.<String> emptySet();
		}
		final String childKeys = this.mapping.getString(ConnectorsPaths.Mapping.CHILD_KEY_FIELDS);
		final List<String> childKeyFields = new java.util.ArrayList<String>();
		if (childKeys != null)
		{
			for (final String field : childKeys.split(","))
			{
				childKeyFields.add(field);
			}
		}
		return com.on.ebx.connect.core.RulePolicies.keyTargets(this.mapping.getString(ConnectorsPaths.Mapping.KEY_PREDICATE), childKeyFields,
			this.mapping.getString(ConnectorsPaths.Mapping.PARENT_LINK_FIELD));
	}

	private MappingDefinition definition()
	{
		final ConnectorRepository repo = ConnectorService.get().repository(this.mapping.getHome().getRepository());
		final int id = ((Integer) this.mapping.get(ConnectorsPaths.Mapping.ID)).intValue();
		final MappingDefinition stored = repo.mapping(id);
		final MappingDefinition fresh = new MappingDefinition(id, this.mapping.getString(ConnectorsPaths.Mapping.CONNECTOR),
			this.mapping.getString(ConnectorsPaths.Mapping.OPERATION),
			"OUTBOUND".equals(this.direction) ? MappingDefinition.Direction.OUTBOUND : MappingDefinition.Direction.INBOUND,
			this.mapping.getString(ConnectorsPaths.Mapping.LABEL), this.mapping.getString(ConnectorsPaths.Mapping.DATASPACE),
			this.mapping.getString(ConnectorsPaths.Mapping.DATASET), this.mapping.getString(ConnectorsPaths.Mapping.TABLE_PATH),
			this.responseRoot, true);
		if (stored != null)
		{
			for (final Map.Entry<String, String> input : stored.getInputs().entrySet())
			{
				fresh.addInput(input.getKey(), input.getValue());
			}
		}
		return fresh;
	}

	/** Current rules: the posted JSON when there is one, else the database. */
	private List<MappingRule> parseRules() throws Exception
	{
		final List<MappingRule> rules = new ArrayList<MappingRule>();
		if (this.postedRules == null)
		{
			final MappingDefinition stored = ConnectorService.get().repository(this.mapping.getHome().getRepository())
					.mapping(((Integer) this.mapping.get(ConnectorsPaths.Mapping.ID)).intValue());
			return stored == null ? rules : new ArrayList<MappingRule>(stored.getRules());
		}
		final JsonNode array = Json.parse(this.postedRules);
		if (array == null || !array.isArray())
		{
			return rules;
		}
		for (final JsonNode node : array)
		{
			final int id = node.has("id") && node.get("id").isNumber() ? node.get("id").asInt() : 0;
			MappingRule.Policy policy = MappingRule.Policy.IF_EMPTY;
			try
			{
				policy = MappingRule.Policy.valueOf(MappingDesignerUserService.text(node, "policy", "IF_EMPTY"));
			}
			catch (final IllegalArgumentException ex)
			{
				policy = MappingRule.Policy.IF_EMPTY;
			}
			// the rule id travels in the position slot until it is saved
			rules.add(new MappingRule(id, MappingDesignerUserService.text(node, "source", ""), MappingDesignerUserService.text(node, "target", ""),
				MappingDesignerUserService.text(node, "transformation", ""), policy, MappingDesignerUserService.text(node, "comment", null)));
		}
		return rules;
	}

	private static String text(final JsonNode pNode, final String pKey, final String pDefault)
	{
		final JsonNode value = pNode.get(pKey);
		return value == null || value.isNull() ? pDefault : value.asText();
	}

	private Adaptation targetDataset(final com.orchestranetworks.instance.Repository pRepository)
	{
		return DatasetLocator.find(pRepository, this.mapping.getString(ConnectorsPaths.Mapping.DATASPACE),
			this.mapping.getString(ConnectorsPaths.Mapping.DATASET), this.mapping.getString(ConnectorsPaths.Mapping.TABLE_PATH));
	}

	private Adaptation sampleRecord(final Adaptation pDataset)
	{
		return this.sampleRecordOf(pDataset, this.mapping.getString(ConnectorsPaths.Mapping.TABLE_PATH));
	}

	private Adaptation sampleRecordOf(final Adaptation pDataset, final String pTablePath)
	{
		if (pDataset == null)
		{
			return null;
		}
		final AdaptationTable table = pDataset.getTable(Path.parse(pTablePath));
		if (this.sampleKey == null || this.sampleKey.isEmpty())
		{
			final RequestResult rows = table.createRequest().execute();
			try
			{
				return rows.nextAdaptation();
			}
			finally
			{
				rows.close();
			}
		}
		return table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(this.sampleKey));
	}

	// ------------------------------------------------------------------ display

	private void writeContent(final UserServicePaneWriter pWriter, final Session pSession)
	{
		final Locale locale = pSession.getLocale();
		final String stamp = String.valueOf(System.currentTimeMillis() / 60000L);
		pWriter.add("<link rel=\"stylesheet\" type=\"text/css\" href=\""
				+ pWriter.getURLForResource(ResourceType.STYLESHEET, "connect/mapping-designer.css") + "?v=" + stamp + "\"/>");
		pWriter.setCurrentObject(MappingDesignerUserService.OPTIONS);
		if (this.message != null)
		{
			pWriter.add("<div class=\"ftmdMsg").add(this.messageIsError ? " ftmdErr" : "").add("\">")
					.add(MappingDesignerUserService.escape(this.message)).add("</div>");
		}
		pWriter.startTableFormRow();
		pWriter.addFormRow(MappingDesignerUserService.P_SAMPLE);
		pWriter.endTableFormRow();
		pWriter.add("<div id=\"ftmd\"></div>");
		pWriter.add("<input type=\"hidden\" name=\"" + MappingDesignerUserService.RULES_PARAM + "\" id=\"ftmdRulesInput\" value=\"\"/>");
		pWriter.add("<input type=\"hidden\" name=\"" + MappingDesignerUserService.DIRECTION_PARAM + "\" id=\"ftmdDirectionInput\" value=\""
				+ MappingDesignerUserService.escape(this.direction) + "\"/>");
		pWriter.add("<input type=\"hidden\" name=\"" + MappingDesignerUserService.RESPONSE_ROOT_PARAM + "\" id=\"ftmdRootInput\" value=\""
				+ MappingDesignerUserService.escape(this.responseRoot == null ? "" : this.responseRoot) + "\"/>");
		pWriter.add("<input type=\"hidden\" name=\"" + MappingDesignerUserService.INPUTS_PARAM + "\" id=\"ftmdInputsInput\" value=\"\"/>");
		for (final String name : new String[] { "ftmdChildCollection", "ftmdChildTable", "ftmdChildLink", "ftmdChildKeys" })
		{
			pWriter.add("<input type=\"hidden\" name=\"" + name + "\" id=\"" + name + "\" value=\"\"/>");
		}
		if (this.testResult != null)
		{
			this.writeTestResult(pWriter, locale);
		}
		// both through addJS_cr so that the model is defined before the page script runs, whatever
		// EBX does with body scripts; the external file is appended once the model exists
		// the model carries values sampled from the remote system: written so that none of them can end the script
		pWriter.addJS_cr("var ftmdModel = " + Json.writeForScript(this.model(pSession)) + ";");
		// opened from a connector's flow card: Close goes back to the connector, not to the flow record
		if (this.mapping != null)
		{
			pWriter.addJS_cr(MappingForm.closeReturnScript("ftmdClose", ConnectorForm.FLOW_RETURN_KEY, ConnectorForm.resolveMappingId(this.mapping), StorageScope.userId(pSession)));
		}
		pWriter.addJS_cr("(function(){ var s = document.createElement('script'); s.type = 'text/javascript'; s.src = '"
				+ pWriter.getURLForResource(ResourceType.JSCRIPT, "connect/mapping-designer.js").replace("'", "%27") + "?v=" + stamp
				+ "'; (document.body || document.head).appendChild(s); })();");
	}

	private void writeTestResult(final UserServicePaneWriter pWriter, final Locale pLocale)
	{
		pWriter.add("<div class=\"ftmdTest\"><div class=\"ftmdTestTitle\">")
				.add(MappingDesignerUserService.escape(MappingDesignerUserService.label(pLocale, "designer.testResult", this.testResult.getRecordLabel())))
				.add("</div><ul>");
		for (final MappingResult.Change change : this.testResult.getChanges())
		{
			pWriter.add("<li class=\"ftmdChange\">").add(MappingDesignerUserService.escape(change.toString())).add("</li>");
		}
		for (final String error : this.testResult.getErrors())
		{
			pWriter.add("<li class=\"ftmdError\">").add(MappingDesignerUserService.escape(error)).add("</li>");
		}
		if (this.testResult.getChanges().isEmpty() && this.testResult.getErrors().isEmpty())
		{
			pWriter.add("<li>").add(MappingDesignerUserService.escape(this.testResult.getMessage())).add("</li>");
		}
		pWriter.add("</ul></div>");
	}

	/** Everything the page script needs, as one JSON object. */
	private ObjectNode model(final Session pSession)
	{
		final Locale locale = pSession.getLocale();
		final ObjectNode model = Json.object();
		model.put("direction", this.direction);
		model.put("responseRoot", this.responseRoot == null ? "" : this.responseRoot);
		model.put("connector", this.mapping.getString(ConnectorsPaths.Mapping.CONNECTOR));
		try
		{
			final com.on.ebx.connect.spi.ConnectorConfig cfg = ConnectorService.get().repository(pSession == null ? Repository.getDefault() : Repository.getDefault()).connector(this.mapping.getString(ConnectorsPaths.Mapping.CONNECTOR));
			model.put("connectorLogo", Logos.url(cfg == null || cfg.getLogo() == null ? Logos.resource("default.svg") : cfg.getLogo()));
		}
		catch (final RuntimeException ex)
		{
			model.put("connectorLogo", Logos.defaultUrl());
		}
		model.put("operation", this.mapping.getString(ConnectorsPaths.Mapping.OPERATION));
		model.put("table", this.mapping.getString(ConnectorsPaths.Mapping.TABLE_PATH));
		model.put("externalOrigin", this.externalFieldsOrigin);
		final MappingDefinition stored = ConnectorService.get().repository(this.mapping.getHome().getRepository())
				.mapping(((Integer) this.mapping.get(ConnectorsPaths.Mapping.ID)).intValue());
		model.put("isChild", stored != null && stored.isChild());
		model.put("collectionPath", stored == null ? "" : stored.getCollectionPath());
		model.put("parentLinkField", stored == null ? "" : stored.getParentLinkField());
		final ArrayNode children = model.putArray("children");
		if (stored != null)
		{
			for (final MappingDefinition c : stored.getChildren())
			{
				final ObjectNode n = children.addObject();
				n.put("id", c.getId());
				n.put("label", c.getLabel());
				n.put("collectionPath", c.getCollectionPath());
				n.put("tablePath", c.getTablePath());
				n.put("rules", c.getRules().size());
			}
		}
		// the call: which inputs the operation takes, with the expressions stored for this mapping
		final ArrayNode inputs = model.putArray("inputs");
		final Map<String, String> storedInputs = new LinkedHashMap<String, String>();
		if (this.postedInputs != null && this.parseInputs() != null)
		{
			storedInputs.putAll(this.parseInputs());
		}
		else if (stored != null)
		{
			storedInputs.putAll(stored.getInputs());
		}
		final List<String> listed = new ArrayList<String>();
		Connector selectedConnector = null;
		try
		{
			final ConnectorRepository repo = ConnectorService.get().repository(this.mapping.getHome().getRepository());
			final Connector connector = ConnectorRegistry.get().connector(this.mapping.getString(ConnectorsPaths.Mapping.CONNECTOR), repo);
			selectedConnector = connector;
			for (final com.on.ebx.connect.spi.Operation operation : connector.getOperations())
			{
				if (!operation.getName().equals(this.mapping.getString(ConnectorsPaths.Mapping.OPERATION)))
				{
					continue;
				}
				model.put("operationKind", operation.getKind().name());
				model.put("operationDescription", operation.getDescription());
				for (final String name : operation.getRequiredInputs())
				{
					final ObjectNode in = inputs.addObject();
					in.put("name", name);
					in.put("expression", storedInputs.containsKey(name) ? storedInputs.get(name) : "");
					in.put("required", true);
					listed.add(name);
				}
				for (final String name : operation.getOptionalInputs())
				{
					final ObjectNode in = inputs.addObject();
					in.put("name", name);
					in.put("expression", storedInputs.containsKey(name) ? storedInputs.get(name) : "");
					in.put("required", false);
					listed.add(name);
				}
			}
		}
		catch (final Exception ex)
		{
			model.put("connectorError", String.valueOf(ex.getMessage()));
		}
		for (final Map.Entry<String, String> input : storedInputs.entrySet())
		{
			if (!listed.contains(input.getKey()))
			{
				final ObjectNode in = inputs.addObject();
				in.put("name", input.getKey());
				in.put("expression", input.getValue());
				in.put("required", false);
			}
		}
		this.addPostgreSqlSourceObjects(model, pSession, selectedConnector);
		final MappingDefinition twin = this.responseMapping();
		if (twin != null)
		{
			final ObjectNode r = model.putObject("responseMapping");
			r.put("id", twin.getId());
			r.put("label", twin.getLabel());
			r.put("rules", twin.getRules().size());
		}
		else
		{
			model.putNull("responseMapping");
		}
		final ArrayNode tables = model.putArray("tables");
		final Adaptation targetDataset = this.targetDataset(this.mapping.getHome().getRepository());
		if (targetDataset != null)
		{
			MappingDesignerUserService.collectTables(targetDataset.getSchemaNode(), tables);
		}
		final ArrayNode labels = model.putArray("labels");
		for (final String key : new String[] { "designer.source", "designer.target", "designer.ebx", "designer.external", "designer.rules",
			"designer.rule.source", "designer.rule.target", "designer.rule.transformation", "designer.rule.policy", "designer.rule.delete",
			"designer.rule.constant", "designer.hint", "designer.search", "designer.direction", "designer.responseRoot", "designer.insert",
			"designer.policy.IF_EMPTY", "designer.policy.ALWAYS", "designer.policy.TRUSTED", "designer.noExternalFields", "designer.reload", "designer.sample",
			"designer.children", "designer.child.collection", "designer.child.table", "designer.child.link", "designer.child.keys",
			"designer.child.hint", "designer.child.of", "designer.child.open", "designer.input", "designer.output", "designer.addTransformation",
			"designer.addConstant", "designer.hideUnmapped", "designer.viewDiagram", "designer.viewTable", "designer.inspector", "designer.inspector.empty",
			"designer.rule.comment", "designer.unsaved", "designer.dragHint", "designer.noRules", "designer.step.add", "designer.step.remove",
			"designer.expandAll", "designer.collapseAll", "designer.pickTarget", "designer.confirmDirection", "designer.mappedCount",
			"designer.pipeline", "designer.pipeline.raw", "designer.value", "designer.sampleValue", "designer.pk", "designer.constantHint",
			"designer.serviceCall", "designer.serviceCall.hint", "designer.serviceCall.hintOut", "designer.input.required", "designer.input.optional",
			"designer.input.expression", "designer.input.insertField", "designer.input.add", "designer.input.name", "designer.input.transform", "designer.fullscreen", "designer.responseSection",
			"designer.responseSection.hint", "designer.responseSection.none", "designer.responseSection.open", "designer.inputOf", "designer.responseOf",
			"designer.connectorError", "designer.sourceTable", "designer.sourceTable.placeholder", "designer.sourceTable.hint",
			"designer.sourceTable.unavailable", "designer.a11y.keyboardHint", "designer.a11y.pk", "designer.a11y.mapped", "designer.a11y.picked", "designer.a11y.linked",
			"designer.a11y.removed", "designer.a11y.rule", "designer.a11y.stepArgs", "designer.a11y.removeStep", "designer.zoomIn", "designer.zoomOut",
			"designer.dialog.ok", "designer.dialog.cancel", "designer.dialog.deleteRule", "designer.newPolicy.title", "designer.newPolicy.hint",
			"designer.newPolicy.required", "designer.newPolicy.apply", "designer.newPolicy.applied" })
		{
			final ObjectNode l = labels.addObject();
			l.put("key", key);
			l.put("text", MappingDesignerUserService.label(locale, key));
		}
		final ArrayNode external = model.putArray("externalFields");
		if (this.externalFields == null)
		{
			this.externalFields = this.catalogQuietly(pSession);
		}
		if ("OUTBOUND".equals(this.direction))
		{
			this.externalFields = MappingDesignerUserService.withRuleTargets(this.externalFields, this.definition());
		}
		for (final FieldInfo field : this.externalFields)
		{
			final ObjectNode f = external.addObject();
			f.put("path", field.getPath());
			f.put("label", field.getLabel());
			f.put("type", field.getType());
			f.put("sample", field.getSample() == null ? "" : field.getSample());
		}
		final ArrayNode ebx = model.putArray("ebxFields");
		for (final String[] field : this.ebxFieldList(pSession))
		{
			final ObjectNode f = ebx.addObject();
			f.put("path", field[0]);
			f.put("label", field[1]);
			f.put("type", field[2]);
			f.put("group", field[3]);
			f.put("pk", "pk".equals(field[4]));
			f.put("groupPath", field.length > 5 ? field[5] : "");
		}
		final ArrayNode rules = model.putArray("rules");
		// audit C-35: on a feed (or a sub-object of one) the policy of the rules added is a choice the designer asks for
		final java.util.Set<String> keyTargets = this.keyTargets();
		final ArrayNode keys = model.putArray("keyTargets");
		for (final String key : keyTargets)
		{
			keys.add(key);
		}
		model.put("policyChoice", this.isFeedLike());
		String common = null;
		boolean mixed = false;
		try
		{
			for (final MappingRule rule : this.parseRules())
			{
				if (!keyTargets.contains(com.on.ebx.connect.core.RulePolicies.normalTarget(rule.getTargetPath())))
				{
					mixed |= common != null && !common.equals(rule.getPolicy().name());
					common = rule.getPolicy().name();
				}
				final ObjectNode r = rules.addObject();
				r.put("id", rule.getPosition());
				r.put("source", rule.getSourcePath());
				r.put("target", rule.getTargetPath());
				r.put("transformation", rule.getTransformation());
				r.put("policy", rule.getPolicy().name());
				r.put("comment", rule.getComment() == null ? "" : rule.getComment());
			}
		}
		catch (final Exception ex)
		{
			model.put("rulesError", String.valueOf(ex.getMessage()));
		}
		// preselected only when the rules already there agree; a new feed, or mixed rules, start with no choice made
		model.put("defaultPolicy", common == null || mixed ? "" : common);
		final ArrayNode transformations = model.putArray("transformations");
		for (final String[] hint : MappingDesignerUserService.TRANSFORMATION_HINTS)
		{
			final ObjectNode t = transformations.addObject();
			t.put("name", hint[0]);
			t.put("hint", hint[1]);
		}
		for (final String name : Transformations.names())
		{
			boolean known = false;
			for (final String[] hint : MappingDesignerUserService.TRANSFORMATION_HINTS)
			{
				if (hint[0].equalsIgnoreCase(name))
				{
					known = true;
				}
			}
			if (!known)
			{
				final ObjectNode t = transformations.addObject();
				t.put("name", name);
				t.put("hint", name);
			}
		}
		return model;
	}

	/**
	 * Adds a bounded, read-only PostgreSQL object list to the page model. Failure is deliberately
	 * non-fatal: a designer must remain usable when the source is offline, and the existing SQL
	 * expression always remains editable.
	 */
	private void addPostgreSqlSourceObjects(final ObjectNode pModel, final Session pSession, final Connector pConnector)
	{
		final ArrayNode objects = pModel.putArray("sourceObjects");
		if (pConnector == null || !"postgresql".equalsIgnoreCase(pConnector.getType())
			|| !"query".equals(this.mapping.getString(ConnectorsPaths.Mapping.OPERATION)))
		{
			return;
		}
		try
		{
			final ConnectorRepository repository = ConnectorService.get().repository(this.mapping.getHome().getRepository());
			final ConnectorContext context = new ConnectorContext(pSession, repository.getRepository(), null, repository.dataset());
			final ObjectDiscoveryResult discovery = pConnector.discoverObjects(context, ObjectDiscoveryRequest.firstPage("", 500));
			if (!discovery.isSupported())
			{
				pModel.put("sourceDiscoveryError", discovery.getMessage());
				return;
			}
			for (final DiscoveredObject object : discovery.getObjects())
			{
				if (!object.isReadable()) continue;
				final ObjectNode node = objects.addObject();
				node.put("id", object.getId());
				node.put("label", object.getLabel());
				node.put("type", object.getType());
				node.put("query", "SELECT * FROM " + quotePostgreSqlObject(object.getId()) + " LIMIT 100");
			}
		}
		catch (final Exception ex)
		{
			pModel.put("sourceDiscoveryError", com.on.ebx.connect.core.Redaction.redact(
				ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
		}
	}

	private static String quotePostgreSqlObject(final String pIdentifier)
	{
		final String[] parts = pIdentifier.split("\\.");
		final StringBuilder quoted = new StringBuilder();
		for (final String part : parts)
		{
			if (quoted.length() > 0) quoted.append('.');
			quoted.append('"').append(part.replace("\"", "\"\"")).append('"');
		}
		return quoted.toString();
	}

	/** Static catalog of the connector, never failing (the page must render without network). */
	/** The known fields plus, for an outbound mapping, the target of every rule (a document has no catalog: its fields are what the rules write). */
	private static List<FieldInfo> withRuleTargets(final List<FieldInfo> pFields, final MappingDefinition pDefinition)
	{
		final Map<String, FieldInfo> merged = new LinkedHashMap<String, FieldInfo>();
		for (final FieldInfo field : pFields)
		{
			merged.put(field.getPath(), field);
		}
		if (pDefinition != null)
		{
			for (final MappingRule rule : pDefinition.getRules())
			{
				final String target = rule.getTargetPath() == null ? "" : rule.getTargetPath().trim();
				if (!target.isEmpty() && !merged.containsKey(target))
				{
					final int dot = target.lastIndexOf('.');
					merged.put(target, new FieldInfo(target, dot < 0 ? target : target.substring(dot + 1), "string", null));
				}
			}
		}
		return new ArrayList<FieldInfo>(merged.values());
	}

	private List<FieldInfo> catalogQuietly(final Session pSession)
	{
		try
		{
			final MappingDefinition definition = this.definition();
			final ConnectorRepository repo = ConnectorService.get().repository(this.mapping.getHome().getRepository());
			final Connector connector = ConnectorRegistry.get().connector(definition.getConnectorId(), repo);
			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			for (final Map.Entry<String, String> input : definition.getInputs().entrySet())
			{
				if (!input.getValue().contains("${"))
				{
					inputs.put(input.getKey(), input.getValue().trim()); // constants only: no record here
				}
			}
			final List<FieldInfo> catalog = connector.describeFields(new ConnectorContext(pSession, this.mapping.getHome().getRepository(), null, null),
				definition.getOperation(), inputs);
			this.externalFieldsOrigin = catalog.isEmpty() ? "" : "catalog";
			return catalog;
		}
		catch (final Exception ex)
		{
			this.externalFieldsOrigin = "";
			return new ArrayList<FieldInfo>();
		}
	}

	/** Terminal fields of the target table: {path, label, type, group, pk}. */
	private List<String[]> ebxFieldList(final Session pSession)
	{
		final List<String[]> out = new ArrayList<String[]>();
		final Adaptation dataset = this.targetDataset(this.mapping.getHome().getRepository());
		if (dataset == null)
		{
			return out;
		}
		final AdaptationTable table;
		try
		{
			table = dataset.getTable(Path.parse(this.mapping.getString(ConnectorsPaths.Mapping.TABLE_PATH)));
		}
		catch (final RuntimeException ex)
		{
			this.message = "Table " + this.mapping.getString(ConnectorsPaths.Mapping.TABLE_PATH) + " cannot be read in dataset " + dataset.getAdaptationName().getStringName()
					+ " (" + dataset.getHome().getKey().getName() + "): " + ex.getMessage();
			this.messageIsError = true;
			return out;
		}
		final List<String> pk = new ArrayList<String>();
		for (final Path p : table.getPrimaryKeySpec())
		{
			pk.add(p.format());
		}
		MappingDesignerUserService.collect(table.getTableOccurrenceRootNode(), "", "", pk, out, pSession.getLocale());
		return out;
	}

	private static void collect(final SchemaNode pNode, final String pPrefix, final String pGroup, final List<String> pPk,
		final List<String[]> pOut, final Locale pLocale)
	{
		MappingDesignerUserService.collect(pNode, pPrefix, pGroup, "", pPk, pOut, pLocale);
	}

	/** {@code pGroupPath} = labels of the enclosing groups joined by " / ", for the tree. */
	private static void collect(final SchemaNode pNode, final String pPrefix, final String pGroup, final String pGroupPath, final List<String> pPk,
		final List<String[]> pOut, final Locale pLocale)
	{
		for (final SchemaNode child : pNode.getNodeChildren())
		{
			if (child.isAssociationNode())
			{
				continue;
			}
			final String step = child.getPathInAdaptation().getLastStep().format();
			final String path = pPrefix.isEmpty() ? "./" + step : pPrefix + "/" + step;
			if (child.isComplex() && !child.isTerminalValue())
			{
				MappingDesignerUserService.collect(child, path, child.getLabel(pLocale),
					pGroupPath.isEmpty() ? child.getLabel(pLocale) : pGroupPath + " / " + child.getLabel(pLocale), pPk, pOut, pLocale);
				continue;
			}
			if (child.isTerminalValueComputed() || child.isValueFunction())
			{
				continue;
			}
			String type = child.getXsTypeName() == null ? "" : child.getXsTypeName().toString().replace("xs:", "").replace("osd:", "");
			if (child.getFacetOnTableReference() != null)
			{
				type = "fk " + child.getFacetOnTableReference().getTablePath().format();
			}
			if (child.getMaxOccurs() != 1)
			{
				type = "list of " + type;
			}
			pOut.add(new String[] { path, child.getLabel(pLocale), type, pGroup,
				pPk.contains(path.substring(1)) || pPk.contains(path) ? "pk" : "", pGroupPath });
		}
	}

	private static void collectTables(final SchemaNode pNode, final ArrayNode pOut)
	{
		for (final SchemaNode child : pNode.getNodeChildren())
		{
			if (child.isTableNode())
			{
				pOut.add(child.getPathInSchema().format());
			}
			else if (child.isComplex())
			{
				MappingDesignerUserService.collectTables(child, pOut);
			}
		}
	}

	static String label(final Locale pLocale, final String pKey, final Object... pArgs)
	{
		return Messages.get(MappingDesignerUserService.class, pLocale, pKey, pArgs);
	}

	static String escape(final String pText)
	{
		if (pText == null)
		{
			return "";
		}
		return pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
