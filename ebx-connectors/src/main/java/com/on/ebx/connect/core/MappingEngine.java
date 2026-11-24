package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.schema.SchemaTypeName;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Applies mapping rules in both directions, including sub-object mappings (a customer with its
 * addresses, contacts...): inbound, a collection of the document becomes rows of a child table
 * linked to the parent record; outbound, the child rows become an array of the document.
 * Values travel as XML-schema strings so that EBX performs the typing.
 */
public final class MappingEngine
{
	/** Rule results for one document: target path to value, plus the policy of each rule. */
	public static final class Values
	{
		private final Map<String, String> byPath = new LinkedHashMap<String, String>();
		private final Map<String, MappingRule.Policy> policies = new LinkedHashMap<String, MappingRule.Policy>();
		/** Gartner lot A: targets whose value is below the document's confidence threshold, with the steward comment. */
		private final Map<String, String> proposals = new LinkedHashMap<String, String>();
		/** Gartner lot A: provenance text (document, page, snippet) of a target, kept in FieldProvenance.source. */
		private final Map<String, String> sources = new LinkedHashMap<String, String>();
		private final List<String> errors = new ArrayList<String>();
		private TrustOracle oracle;
		private int sourceTrust = -1;
		private String sourceConnector;
		private String tablePath;

		public Map<String, String> getByPath()
		{
			final Map<String, String> report = new LinkedHashMap<String, String>();
			for (final Map.Entry<String, String> e : this.byPath.entrySet())
				report.put(e.getKey(), SampleValueSafety.reportValue(this.tablePath, e.getKey(), e.getValue()));
			return java.util.Collections.unmodifiableMap(report);
		}

		public List<String> getErrors()
		{
			return this.errors;
		}

		/** Targets left as proposals because their confidence is below the document's threshold (target to comment). */
		public Map<String, String> getProposals()
		{
			return java.util.Collections.unmodifiableMap(this.proposals);
		}

		public String get(final String pPath)
		{
			return SampleValueSafety.reportValue(this.tablePath, MappingEngine.normalise(pPath), this.byPath.get(MappingEngine.normalise(pPath)));
		}

		/**
		 * Volume lot 5, task 3: the raw (unredacted) value computed for a path, or {@code null} when no
		 * rule produced one. {@link #get} runs every value through {@link SampleValueSafety#reportValue}
		 * for display/logging, which can mask a genuine value (a sample-connector table); the
		 * {@code unchangedIfEqual} comparison must never compare against a masked placeholder, so it uses
		 * this instead. Package-private: internal engine use only, never for a report shown to a user.
		 */
		String rawGet(final String pPath)
		{
			return this.byPath.get(MappingEngine.normalise(pPath));
		}
	}

	private MappingEngine()
	{
	}

	// ------------------------------------------------------------------ inbound, one record

	/** Evaluates the rules of a mapping on a document: transformations applied, nothing written. */
	public static Values compute(final MappingDefinition pMapping, final JsonNode pDocument, final TransformationContext pContext)
	{
		final Values values = new Values();
		values.tablePath = pMapping.getTablePath();
		if (pContext != null)
		{
			values.oracle = pContext.getOracle();
			values.sourceTrust = pContext.getSourceTrust();
			values.sourceConnector = pContext.getSourceConnector();
		}
		final JsonNode root = pMapping.getResponseRoot().isEmpty() ? pDocument : Json.at(pDocument, pMapping.getResponseRoot());
		if (pContext != null)
		{
			pContext.setDocumentRoot(root);
		}
		// Gartner lot A: a document carrying a confidence envelope (DocumentExtraction) proposes what is below its threshold
		final DocumentExtraction.Envelope envelope = DocumentExtraction.envelope(root);
		for (final MappingRule rule : pMapping.getRules())
		{
			try
			{
				final JsonNode sourceNode = rule.getSourcePath().isEmpty() ? null : Json.at(root, rule.getSourcePath());
				final Object raw = sourceNode == null ? null : Json.toJava(sourceNode);
				final Object value = Transformations.apply(rule.getTransformation(), raw, pContext);
				final String xs = MappingEngine.asXsString(value);
				// Json.at intentionally collapses null/missing. Only direct fields support explicit clearing.
				final boolean sourceClear = root != null && root.isObject() && root.has(rule.getSourcePath())
					&& root.get(rule.getSourcePath()).isNull()
					&& rule.getTransformation().isEmpty() && rule.getPolicy() == MappingRule.Policy.ALWAYS
					&& allowsSourceLabelClear(pMapping.getTablePath(), rule.getTargetPath());
				if (!sourceClear && (xs == null || xs.isEmpty()))
				{
					continue;
				}
				final String target = MappingEngine.normalise(rule.getTargetPath());
				// several rules may feed the same field (fallbacks): the first non-empty wins
				if (!values.byPath.containsKey(target))
				{
					values.byPath.put(target, xs);
					values.policies.put(target, rule.getPolicy());
					if (envelope != null && !rule.getSourcePath().isEmpty())
					{
						final String source = envelope.sourceOf(rule.getSourcePath());
						if (source != null) values.sources.put(target, source);
						if (envelope.isBelow(rule.getSourcePath()))
						{
							values.proposals.put(target, MappingEngine.proposalComment(envelope.confidenceOf(rule.getSourcePath()), envelope.getThreshold(), source));
						}
					}
				}
			}
			catch (final Exception ex)
			{
				values.errors.add(rule.getTargetPath() + ": " + SampleValueSafety.safeFailure(pMapping, ex.getMessage()));
			}
		}
		return values;
	}

	/** Stages the values on an update context; {@code pExisting} null means a record being created. */
	public static MappingResult applyValues(final Values pValues, final Adaptation pExisting, final ValueContextForUpdate pUpdate,
		final SchemaNode pTableNode)
	{
		final MappingResult result = new MappingResult();
		result.getErrors().addAll(pValues.getErrors());
		for (final Map.Entry<String, String> entry : pValues.byPath.entrySet())
		{
			final Path targetPath = Path.parse(entry.getKey());
			final SchemaNode node = pTableNode.getNode(targetPath);
			if (node == null || !node.isTerminalValue())
			{
				result.addError(entry.getKey() + ": not a field of " + pTableNode.getPathInSchema().format());
				continue;
			}
			try
			{
				String currentXs = null;
				final String proposal = pValues.proposals.get(entry.getKey());
				if (proposal != null)
				{
					// Gartner lot A: below the confidence threshold - a proposal for a steward, never a write
					final Object current = pExisting == null ? null : pExisting.get(targetPath);
					currentXs = current == null ? null : node.formatToXsString(current);
					if (java.util.Objects.equals(entry.getValue(), currentXs))
					{
						result.skip();
						continue;
					}
					final TrustOracle.Provenance origin = pExisting == null || pValues.oracle == null ? null : pValues.oracle.provenanceOf(pExisting, entry.getKey());
					result.addConflict(entry.getKey(), SampleValueSafety.reportValue(pValues.tablePath, entry.getKey(), currentXs),
						SampleValueSafety.reportValue(pValues.tablePath, entry.getKey(), entry.getValue()), origin == null ? null : origin.connector,
						origin == null ? (currentXs == null ? 0 : TrustOracle.MANUAL_TRUST) : origin.trust, proposal);
					result.skip();
					continue;
				}
				if (pExisting != null)
				{
					final Object current = pExisting.get(targetPath);
					currentXs = current == null ? null : node.formatToXsString(current);
					final boolean empty = currentXs == null || currentXs.trim().isEmpty();
					final MappingRule.Policy policy = pValues.policies.get(entry.getKey());
					if (policy == MappingRule.Policy.IF_EMPTY && !empty)
					{
						result.skip();
						continue;
					}
					if (java.util.Objects.equals(entry.getValue(), currentXs))
					{
						result.skip();
						continue;
					}
					if (policy == MappingRule.Policy.TRUSTED && !empty)
					{
						// who wrote the current value, and is this source at least as trusted?
						final TrustOracle.Provenance origin = pValues.oracle == null ? null : pValues.oracle.provenanceOf(pExisting, entry.getKey());
						final int currentTrust = origin == null ? TrustOracle.MANUAL_TRUST : origin.trust;
						final int sourceTrust = pValues.sourceTrust < 0 ? 0 : pValues.sourceTrust;
						if (pValues.oracle == null || sourceTrust < currentTrust)
						{
							result.addConflict(entry.getKey(), SampleValueSafety.reportValue(pValues.tablePath, entry.getKey(), currentXs),
								SampleValueSafety.reportValue(pValues.tablePath, entry.getKey(), entry.getValue()), origin == null ? null : origin.connector, currentTrust);
							result.skip();
							continue;
						}
					}
				}
				if (entry.getValue() == null) pUpdate.setValue(null, targetPath);
				else pUpdate.setValueFromXsString(entry.getValue(), targetPath);
				// Stage the original for the classification trigger, but never copy it into reporting.
				result.addChange(entry.getKey(), SampleValueSafety.reportValue(pValues.tablePath, entry.getKey(), currentXs),
					SampleValueSafety.reportValue(pValues.tablePath, entry.getKey(), entry.getValue()), pValues.sources.get(entry.getKey()));
			}
			catch (final Exception ex)
			{
				result.addError(entry.getKey() + ": " + (SampleValueSafety.isSampleTable(pValues.tablePath)
					? "Sample field update failed; value-bearing diagnostic omitted." : ex.getMessage()));
			}
		}
		return result;
	}

	/** The steward comment of a proposal: {@code confidence 0.62 < 0.80 - invoice.pdf p.2: "..."}, at most 500 characters (Conflict.comment). */
	static String proposalComment(final double pConfidence, final double pThreshold, final String pSource)
	{
		final String text = String.format(java.util.Locale.ROOT, "confidence %.2f < %.2f", Double.valueOf(pConfidence), Double.valueOf(pThreshold))
			+ (pSource == null ? "" : " - " + pSource);
		return text.length() > 500 ? text.substring(0, 497) + "..." : text;
	}

	/** Explicit provider absence may clear only these source-owned label values, never curated tags/sensitivity. */
	static boolean allowsSourceLabelClear(final String table, final String field)
	{
		return ("/root/Asset".equals(table) || "/root/Column".equals(table))
			&& ("./sourceRetention".equals(normalise(field)) || "./sourceSensitivity".equals(normalise(field)));
	}

	/**
	 * Volume lot 5, task 3: whether the record already holds, at the mapping's
	 * {@code unchangedIfEqual} field, exactly the value {@code pValues} computed for it. Reads the
	 * current value the same way {@link #applyValues} does ({@code formatToXsString}), so a match here
	 * is the same "no real change" {@link #applyValues} would find for that one field - without
	 * running any write policy, staging any value, opening a procedure, or touching sub-objects.
	 * <p>
	 * Always {@code false} when the mapping has no {@code unchangedIfEqual}, the record does not exist
	 * yet (a creation always counts as a change), the path is not a field of the record, or no rule of
	 * the mapping produced a value for that path - a hash/version field the mapping fails to compute
	 * must never be silently treated as "unchanged".
	 */
	public static boolean unchanged(final MappingDefinition pMapping, final Adaptation pExisting, final Values pValues)
	{
		final String path = pMapping.getUnchangedIfEqual();
		if (path.isEmpty() || pExisting == null)
		{
			return false;
		}
		final String target = MappingEngine.normalise(path);
		final SchemaNode node = pExisting.getSchemaNode().getNode(Path.parse(target));
		if (node == null)
		{
			return false;
		}
		final String incoming = pValues.rawGet(target);
		if (incoming == null)
		{
			return false;
		}
		final Object current = pExisting.get(Path.parse(target));
		final String currentXs = current == null ? null : node.formatToXsString(current);
		return UnchangedRowDetector.sameValue(currentXs, incoming);
	}

	/** Compute and stage the field updates of {@code pMapping} on an existing record. */
	public static MappingResult applyInbound(final MappingDefinition pMapping, final JsonNode pDocument,
		final Adaptation pRecord, final ValueContextForUpdate pUpdate, final TransformationContext pContext)
	{
		return MappingEngine.applyValues(MappingEngine.compute(pMapping, pDocument, pContext), pRecord, pUpdate,
			pRecord.getSchemaNode());
	}

	// ------------------------------------------------------------------ inbound, sub-objects

	/**
	 * Applies the sub-object mappings of {@code pParent} to the collections of {@code pDocument}
	 * (the parent document, before its response root is applied): child rows are matched by the
	 * child key fields, updated or created, linked to the parent, optionally deleted when absent.
	 * Runs inside the caller's procedure.
	 */
	public static void applyChildren(final ProcedureContext pContext, final MappingDefinition pParent, final JsonNode pDocument,
		final Adaptation pParentRecord, final TransformationContext pTransformations, final SyncResult pResult)
	{
		MappingEngine.applyChildren(pContext, pParent, pDocument, pParentRecord, pTransformations, pResult, null, false);
	}

	/**
	 * As above, matching the child elements against rows read beforehand when {@code pPreload} has them ({@link
	 * ChildRowPreload}, audit C-11); {@code pParentIsNew} says that the parent was created by the same procedure. Only the
	 * direct children of the parent use it; their own sub-objects are applied one XPath per element.
	 */
	static void applyChildren(final ProcedureContext pContext, final MappingDefinition pParent, final JsonNode pDocument,
		final Adaptation pParentRecord, final TransformationContext pTransformations, final SyncResult pResult,
		final ChildRowPreload pPreload, final boolean pParentIsNew)
	{
		final JsonNode parentRoot = pParent.getResponseRoot().isEmpty() ? pDocument : Json.at(pDocument, pParent.getResponseRoot());
		for (final MappingDefinition child : pParent.getChildren())
		{
			try
			{
				MappingEngine.applyChild(pContext, child, parentRoot, pParentRecord, pTransformations, pResult, pPreload, pParentIsNew);
			}
			catch (final Exception ex)
			{
				pResult.getErrors().add(child.getCollectionPath() + ": " + SampleValueSafety.safeFailure(child,
					ex.getMessage() == null ? ex.toString() : ex.getMessage()));
			}
		}
	}

	private static void applyChild(final ProcedureContext pContext, final MappingDefinition pChild, final JsonNode pParentRoot,
		final Adaptation pParentRecord, final TransformationContext pTransformations, final SyncResult pResult,
		final ChildRowPreload pPreload, final boolean pParentIsNew) throws Exception
	{
		final Adaptation dataset = pParentRecord.getContainer();
		final AdaptationTable table = dataset.getTable(Path.parse(pChild.getTablePath()));
		final SchemaNode tableNode = table.getTableOccurrenceRootNode();
		final String parentKey = pParentRecord.getOccurrencePrimaryKey().format();
		final String link = MappingEngine.normalise(pChild.getParentLinkField());
		if (link.isEmpty())
		{
			pResult.getErrors().add(pChild.getCollectionPath() + ": parentLinkField is not set");
			return;
		}
		// The rows of this parent, read before the procedure, when they can answer exactly as the XPath would (audit C-11).
		final ChildRowPreload.Child preloaded = pPreload == null ? null : pPreload.child(pChild);
		final ChildRowGroups<Adaptation> groups = preloaded == null ? null : preloaded.groupsFor(parentKey, pParentIsNew);
		final JsonNode array = Json.at(pParentRoot, pChild.getCollectionPath());
		final List<JsonNode> elements = new ArrayList<JsonNode>();
		if (array != null && array.isArray())
		{
			for (final JsonNode element : array)
			{
				elements.add(element);
			}
		}
		else if (array != null && array.isObject())
		{
			elements.add(array); // a single embedded object is a collection of one
		}
		final Set<AdaptationName> matched = new HashSet<AdaptationName>();
		final List<String> keyFields = pChild.getChildKeyFields();
		int index = 0;
		for (final JsonNode element : elements)
		{
			final String prefix = pChild.getCollectionPath() + "[" + index + "]";
			index++;
			final Values values = MappingEngine.compute(pChild, element, pTransformations);
			Adaptation existing = null;
			if (!keyFields.isEmpty())
			{
				boolean asked = false;
				if (groups != null)
				{
					final List<String> texts = new ArrayList<String>(keyFields.size());
					for (final String keyField : keyFields)
					{
						final String value = values.get(keyField);
						if (value == null)
						{
							break; // an incomplete key is never looked up: the element is new
						}
						texts.add(value);
					}
					if (texts.size() < keyFields.size())
					{
						asked = true;
					}
					else
					{
						final BusinessKeyIndex.Match<Adaptation> match = groups.find(parentKey, texts);
						if (match.getKind() != BusinessKeyIndex.Kind.AMBIGUOUS)
						{
							existing = match.getRecord();
							asked = true;
						}
					}
				}
				if (!asked)
				{
					final StringBuilder predicate = new StringBuilder(MappingEngine.predicate(tableNode, link, parentKey));
					boolean complete = true;
					for (final String keyField : keyFields)
					{
						final String value = values.get(keyField);
						if (value == null)
						{
							complete = false;
							break;
						}
						predicate.append(" and ").append(MappingEngine.predicate(tableNode, MappingEngine.normalise(keyField), value));
					}
					if (!pChild.getChildFilter().isEmpty())
					{
						predicate.append(" and (").append(pChild.getChildFilter()).append(')');
					}
					existing = complete ? MappingEngine.first(pContext.getSession(), table, predicate.toString()) : null;
				}
			}
			final Adaptation row;
			if (existing == null)
			{
				final ValueContextForUpdate created = pContext.getContextForNewOccurrence(table);
				created.setValueFromXsString(parentKey, Path.parse(link));
				final MappingResult applied = MappingEngine.applyValues(values, null, created, tableNode);
				MappingEngine.absorb(pResult, applied, prefix, "+");
				row = pContext.doCreateOccurrence(created, table);
				if (preloaded != null)
				{
					preloaded.wrote(null, null, row);
				}
			}
			else
			{
				final ValueContextForUpdate update = pContext.getContext(existing.getAdaptationName());
				final MappingResult applied = MappingEngine.applyValues(values, existing, update, tableNode);
				MappingEngine.absorb(pResult, applied, prefix, "");
				row = applied.hasChanges() ? pContext.doModifyContent(existing, update) : existing;
				if (preloaded != null && applied.hasChanges())
				{
					// also when this parent is asked to EBX: a rule may have linked the row to a parent whose rows are held here
					preloaded.wrote(existing, parentKey, row);
				}
			}
			matched.add(row.getAdaptationName());
			if (!pChild.getChildren().isEmpty())
			{
				MappingEngine.applyChildren(pContext, pChild, element, row, pTransformations, pResult);
			}
		}
		if (pChild.isDeleteMissing())
		{
			final List<Adaptation> stale = new ArrayList<Adaptation>();
			if (groups != null)
			{
				for (final Adaptation r : groups.rows(parentKey))
				{
					if (!matched.contains(r.getAdaptationName()))
					{
						stale.add(r);
					}
				}
			}
			else
			{
				final Request request = table.createRequest();
				request.setSession(pContext.getSession());
				request.setXPathFilter(MappingEngine.predicate(tableNode, link, parentKey)
						+ (pChild.getChildFilter().isEmpty() ? "" : " and (" + pChild.getChildFilter() + ")"));
				final RequestResult rows = request.execute();
				try
				{
					for (Adaptation r = rows.nextAdaptation(); r != null; r = rows.nextAdaptation())
					{
						if (!matched.contains(r.getAdaptationName()))
						{
							stale.add(r);
						}
					}
				}
				finally
				{
					rows.close();
				}
			}
			for (final Adaptation r : stale)
			{
				pContext.doDelete(r.getAdaptationName(), false);
				pResult.getChanges().add(new MappingResult.Change(pChild.getCollectionPath() + " -" , r.getLabel(java.util.Locale.getDefault()), "deleted"));
			}
		}
	}

	// ------------------------------------------------------------------ outbound

	/** Builds the outbound document of {@code pMapping} from the record, sub-objects included. */
	public static ObjectNode buildOutbound(final MappingDefinition pMapping, final Adaptation pRecord,
		final TransformationContext pContext, final MappingResult pResult)
	{
		final ObjectNode document = Json.object();
		for (final MappingRule rule : pMapping.getRules())
		{
			try
			{
				Object raw = null;
				if (!rule.getSourcePath().isEmpty())
				{
					final Path sourcePath = Path.parse(MappingEngine.normalise(rule.getSourcePath()));
					final SchemaNode node = pRecord.getSchemaNode().getNode(sourcePath);
					if (node == null)
					{
						pResult.addError(rule.getSourcePath() + ": not a field of the record");
						continue;
					}
					if (pContext.getSession() == null || pContext.getSession().getPermissions().getNodeAccessPermission(node, pRecord).isHidden())
					{
						pResult.addError(rule.getSourcePath() + ": field is unavailable or restricted");
						continue;
					}
					raw = MappingEngine.recordValue(pRecord, node, sourcePath);
				}
				final Object value = Transformations.apply(rule.getTransformation(), raw, pContext);
				if (value == null)
				{
					pResult.skip();
					continue;
				}
				Json.set(document, rule.getTargetPath(), Json.fromJava(value));
				pResult.addChange(rule.getTargetPath(), null, MappingEngine.asXsString(value));
			}
			catch (final Exception ex)
			{
				pResult.addError(rule.getTargetPath() + ": " + ex.getMessage());
			}
		}
		for (final MappingDefinition child : pMapping.getChildren())
		{
			try
			{
				MappingEngine.buildChild(child, pRecord, document, pContext, pResult);
			}
			catch (final Exception ex)
			{
				pResult.addError(child.getCollectionPath() + ": " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
			}
		}
		return document;
	}

	private static void buildChild(final MappingDefinition pChild, final Adaptation pParentRecord, final ObjectNode pParentDocument,
		final TransformationContext pContext, final MappingResult pResult)
	{
		final AdaptationTable table = pParentRecord.getContainer().getTable(Path.parse(pChild.getTablePath()));
		final SchemaNode tableNode = table.getTableOccurrenceRootNode();
		final String link = MappingEngine.normalise(pChild.getParentLinkField());
		final Request request = table.createRequest();
		request.setSession(pContext.getSession());
		request.setXPathFilter(MappingEngine.predicate(tableNode, link, pParentRecord.getOccurrencePrimaryKey().format())
				+ (pChild.getChildFilter().isEmpty() ? "" : " and (" + pChild.getChildFilter() + ")"));
		final RequestResult rows = request.execute();
		int count = 0;
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final MappingResult ignored = new MappingResult();
				final ObjectNode element = MappingEngine.buildOutbound(pChild, row,
					new TransformationContext(row.getContainer(), row, pContext.getSession()), ignored);
				pResult.getErrors().addAll(ignored.getErrors());
				Json.set(pParentDocument, pChild.getCollectionPath() + "[]", element);
				count++;
			}
		}
		finally
		{
			rows.close();
		}
		pResult.addChange(pChild.getCollectionPath(), null, count + " element(s)");
	}

	// ------------------------------------------------------------------ helpers

	private static void absorb(final SyncResult pResult, final MappingResult pApplied, final String pPrefix, final String pMark)
	{
		for (final MappingResult.Change change : pApplied.getChanges())
		{
			pResult.getChanges().add(new MappingResult.Change(pPrefix + pMark + " " + change.getPath(), change.getOldValue(), change.getNewValue()));
		}
		for (final String error : pApplied.getErrors())
		{
			pResult.getErrors().add(pPrefix + " " + error);
		}
	}

	static Adaptation first(final com.orchestranetworks.service.Session pSession, final AdaptationTable pTable, final String pPredicate)
	{
		final Request request = pTable.createRequest();
		request.setSession(pSession);
		request.setXPathFilter(pPredicate);
		final RequestResult rows = request.execute();
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	/** {@code ./field='value'}, quoting according to the field type. */
	static String predicate(final SchemaNode pTableNode, final String pPath, final String pXsValue)
	{
		final SchemaNode node = pTableNode.getNode(Path.parse(pPath));
		final String path = pPath.startsWith("./") ? pPath : "./" + pPath.replaceFirst("^/", "");
		if (node != null && (SchemaTypeName.XS_INT.equals(node.getXsTypeName()) || SchemaTypeName.XS_INTEGER.equals(node.getXsTypeName())
			|| SchemaTypeName.XS_DECIMAL.equals(node.getXsTypeName()) || SchemaTypeName.XS_BOOLEAN.equals(node.getXsTypeName()))
			&& node.getFacetOnTableReference() == null)
		{
			final boolean valid = SchemaTypeName.XS_BOOLEAN.equals(node.getXsTypeName())
				? pXsValue.matches("true|false|0|1")
				: SchemaTypeName.XS_DECIMAL.equals(node.getXsTypeName())
					? pXsValue.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
					: pXsValue.matches("[+-]?[0-9]+");
			if (!valid) throw new IllegalArgumentException("Invalid typed predicate value");
			return path + "=" + pXsValue;
		}
		return path + "=" + Xpath.quote(pXsValue);
	}

	static String normalise(final String pPath)
	{
		if (pPath == null)
		{
			return "";
		}
		final String p = pPath.trim();
		if (p.isEmpty() || p.startsWith("./"))
		{
			return p;
		}
		return p.startsWith("/") ? "." + p : "./" + p;
	}

	private static Object recordValue(final Adaptation pRecord, final SchemaNode pNode, final Path pPath)
	{
		final Object value = pRecord.get(pPath);
		if (value == null)
		{
			return null;
		}
		if (value instanceof List)
		{
			final List<Object> out = new ArrayList<Object>();
			for (final Object item : (List<?>) value)
			{
				out.add(item instanceof java.util.Date || item instanceof Number ? pNode.formatToXsString(item) : item);
			}
			return out;
		}
		if (value instanceof Boolean || value instanceof java.math.BigDecimal || value instanceof Integer)
		{
			return value;
		}
		return pNode.isTerminalValue() ? pNode.formatToXsString(value) : String.valueOf(value);
	}

	private static String asXsString(final Object pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		if (pValue instanceof Boolean)
		{
			return pValue.toString();
		}
		return Transformations.text(pValue);
	}
}
