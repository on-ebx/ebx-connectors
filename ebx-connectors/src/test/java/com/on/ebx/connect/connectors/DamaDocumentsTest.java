package com.on.ebx.connect.connectors;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.DamaAssets;
import com.on.ebx.connect.core.DamaProposals;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.MappingResult;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Session;

/**
 * The documents connector on DAMA documents, offline, with a stub of the add-on: reading by record and by UUID, a hidden record, a
 * read-only field, the size cap, an image refused, extractAll over a table; annotate writing metadata and adding tags from the
 * vocabulary only (never created, existing tags kept), IF_EMPTY / ALWAYS, unknown and computed metadata types, low confidence kept as
 * dama: proposals on the record, no record, dry run, a failed update; and the dama: proposal paths the conflict manager accepts.
 */
public final class DamaDocumentsTest
{
	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition) throw new AssertionError(pMessage);
	}

	interface Run
	{
		void run() throws Exception;
	}

	private static void rejects(final String pReason, final Run pRun) throws Exception
	{
		try
		{
			pRun.run();
		}
		catch (final ConnectorException ex)
		{
			check(ex.getMessage().contains(pReason), "expected '" + pReason + "', got: " + ex.getMessage());
			return;
		}
		throw new AssertionError("accepted, expected refusal: " + pReason);
	}

	static Session session(final String pName)
	{
		return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] { Session.class }, (proxy, method, args) ->
		{
			if ("toString".equals(method.getName())) return pName;
			if ("hashCode".equals(method.getName())) return Integer.valueOf(pName.hashCode());
			if ("equals".equals(method.getName())) return Boolean.valueOf(proxy == args[0]);
			return null;
		});
	}

	// ------------------------------------------------------------------ the stub of DAMA

	static final class StubAsset
	{
		String label;
		String extension;
		String type;
		byte[] content;
		final Map<String, String> metadata = new LinkedHashMap<String, String>();
		final List<String> tags = new ArrayList<String>();
	}

	static final class Stub implements DamaAssets
	{
		final Map<String, StubAsset> assets = new HashMap<String, StubAsset>();
		/** record key to asset UUID */
		final Map<String, String> records = new LinkedHashMap<String, String>();
		final Set<String> hidden = new HashSet<String>();
		final Set<String> readOnly = new HashSet<String>();
		final List<DamaAssets.Tag> vocabulary = new ArrayList<DamaAssets.Tag>();
		final Map<String, Boolean> types = new LinkedHashMap<String, Boolean>();
		final List<Session> openedWith = new ArrayList<Session>();
		final List<Session> updatedWith = new ArrayList<Session>();
		final List<MappingResult.Conflict> proposed = new ArrayList<MappingResult.Conflict>();
		/** record key to the values of its list field {@link #TAGS_FIELD} */
		final Map<String, List<String>> recordTags = new HashMap<String, List<String>>();
		final List<Session> taggedWith = new ArrayList<Session>();
		int updates;
		String failUpdate;

		@Override
		public boolean available()
		{
			return true;
		}

		@Override
		public Asset open(final Repository pRepository, final Session pSession, final Ref pRef, final boolean pForWrite) throws ConnectorException
		{
			this.openedWith.add(pSession);
			if (pRef.table == null || pRef.field == null) throw new ConnectorException("a DAMA document needs dataspace, dataset, table and field");
			String uuid = pRef.uuid;
			if (pRef.record != null)
			{
				if (!this.records.containsKey(pRef.record) || this.hidden.contains(pRef.record)) throw new ConnectorException("no record " + pRef.record + " in " + pRef.table);
				if (pForWrite && this.readOnly.contains(pRef.record)) throw new ConnectorException("changing the document's metadata or tags needs the right to change " + pRef.field);
				uuid = this.records.get(pRef.record);
			}
			final StubAsset a = this.assets.get(uuid);
			if (a == null) throw new ConnectorException("no DAMA asset " + uuid);
			return new Asset(pRef, uuid, a.label, a.extension, a.type, Long.valueOf(a.content.length), "2026-11-01T00:00:00Z", "Record " + pRef.record, a.metadata, a.tags,
				pSession);
		}

		@Override
		public byte[] content(final Asset pAsset, final long pMaxBytes) throws ConnectorException
		{
			return this.assets.get(pAsset.uuid).content;
		}

		@Override
		public List<Tag> vocabulary(final Session pSession, final Asset pAsset)
		{
			return this.vocabulary;
		}

		@Override
		public Map<String, Boolean> metadataTypes(final Session pSession, final Asset pAsset)
		{
			return this.types;
		}

		@Override
		public void update(final Asset pAsset, final Map<String, String> pMetadata) throws ConnectorException
		{
			if (pMetadata.isEmpty()) return;
			if (this.failUpdate != null) throw new ConnectorException("DAMA refused the update of " + pAsset.label + ": " + this.failUpdate);
			this.updates++;
			this.updatedWith.add((Session) pAsset.handle);
			final StubAsset a = this.assets.get(pAsset.uuid);
			a.metadata.putAll(pMetadata);
		}

		@Override
		public List<String> recordList(final Asset pAsset, final String pField, final boolean pForWrite) throws ConnectorException
		{
			if (pAsset.ref.record == null) throw new ConnectorException("tags are kept in " + pField + " of the record that holds the document: give a record, not only an asset");
			if (!TAGS_FIELD.equals(pField)) throw new ConnectorException(pField + " must be a list field (maxOccurs > 1) to keep the document's tags");
			if (pForWrite && this.readOnly.contains(pAsset.ref.record)) throw new ConnectorException("adding the document's tags needs the right to change " + pField);
			return new ArrayList<String>(this.recordTags.computeIfAbsent(pAsset.ref.record, k -> new ArrayList<String>()));
		}

		@Override
		public void addToRecordList(final Asset pAsset, final String pField, final List<String> pValues) throws ConnectorException
		{
			if (pValues.isEmpty()) return;
			this.recordList(pAsset, pField, true);
			this.taggedWith.add((Session) pAsset.handle);
			final List<String> list = this.recordTags.get(pAsset.ref.record);
			for (final String value : pValues) if (!list.contains(value)) list.add(value);
		}

		@Override
		public List<Ref> records(final Repository pRepository, final Session pSession, final Ref pTable, final String pFilter, final int pMax)
		{
			final List<Ref> out = new ArrayList<Ref>();
			for (final String key : this.records.keySet())
			{
				if (!this.hidden.contains(key) && out.size() < pMax) out.add(pTable.withRecord(key));
			}
			return out;
		}

		@Override
		public boolean propose(final Session pSession, final Asset pAsset, final ConnectorConfig pConnector, final List<MappingResult.Conflict> pConflicts)
		{
			if (pAsset.ref.record == null) return true;
			this.proposed.addAll(pConflicts);
			return true;
		}
	}

	static final String TAGS_FIELD = "./documentTags";
	private static final String TEXT = "Invoice n° INV-42\nTotal: 1234.50 EUR\nServices of October for ACME.";
	private static final String UUID_1 = "11111111-aaaa-bbbb-cccc-000000000001";
	private static final String UUID_IMG = "11111111-aaaa-bbbb-cccc-000000000002";

	static Stub stub()
	{
		final Stub stub = new Stub();
		final StubAsset invoice = new StubAsset();
		invoice.label = "invoice-42.txt";
		invoice.extension = "txt";
		invoice.type = "text/plain";
		invoice.content = TEXT.getBytes(StandardCharsets.UTF_8);
		stub.assets.put(UUID_1, invoice);
		stub.recordTags.put("42", new ArrayList<String>(Arrays.asList("ARCHIVE")));
		final StubAsset image = new StubAsset();
		image.label = "photo.jpg";
		image.extension = "jpg";
		image.type = "image/jpeg";
		image.content = new byte[] { 1, 2, 3 };
		stub.assets.put(UUID_IMG, image);
		stub.records.put("42", UUID_1);
		stub.records.put("43", UUID_IMG);
		stub.vocabulary.add(new DamaAssets.Tag("INVOICE", "Invoice"));
		stub.vocabulary.add(new DamaAssets.Tag("CONTRACT", "Contract"));
		stub.vocabulary.add(new DamaAssets.Tag("ARCHIVE", "Archived"));
		stub.types.put("INVOICE_NO", Boolean.TRUE);
		stub.types.put("TOTAL", Boolean.TRUE);
		stub.types.put("PAGE_COUNT", Boolean.FALSE);
		return stub;
	}

	static ConnectorConfig config()
	{
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("docs-dama");
		config.setType("documents");
		config.setTrust(30);
		config.setProperty("fields", "invoiceNumber:the invoice number, total:amount:decimal");
		config.setProperty("confidence.threshold", "0.8");
		config.setProperty("dama.dataspace", "Products");
		config.setProperty("dama.dataset", "Products");
		config.setProperty("dama.table", "/root/Invoice");
		config.setProperty("dama.field", "./document");
		config.setProperty("dama.tags.field", TAGS_FIELD);
		return config;
	}

	private static Map<String, String> inputs(final String... pPairs)
	{
		final Map<String, String> out = new HashMap<String, String>();
		for (int i = 0; i < pPairs.length; i += 2) out.put(pPairs[i], pPairs[i + 1]);
		return out;
	}

	/** The model's answer: invoiceNumber sure, total unsure, tags INVOICE sure, CONTRACT unsure, BOGUS outside the vocabulary. */
	private static final String ANSWER = "{\"fields\": {"
		+ "\"invoiceNumber\": {\"value\": \"INV-42\", \"confidence\": 0.95, \"page\": 1, \"snippet\": \"Invoice n° INV-42\"},"
		+ "\"total\": {\"value\": 1234.50, \"confidence\": 0.6, \"page\": 1, \"snippet\": \"Total: 1234.50 EUR\"}},"
		+ "\"tags\": [{\"code\": \"INVOICE\", \"confidence\": 0.9, \"snippet\": \"Invoice n° INV-42\"},"
		+ "{\"code\": \"CONTRACT\", \"confidence\": 0.7, \"snippet\": \"Services of October\"},"
		+ "{\"code\": \"BOGUS\", \"confidence\": 0.99, \"snippet\": \"ACME\"}]}";

	public static void main(final String[] args) throws Exception
	{
		final Session user = DamaDocumentsTest.session("user");
		final ConnectorContext context = new ConnectorContext(user, null, null, null);
		final AtomicReference<String> prompt = new AtomicReference<String>();
		final DocumentConnector.Model model = (pSystem, pPrompt) ->
		{
			prompt.set(pPrompt);
			try
			{
				return Json.parse(ANSWER);
			}
			catch (final java.io.IOException ex)
			{
				throw new ConnectorException("answer: " + ex.getMessage());
			}
		};

		// ---------------------------------------------------------- reading
		Stub stub = DamaDocumentsTest.stub();
		DocumentConnector connector = new DocumentConnector().withModel(model).withDama(stub);
		connector.configure(DamaDocumentsTest.config());
		final JsonNode text = connector.execute(context, "text", inputs("record", "42"), null);
		check(text.path("document").asText().equals("invoice-42.txt") && text.path("pages").get(0).path("text").asText().contains("INV-42"), "text by record: " + text);
		check(stub.openedWith.get(0) == user, "opened with the caller's session");
		final JsonNode extracted = connector.execute(context, "extract", inputs("record", "42"), null);
		check("INV-42".equals(extracted.path("invoiceNumber").asText()) && UUID_1.equals(extracted.path("asset").asText())
			&& "42".equals(extracted.path("record").asText()) && "./document".equals(extracted.path("field").asText()), "extract by record carries the asset and record: " + extracted);
		final JsonNode byUuid = connector.execute(context, "text", inputs("asset", UUID_1), null);
		check(byUuid.path("chars").asInt() > 0, "text by UUID");
		rejects("no record 44", () -> connector.execute(context, "text", inputs("record", "44"), null));
		stub.hidden.add("42");
		rejects("no record 42", () -> connector.execute(context, "text", inputs("record", "42"), null));
		stub.hidden.clear();
		rejects("no OCR and no vision", () -> connector.execute(context, "extract", inputs("record", "43"), null));
		rejects("with the caller's session", () -> connector.execute(null, "text", inputs("record", "42"), null));
		final ConnectorConfig small = DamaDocumentsTest.config();
		small.setProperty("max.bytes", "1024");
		stub.assets.get(UUID_1).content = new byte[5000];
		final DocumentConnector capped = new DocumentConnector().withModel(model).withDama(stub);
		capped.configure(small);
		rejects("above max.bytes", () -> capped.execute(context, "text", inputs("record", "42"), null));
		stub.assets.get(UUID_1).content = TEXT.getBytes(StandardCharsets.UTF_8);

		// an upload never touches DAMA
		final Stub untouched = new Stub();
		final DocumentConnector upload = new DocumentConnector().withModel(model).withDama(untouched);
		upload.configure(DamaDocumentsTest.config());
		upload.execute(null, "text", inputs("content", Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)), "name", "a.txt"), null);
		check(untouched.openedWith.isEmpty(), "an upload does not open DAMA");

		// extractAll over the table: the image is reported, the invoice extracted
		final JsonNode all = connector.execute(context, "extractAll", inputs(), null);
		check(all.path("count").asInt() == 1 && "43".equals(all.path("errors").get(0).path("record").asText()), "extractAll over the DAMA table: " + all);

		// ---------------------------------------------------------- annotate
		rejects("nothing to write", () -> connector.execute(context, "annotate", inputs("record", "42"), null));
		rejects("needs a DAMA document", () -> connector.execute(context, "annotate", inputs("metadata", "invoiceNumber:INVOICE_NO"), null));
		rejects("not in fields", () -> connector.execute(context, "annotate", inputs("record", "42", "metadata", "vendor:VENDOR"), null));
		rejects("no metadata type VENDOR", () -> connector.execute(context, "annotate", inputs("record", "42", "fields", "vendor:the vendor", "metadata", "vendor:VENDOR"), null));
		rejects("not entered by hand", () -> connector.execute(context, "annotate", inputs("record", "42", "metadata", "invoiceNumber:PAGE_COUNT"), null));
		stub.readOnly.add("42");
		rejects("needs the right to change", () -> connector.execute(context, "annotate", inputs("record", "42", "tags", "true"), null));
		stub.readOnly.clear();

		final JsonNode annotated = connector.execute(context, "annotate", inputs("record", "42", "metadata", "invoiceNumber:INVOICE_NO, total:TOTAL", "tags", "true"), null);
		final StubAsset invoice = stub.assets.get(UUID_1);
		check("INV-42".equals(invoice.metadata.get("INVOICE_NO")), "a sure value is written as metadata: " + invoice.metadata);
		check(!invoice.metadata.containsKey("TOTAL"), "an unsure value is not written");
		check(stub.recordTags.get("42").equals(Arrays.asList("ARCHIVE", "INVOICE")), "the sure tag is added to the record's list field, the existing one kept: " + stub.recordTags);
		check(invoice.tags.isEmpty() && stub.taggedWith.get(0) == user && TAGS_FIELD.equals(annotated.path("written").path("tagsField").asText()),
			"the asset is not tagged (DAMA's API cannot), the record is, with the caller's session: " + annotated.path("written"));
		check(annotated.path("rejectedTags").toString().equals("[\"BOGUS\"]"), "a code outside the vocabulary is rejected, never created: " + annotated.path("rejectedTags"));
		check(stub.vocabulary.size() == 3, "the vocabulary is never extended");
		check(prompt.get().contains("<tags>") && prompt.get().contains("- INVOICE: Invoice") && prompt.get().contains("only from this closed list"), "the vocabulary goes to the model as a closed list");
		check(stub.proposed.size() == 2, "two proposals: " + stub.proposed);
		final DamaProposals.Target total = DamaProposals.parse(stub.proposed.get(0).path);
		check(total != null && "metadata".equals(total.kind) && "TOTAL".equals(total.code) && UUID_1.equals(total.uuid) && "./document".equals(total.field)
			&& "1234.5".equals(stub.proposed.get(0).proposedValue), "the total is a dama: metadata proposal: " + stub.proposed.get(0).path + " = " + stub.proposed.get(0).proposedValue);
		check(stub.proposed.get(0).comment.startsWith("confidence 0.60 < 0.80 - invoice-42.txt p.1"), "the steward comment: " + stub.proposed.get(0).comment);
		check(TAGS_FIELD.equals(stub.proposed.get(1).path) && "CONTRACT".equals(stub.proposed.get(1).proposedValue) && stub.proposed.get(1).comment.startsWith("confidence 0.70 < 0.80"),
			"the unsure tag is an ordinary proposal on the record's list field: " + stub.proposed.get(1).path);
		check("conflict manager".equals(annotated.path("proposedTo").asText()) && annotated.path("pending").size() == 2, "pending reported: " + annotated);
		check(stub.updatedWith.get(0) == user, "written with the caller's session");

		// IF_EMPTY keeps a value already there, ALWAYS replaces it
		invoice.metadata.put("INVOICE_NO", "OLD-1");
		final JsonNode keep = connector.execute(context, "annotate", inputs("record", "42", "metadata", "invoiceNumber:INVOICE_NO"), null);
		check("OLD-1".equals(invoice.metadata.get("INVOICE_NO")) && keep.path("kept").size() == 1, "IF_EMPTY keeps the existing value: " + keep.path("kept"));
		connector.execute(context, "annotate", inputs("record", "42", "metadata", "invoiceNumber:INVOICE_NO", "policy", "ALWAYS"), null);
		check("INV-42".equals(invoice.metadata.get("INVOICE_NO")), "ALWAYS replaces it");

		// dry run: nothing written, nothing proposed
		final int updates = stub.updates;
		final int proposals = stub.proposed.size();
		invoice.metadata.clear();
		final JsonNode dry = connector.execute(context, "annotate", inputs("record", "42", "metadata", "invoiceNumber:INVOICE_NO", "dryRun", "true"), null);
		check(stub.updates == updates && stub.proposed.size() == proposals && invoice.metadata.isEmpty() && dry.path("dryRun").asBoolean()
			&& "INV-42".equals(dry.path("written").path("metadata").path("INVOICE_NO").asText()), "a dry run writes nothing: " + dry);

		// by UUID only: the sure values are written, the proposals only returned
		final JsonNode noRecord = connector.execute(context, "annotate", inputs("asset", UUID_1, "metadata", "total:TOTAL"), null);
		check(stub.proposed.size() == proposals && noRecord.path("proposedTo").asText().startsWith("none") && noRecord.path("pending").size() == 1, "no record, no conflict: " + noRecord);

		// a failed update is an error, and no proposal is kept
		stub.failUpdate = "drive is read-only";
		invoice.metadata.clear();
		rejects("drive is read-only", () -> connector.execute(context, "annotate", inputs("record", "42", "metadata", "invoiceNumber:INVOICE_NO, total:TOTAL"), null));
		check(stub.proposed.size() == proposals, "no proposal after a failed update");
		stub.failUpdate = null;

		// the vocabulary: empty, or too large, is refused rather than cut
		final List<DamaAssets.Tag> saved = new ArrayList<DamaAssets.Tag>(stub.vocabulary);
		stub.vocabulary.clear();
		rejects("no tag is ever created", () -> connector.execute(context, "annotate", inputs("record", "42", "tags", "true"), null));
		for (int i = 0; i < 301; i++) stub.vocabulary.add(new DamaAssets.Tag("T" + i, "Tag " + i));
		rejects("above dama.tags.vocabulary.max 300", () -> connector.execute(context, "annotate", inputs("record", "42", "tags", "true"), null));
		stub.vocabulary.clear();
		stub.vocabulary.addAll(saved);

		// a document with no text: no model call, nothing written
		invoice.content = "   ".getBytes(StandardCharsets.UTF_8);
		final int before = stub.updates;
		prompt.set(null);
		final JsonNode blank = connector.execute(context, "annotate", inputs("record", "42", "tags", "true"), null);
		check(prompt.get() == null && stub.updates == before && blank.path("note").asText().contains("OCR"), "no text, no call, nothing written: " + blank);
		invoice.content = TEXT.getBytes(StandardCharsets.UTF_8);

		// threshold 1: a model sure of everything (1.0, snippets found) still writes nothing, everything waits for a steward
		final String sure = "{\"fields\": {"
			+ "\"invoiceNumber\": {\"value\": \"INV-42\", \"confidence\": 1.0, \"page\": 1, \"snippet\": \"Invoice n° INV-42\"}},"
			+ "\"tags\": [{\"code\": \"INVOICE\", \"confidence\": 1, \"snippet\": \"Invoice n° INV-42\"}]}";
		final DocumentConnector certain = new DocumentConnector().withModel((pSystem, pPrompt) ->
		{
			try
			{
				return Json.parse(sure);
			}
			catch (final java.io.IOException ex)
			{
				throw new ConnectorException("answer: " + ex.getMessage());
			}
		}).withDama(stub);
		certain.configure(DamaDocumentsTest.config());
		invoice.metadata.clear();
		stub.recordTags.get("42").remove("INVOICE");
		final int updatesBefore = stub.updates;
		final int proposalsBefore = stub.proposed.size();
		final JsonNode strict = certain.execute(context, "annotate", inputs("record", "42", "metadata", "invoiceNumber:INVOICE_NO", "tags", "true", "threshold", "1"), null);
		check(stub.updates == updatesBefore && invoice.metadata.isEmpty() && !stub.recordTags.get("42").contains("INVOICE"), "threshold 1 writes nothing: " + strict.path("written"));
		check(stub.proposed.size() == proposalsBefore + 2 && strict.path("pending").size() == 2, "threshold 1 proposes the value and the tag: " + strict.path("pending"));
		check(stub.proposed.get(proposalsBefore).comment.startsWith("confidence 0.99 < 1.00"), "the comment says why: " + stub.proposed.get(proposalsBefore).comment);
		certain.execute(context, "annotate", inputs("record", "42", "metadata", "invoiceNumber:INVOICE_NO", "tags", "true", "threshold", "0.99"), null);
		check("INV-42".equals(invoice.metadata.get("INVOICE_NO")) && stub.recordTags.get("42").contains("INVOICE"), "at 0.99 the same answer is written (at or above the threshold writes)");

		// without dama.tags.field the tags are only returned; a field that is not a list, or no record, is refused
		final ConnectorConfig noField = DamaDocumentsTest.config();
		noField.setProperty("dama.tags.field", null);
		final DocumentConnector returnsOnly = new DocumentConnector().withModel(model).withDama(stub);
		returnsOnly.configure(noField);
		final int proposalsNow = stub.proposed.size();
		final List<String> tagsNow = new ArrayList<String>(stub.recordTags.get("42"));
		final JsonNode onlyReturned = returnsOnly.execute(context, "annotate", inputs("record", "42", "tags", "true"), null);
		check(onlyReturned.path("tags").size() == 2 && onlyReturned.path("written").path("tags").size() == 0 && onlyReturned.path("tagsNote").asText().contains("dama.tags.field")
			&& stub.proposed.size() == proposalsNow && stub.recordTags.get("42").equals(tagsNow), "no tags field: tags returned, nothing written or proposed: " + onlyReturned);
		rejects("must be a list field", () -> connector.execute(context, "annotate", inputs("record", "42", "tags", "true", "tagsField", "./title"), null));
		rejects("give a record", () -> connector.execute(context, "annotate", inputs("asset", UUID_1, "tags", "true"), null));

		// options
		check(DocumentConnector.maxTags("true") == DocumentConnector.DEFAULT_MAX_TAGS && DocumentConnector.maxTags("max:3") == 3 && DocumentConnector.maxTags(null) == 0, "tags option");
		rejects("", () -> { try { DocumentConnector.maxTags("lots"); } catch (final IllegalArgumentException ex) { throw new ConnectorException(ex.getMessage()); } });
		check(DocumentConnector.text(Json.parse("1234.5")).equals("1234.5") && DocumentConnector.text(Json.parse("42")).equals("42") && DocumentConnector.text(Json.parse("\"a\"")).equals("a"), "metadata values as text");
		check(!connector.validateConfiguration(DamaDocumentsTest.with("metadata.policy", "SOMETIMES")).getIssues().isEmpty(), "metadata.policy is checked");
		check(!connector.validateConfiguration(DamaDocumentsTest.with("dama.metadata", "invoiceNumber")).getIssues().isEmpty(), "dama.metadata is checked");

		// ---------------------------------------------------------- dama: proposals, as the conflict manager accepts them
		final String path = DamaProposals.path("./document", UUID_1, DamaProposals.TAG, "CONTRACT");
		check(DamaProposals.isAssetPath(path) && "document (DAMA tag): CONTRACT".equals(DamaProposals.display(path)), "display: " + DamaProposals.display(path));
		check(DamaProposals.parse("./document") == null && DamaProposals.parse("dama:x/y") == null, "not a DAMA proposal");
		final Session steward = DamaDocumentsTest.session("steward");
		final DamaAssets.Asset asStewardSees = stub.open(null, steward, new DamaAssets.Ref("Products", "Products", "/root/Invoice", "42", "./document", null), true);
		// a dama: tag proposal can only come from an earlier version: accepting it is refused, DAMA would not keep it
		rejects("cannot add a tag to an asset", () -> DamaProposals.apply(stub, asStewardSees, DamaProposals.parse(path), "CONTRACT"));
		final DamaProposals.Target totalTarget = DamaProposals.parse(DamaProposals.path("./document", UUID_1, DamaProposals.METADATA, "TOTAL"));
		check(!DamaProposals.holds(asStewardSees, totalTarget, "1234.5"), "the metadata value is not there yet");
		DamaProposals.apply(stub, asStewardSees, totalTarget, "1234.5");
		check("1234.5".equals(invoice.metadata.get("TOTAL")) && stub.updatedWith.get(stub.updatedWith.size() - 1) == steward, "accepting writes the metadata with the steward's session");
		final DamaProposals.Target other = DamaProposals.parse(DamaProposals.path("./document", "another-uuid", DamaProposals.METADATA, "TOTAL"));
		rejects("changed since the proposal", () -> DamaProposals.apply(stub, asStewardSees, other, "1"));

		System.out.println("PASS DamaDocumentsTest");
	}

	private static ConnectorConfig with(final String pProperty, final String pValue)
	{
		final ConnectorConfig config = DamaDocumentsTest.config();
		config.setProperty(pProperty, pValue);
		return config;
	}
}
