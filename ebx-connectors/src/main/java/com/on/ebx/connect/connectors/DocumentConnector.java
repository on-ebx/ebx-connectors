package com.on.ebx.connect.connectors;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.ConnectorService;
import com.on.ebx.connect.core.DamaAssets;
import com.on.ebx.connect.core.DamaProposals;
import com.on.ebx.connect.core.DocumentExtraction;
import com.on.ebx.connect.core.DocumentText;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.MappingResult;
import com.on.ebx.connect.core.SampleDataUrl;
import com.on.ebx.connect.spi.AuthenticationMethod;
import com.on.ebx.connect.spi.ConfigurationPropertyDefinition;
import com.on.ebx.connect.spi.ConfigurationPropertyType;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorCapability;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorDirection;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.Operation;

/**
 * Unstructured document feed (Gartner lot A): reads PDF, DOCX and plain-text documents, asks the
 * module's LLM connector for the attributes of a target table, and returns one item per document that a
 * normal inbound mapping imports. Each attribute comes with a confidence and the words of the document
 * that state it; the item's confidence envelope ({@link DocumentExtraction}) makes the mapping engine
 * write only what is at or above the threshold and leave the rest as proposals in the conflict manager,
 * with the document, page and snippet kept in {@code FieldProvenance.source}.
 * <p>
 * Where documents come from, by priority: input {@code content} (base64 of an uploaded file, with input
 * {@code name}), input {@code path} (a file of the connector's {@code folder}), or the document uploaded
 * on the connector row ({@code sampleData}, for the designer and tests). A document of the EBX Digital Asset Manager add-on (DAMA) is
 * read from the media field of a record (inputs {@code dataspace, dataset, table, record, field}, defaults in the {@code dama.*}
 * properties) or by its UUID (input {@code asset}), under the caller's session; operation {@code annotate} writes what is extracted
 * back to the asset as DAMA metadata and tags (docs/DAMA-DOCUMENTS.md). A {@code folder} must sit inside
 * one of the roots listed by the JVM property {@code -Debx.connectors.documents.roots} (separated by
 * {@code ,} or {@code ;}): without it, no server folder is read at all. Symbolic links that leave the
 * folder, hidden files and sub-folders are refused.
 * <p>
 * Properties: {@code llm.connector} (id of an {@code llm} connector, required for extract), {@code fields}
 * (default attribute list, {@code name:description:type, ...}), {@code confidence.threshold} (0.8),
 * {@code folder}, {@code max.bytes} (per document, 20 MB, ceiling 50 MB), {@code max.files} (100, ceiling
 * 1000), {@code max.chars} (text sent to the model, 60000), {@code language}. This connector opens no
 * network connection itself: the text goes to the model through the LLM connector, under that
 * connector's egress rules and secrets.
 */
public final class DocumentConnector implements Connector
{
	/** JVM property: the server folders a document connector may read. */
	public static final String ROOTS_PROPERTY = "ebx.connectors.documents.roots";
	static final int DEFAULT_MAX_BYTES = 20 * 1024 * 1024;
	static final int CEILING_MAX_BYTES = 50 * 1024 * 1024;

	/** The model behind {@code extract}: the LLM connector in production, a stub in tests. */
	public interface Model
	{
		/** The JSON answer of the model to one extraction prompt. */
		JsonNode ask(String pSystem, String pPrompt) throws ConnectorException;

		/** The same, the document being an image or a PDF sent with the prompt (a multimodal model). */
		default JsonNode ask(final String pSystem, final String pPrompt, final List<LlmConnector.Attachment> pAttachments) throws ConnectorException
		{
			throw new ConnectorException("this model takes no image or PDF");
		}
	}

	private static final List<Operation> OPERATIONS = Arrays.asList(
		Operation.search("list", "Documents of the connector's folder (pdf, docx, txt, md, csv, html), with size and modification date"),
		Operation.read("text", "Text of one document, page by page (input path, or content = base64 with name, or the uploaded document)"),
		Operation.read("extract", "Attributes of one document by the LLM connector, each with confidence, page and snippet (inputs path or content/name, optional fields, threshold)"),
		Operation.search("extractAll", "One extracted item per document of the folder, or per record of a DAMA table (inputs table, field, optional filter), for a feed (itemsPath items; optional inputs pattern = *.pdf, max, fields, threshold)"),
		Operation.write("annotate", "Writes the attributes of a DAMA document as metadata of the asset and adds tags chosen from DAMA's vocabulary to a list field of the record (dama.tags.field: DAMA's public API cannot tag an asset); below the threshold, proposals in the conflict manager (inputs record or asset, metadata = attribute:CODE, tags = true or max:N, optional tagsField, dryRun)"));

	/** Tags asked for when {@code tags} is {@code true}. */
	static final int DEFAULT_MAX_TAGS = 5;
	/** Largest tag vocabulary sent to the model by default (property {@code dama.tags.vocabulary.max}). */
	static final int DEFAULT_MAX_VOCABULARY = 300;
	/** Largest image sent to the model by default (property {@code max.image.bytes}): the smallest per-image limit of the providers. */
	static final int DEFAULT_MAX_IMAGE_BYTES = 5 * 1024 * 1024;
	/** Most pages of a PDF without text layer sent to the model by default (property {@code max.pages}). */
	static final int DEFAULT_MAX_PAGES = 20;
	/** Image formats sent to a multimodal model (what every provider accepts), to their media type. */
	static final Map<String, String> IMAGES;
	static
	{
		final Map<String, String> images = new LinkedHashMap<String, String>();
		images.put("png", "image/png");
		images.put("jpeg", "image/jpeg");
		images.put("gif", "image/gif");
		images.put("webp", "image/webp");
		IMAGES = Collections.unmodifiableMap(images);
	}

	private ConnectorConfig config;
	private Model model;
	private DamaAssets dama;

	@Override
	public String getType()
	{
		return "documents";
	}

	@Override
	public String getLabel()
	{
		return "Documents (PDF, DOCX, text) with AI extraction";
	}

	@Override
	public List<Operation> getOperations()
	{
		return DocumentConnector.OPERATIONS;
	}

	@Override
	public void configure(final ConnectorConfig pConfig)
	{
		this.config = pConfig;
	}

	/** Tests: the model to call instead of the LLM connector. */
	DocumentConnector withModel(final Model pModel)
	{
		this.model = pModel;
		return this;
	}

	/** Tests: the DAMA access to use instead of the add-on. */
	DocumentConnector withDama(final DamaAssets pDama)
	{
		this.dama = pDama;
		return this;
	}

	private DamaAssets dama()
	{
		return this.dama != null ? this.dama : DamaAssets.get();
	}

	@Override
	public ConnectorDescriptor getDescriptor()
	{
		final ConnectorDescriptor.Builder b = ConnectorDescriptor.builder(this.getType(), this.getLabel(), "AI")
			.description("Unstructured documents (PDF, DOCX, text) read from an allowed server folder or an upload; attributes extracted by the LLM connector with a confidence and a source snippet; low-confidence values become steward proposals.")
			.authentication(AuthenticationMethod.NONE)
			.capability(ConnectorCapability.TEST_CONNECTION).capability(ConnectorCapability.BULK_READ)
			.apiVersion("PDF 1.0-2.0 (text layer; without one, sent to a multimodal model when vision=true)").apiVersion("Office Open XML (DOCX)")
			.apiVersion("Images png, jpeg, gif, webp (vision=true, multimodal model)")
			.configurationProperty(DocumentConnector.property("llm.connector", ConfigurationPropertyType.STRING, null))
			.configurationProperty(DocumentConnector.property("fields", ConfigurationPropertyType.STRING, null))
			.configurationProperty(DocumentConnector.property("confidence.threshold", ConfigurationPropertyType.STRING, "0.8"))
			.configurationProperty(DocumentConnector.property("folder", ConfigurationPropertyType.STRING, null))
			.configurationProperty(DocumentConnector.property("max.bytes", ConfigurationPropertyType.INTEGER, Integer.valueOf(DEFAULT_MAX_BYTES)))
			.configurationProperty(DocumentConnector.property("max.files", ConfigurationPropertyType.INTEGER, Integer.valueOf(100)))
			.configurationProperty(DocumentConnector.property("max.chars", ConfigurationPropertyType.INTEGER, Integer.valueOf(60000)))
			.configurationProperty(DocumentConnector.property("language", ConfigurationPropertyType.STRING, null))
			.configurationProperty(DocumentConnector.property("vision", ConfigurationPropertyType.BOOLEAN, Boolean.FALSE))
			.configurationProperty(DocumentConnector.property("vision.confidence.max", ConfigurationPropertyType.STRING, String.valueOf(DocumentExtraction.DEFAULT_ATTACHMENT_CAP)))
			.configurationProperty(DocumentConnector.property("max.image.bytes", ConfigurationPropertyType.INTEGER, Integer.valueOf(DEFAULT_MAX_IMAGE_BYTES)))
			.configurationProperty(DocumentConnector.property("max.pages", ConfigurationPropertyType.INTEGER, Integer.valueOf(DEFAULT_MAX_PAGES)))
			.configurationProperty(DocumentConnector.property("dama.dataspace", ConfigurationPropertyType.STRING, null))
			.configurationProperty(DocumentConnector.property("dama.dataset", ConfigurationPropertyType.STRING, null))
			.configurationProperty(DocumentConnector.property("dama.table", ConfigurationPropertyType.STRING, null))
			.configurationProperty(DocumentConnector.property("dama.field", ConfigurationPropertyType.STRING, null))
			.configurationProperty(DocumentConnector.property("dama.metadata", ConfigurationPropertyType.STRING, null))
			.configurationProperty(DocumentConnector.property("dama.tags", ConfigurationPropertyType.STRING, null))
			.configurationProperty(DocumentConnector.property("dama.tags.field", ConfigurationPropertyType.STRING, null))
			.configurationProperty(DocumentConnector.property("dama.tags.vocabulary.max", ConfigurationPropertyType.INTEGER, Integer.valueOf(DEFAULT_MAX_VOCABULARY)))
			.configurationProperty(DocumentConnector.property("metadata.policy", ConfigurationPropertyType.STRING, "IF_EMPTY"));
		for (final Operation o : this.getOperations())
		{
			// annotate writes to the DAMA asset (outside EBX's record), the other operations only read
			final ConnectorDirection direction = o.getKind() == Operation.Kind.WRITE ? ConnectorDirection.OUTBOUND : ConnectorDirection.INBOUND;
			b.direction(direction).operation(new ConnectorOperationDescriptor(o.getName(), o.getKind(), o.getDescription(),
				Collections.singleton(direction), Collections.<ConnectorCapability>emptySet()));
		}
		return b.build();
	}

	private static ConfigurationPropertyDefinition property(final String pName, final ConfigurationPropertyType pType, final Object pDefault)
	{
		final ConfigurationPropertyDefinition.Builder b = ConfigurationPropertyDefinition.builder(pName, pType);
		if (pDefault != null) b.defaultValue(pDefault);
		return b.build();
	}

	@Override
	public ConfigurationValidationResult validateConfiguration(final ConnectorConfig pConfig)
	{
		final List<ConfigurationValidationIssue> issues = new ArrayList<ConfigurationValidationIssue>();
		if (pConfig == null) return new ConfigurationValidationResult(issues);
		final String threshold = pConfig.getProperty("confidence.threshold", null);
		if (threshold != null)
		{
			try
			{
				final double t = Double.parseDouble(threshold.trim());
				if (t < 0 || t > 1) issues.add(new ConfigurationValidationIssue("confidence.threshold", "Must be between 0 and 1"));
			}
			catch (final NumberFormatException ex)
			{
				issues.add(new ConfigurationValidationIssue("confidence.threshold", "Must be a number between 0 and 1"));
			}
		}
		final String cap = pConfig.getProperty("vision.confidence.max", null);
		if (cap != null && !cap.trim().isEmpty())
		{
			try
			{
				final double c = Double.parseDouble(cap.trim());
				if (c < 0 || c > 1) issues.add(new ConfigurationValidationIssue("vision.confidence.max", "Must be between 0 and 1"));
			}
			catch (final NumberFormatException ex)
			{
				issues.add(new ConfigurationValidationIssue("vision.confidence.max", "Must be a number between 0 and 1"));
			}
		}
		final String fields = pConfig.getProperty("fields", null);
		if (fields != null)
		{
			try
			{
				DocumentExtraction.fields(fields);
			}
			catch (final IllegalArgumentException ex)
			{
				issues.add(new ConfigurationValidationIssue("fields", ex.getMessage()));
			}
		}
		final String metadata = pConfig.getProperty("dama.metadata", null);
		if (metadata != null)
		{
			try
			{
				DocumentConnector.metadataMap(metadata);
			}
			catch (final IllegalArgumentException ex)
			{
				issues.add(new ConfigurationValidationIssue("dama.metadata", ex.getMessage()));
			}
		}
		final String tags = pConfig.getProperty("dama.tags", null);
		if (tags != null)
		{
			try
			{
				DocumentConnector.maxTags(tags);
			}
			catch (final IllegalArgumentException ex)
			{
				issues.add(new ConfigurationValidationIssue("dama.tags", ex.getMessage()));
			}
		}
		final String policy = pConfig.getProperty("metadata.policy", null);
		if (policy != null && !policy.trim().isEmpty() && !"IF_EMPTY".equals(policy.trim()) && !"ALWAYS".equals(policy.trim()))
			issues.add(new ConfigurationValidationIssue("metadata.policy", "IF_EMPTY or ALWAYS"));
		final String folder = pConfig.getProperty("folder", null);
		if (folder != null && !folder.trim().isEmpty())
		{
			final String problem = DocumentConnector.folderProblem(folder, System.getProperty(ROOTS_PROPERTY));
			if (problem != null) issues.add(new ConfigurationValidationIssue("folder", problem));
		}
		return new ConfigurationValidationResult(issues);
	}

	@Override
	public com.on.ebx.connect.spi.ConnectionTestResult testConnection(final ConnectorContext pContext) throws ConnectorException
	{
		final long start = System.nanoTime();
		final String folder = this.property("folder");
		if (folder != null)
		{
			final List<JsonNode> files = this.listFolder(null, 1);
			return com.on.ebx.connect.spi.ConnectionTestResult.success("Folder readable" + (files.isEmpty() ? " (no document yet)" : ""), (System.nanoTime() - start) / 1000000L);
		}
		if (this.config != null && this.config.getSampleData() != null)
			return com.on.ebx.connect.spi.ConnectionTestResult.success("Uploaded document present", (System.nanoTime() - start) / 1000000L);
		if (this.property("dama.field") != null)
		{
			return this.dama().available()
				? com.on.ebx.connect.spi.ConnectionTestResult.success("DAMA add-on present", (System.nanoTime() - start) / 1000000L)
				: com.on.ebx.connect.spi.ConnectionTestResult.failure("Property dama.field is set but the DAMA add-on is not installed", (System.nanoTime() - start) / 1000000L);
		}
		return com.on.ebx.connect.spi.ConnectionTestResult.failure("No folder, no uploaded document and no DAMA field: set property folder or dama.field, or upload a document", (System.nanoTime() - start) / 1000000L);
	}

	@Override
	public JsonNode execute(final ConnectorContext pContext, final String pOperation, final Map<String, String> pInputs, final JsonNode pPayload)
		throws ConnectorException
	{
		if ("list".equals(pOperation))
		{
			final ObjectNode out = Json.object();
			final ArrayNode items = out.putArray("items");
			for (final JsonNode file : this.listFolder(DocumentConnector.input(pInputs, "pattern"), this.maxFiles(pInputs))) items.add(file);
			out.put("count", items.size());
			return out;
		}
		if ("text".equals(pOperation))
		{
			final Source source = this.source(pContext, pInputs);
			if (IMAGES.containsKey(source.format))
			{
				final ObjectNode out = DocumentConnector.textOf(source, Collections.singletonList(new DocumentText.Page(1, "")));
				out.put("note", "an image has no text to read: use extract or annotate, which send it to the multimodal model");
				return out;
			}
			final List<DocumentText.Page> pages = DocumentText.pages(source.content, source.format);
			return DocumentConnector.textOf(source, pages);
		}
		if ("extract".equals(pOperation))
		{
			return this.extract(pContext, this.source(pContext, pInputs), pInputs);
		}
		if ("annotate".equals(pOperation))
		{
			return this.annotate(pContext, pInputs);
		}
		if ("extractAll".equals(pOperation))
		{
			final ObjectNode out = Json.object();
			final ArrayNode items = out.putArray("items");
			final ArrayNode errors = out.putArray("errors");
			if (this.damaFeed(pInputs))
			{
				final DamaAssets.Ref table = this.damaRef(pInputs);
				for (final DamaAssets.Ref ref : this.dama().records(pContext == null ? null : pContext.getRepository(), pContext == null ? null : pContext.getSession(),
					table, DocumentConnector.input(pInputs, "filter"), this.maxFiles(pInputs)))
				{
					try
					{
						items.add(this.extract(pContext, this.fromDama(pContext, ref, false), pInputs));
					}
					catch (final ConnectorException ex)
					{
						final ObjectNode error = errors.addObject();
						error.put("record", ref.record);
						error.put("error", ex.getMessage());
					}
				}
				out.put("count", items.size());
				return out;
			}
			for (final JsonNode file : this.listFolder(DocumentConnector.input(pInputs, "pattern"), this.maxFiles(pInputs)))
			{
				final String name = file.path("name").asText();
				try
				{
					items.add(this.extract(pContext, this.fromFolder(name), pInputs));
				}
				catch (final ConnectorException ex)
				{
					// one unreadable document does not stop the feed: it is reported and the others are imported
					final ObjectNode error = errors.addObject();
					error.put("document", name);
					error.put("error", ex.getMessage());
				}
			}
			out.put("count", items.size());
			return out;
		}
		throw new ConnectorException(this.id() + ": unknown operation '" + pOperation + "'");
	}

	// ------------------------------------------------------------------ extraction

	private ObjectNode extract(final ConnectorContext pContext, final Source pSource, final Map<String, String> pInputs) throws ConnectorException
	{
		final String spec = DocumentConnector.input(pInputs, "fields") != null ? DocumentConnector.input(pInputs, "fields") : this.property("fields");
		final List<DocumentExtraction.Field> fields;
		try
		{
			fields = DocumentExtraction.fields(spec);
		}
		catch (final IllegalArgumentException ex)
		{
			throw new ConnectorException(this.id() + ": " + ex.getMessage());
		}
		if (fields.isEmpty()) throw new ConnectorException(this.id() + ": no attribute to extract (input or property 'fields', e.g. invoiceNumber:the invoice number, total:amount:decimal)");
		final ObjectNode item = this.read(pContext, pSource, fields, null, 0, this.threshold(pInputs), "").item;
		item.put("format", pSource.format);
		if (pSource.modified != null) item.put("modified", pSource.modified);
		if (pSource.asset != null) DocumentConnector.describe(item, pSource.asset);
		return item;
	}

	/** What the model read from one document: the item a mapping imports, and the tags it chose (empty when none was asked). */
	private static final class Reading
	{
		final ObjectNode item;
		final ObjectNode tags;

		Reading(final ObjectNode pItem, final ObjectNode pTags)
		{
			this.item = pItem;
			this.tags = pTags;
		}
	}

	/**
	 * Asks the model about one document. A text document sends its text; an image, or a PDF without text layer, is sent itself to
	 * the multimodal model when {@code vision=true}, its values capped at {@code vision.confidence.max} since nothing can check them.
	 * Otherwise a document without text calls no model: the item says why ({@code pNoteEnd} ends that note).
	 */
	private Reading read(final ConnectorContext pContext, final Source pSource, final List<DocumentExtraction.Field> pFields, final Map<String, String> pVocabulary,
		final int pMaxTags, final double pThreshold, final String pNoteEnd) throws ConnectorException
	{
		final Map<String, String> vocabulary = pMaxTags > 0 ? pVocabulary : null;
		final String image = IMAGES.get(pSource.format);
		final List<DocumentText.Page> pages = image != null ? Collections.singletonList(new DocumentText.Page(1, ""))
			: DocumentText.pages(pSource.content, pSource.format);
		boolean hasText = false;
		for (final DocumentText.Page page : pages) hasText |= !page.getText().trim().isEmpty();
		if (hasText)
		{
			final int maxChars = DocumentConnector.bounded(this.property("max.chars"), 60000, 1000, 400000);
			final String joined = DocumentText.joined(pages, maxChars);
			final String prompt = DocumentExtraction.prompt(pFields, pSource.name, joined, this.property("language"), vocabulary, pMaxTags);
			final JsonNode answer = this.model(pContext).ask(DocumentExtraction.system(), prompt);
			final ObjectNode item = DocumentExtraction.item(answer, pFields, pages, pSource.name, pThreshold);
			if (joined.length() >= maxChars) item.put("note", "text cut at " + maxChars + " characters (property max.chars)");
			return new Reading(item, DocumentExtraction.tags(answer, vocabulary, pages, pSource.name, pThreshold, pMaxTags));
		}
		if (image == null && (!"pdf".equals(pSource.format) || !this.vision()))
		{
			// a scanned document and no multimodal model: nothing to send, nothing invented; the item says why
			final ObjectNode item = DocumentExtraction.item(null, pFields, pages, pSource.name, pThreshold);
			item.put("note", "no text layer (scanned document?): OCR is not part of this connector" + ("pdf".equals(pSource.format)
				? " (vision=true sends the PDF to a multimodal model)" : "") + pNoteEnd);
			return new Reading(item, DocumentExtraction.tags(null, vocabulary, pages, pSource.name, pThreshold, pMaxTags));
		}
		this.requireVision(pSource.name);
		if (image != null)
		{
			final int maxImage = DocumentConnector.bounded(this.property("max.image.bytes"), DEFAULT_MAX_IMAGE_BYTES, 1024, 20 * 1024 * 1024);
			if (pSource.content.length > maxImage)
				throw new ConnectorException(this.id() + ": image " + pSource.name + " is " + pSource.content.length + " bytes, above max.image.bytes " + maxImage + " (not resized)");
		}
		else
		{
			final int maxPages = DocumentConnector.bounded(this.property("max.pages"), DEFAULT_MAX_PAGES, 1, 100);
			if (pages.size() > maxPages)
				throw new ConnectorException(this.id() + ": " + pSource.name + " has no text layer and " + pages.size() + " pages, above max.pages " + maxPages + ": not sent to the model");
		}
		final double cap = this.attachmentCap();
		final String kind = image != null ? DocumentExtraction.IMAGE : DocumentExtraction.PDF;
		final String prompt = DocumentExtraction.promptForAttachment(pFields, pSource.name, kind, this.property("language"), vocabulary, pMaxTags);
		final LlmConnector.Attachment attachment = new LlmConnector.Attachment(image != null ? image : "application/pdf", pSource.content, pSource.name);
		final JsonNode answer = this.model(pContext).ask(DocumentExtraction.systemForAttachment(), prompt, Collections.singletonList(attachment));
		final ObjectNode item = DocumentExtraction.item(answer, pFields, pages, pSource.name, pThreshold, cap);
		item.put("attachment", kind);
		return new Reading(item, DocumentExtraction.tags(answer, vocabulary, pages, pSource.name, pThreshold, pMaxTags, cap));
	}

	/** True when this connection may send images and PDFs without text to the multimodal model ({@code vision=true}). */
	private boolean vision()
	{
		return "true".equalsIgnoreCase(this.property("vision"));
	}

	private void requireVision(final String pName) throws ConnectorException
	{
		if (!this.vision())
			throw new ConnectorException(this.id() + ": " + pName + " is an image: no OCR and no vision on this connection (vision=true sends images to the multimodal model of the LLM connector; the image then leaves EBX)");
	}

	/** The highest confidence of a value read from an image or a PDF without text ({@code vision.confidence.max}). */
	private double attachmentCap()
	{
		final String raw = this.property("vision.confidence.max");
		try
		{
			return raw == null ? DocumentExtraction.DEFAULT_ATTACHMENT_CAP : Math.max(0, Math.min(1, Double.parseDouble(raw.trim())));
		}
		catch (final NumberFormatException ex)
		{
			return DocumentExtraction.DEFAULT_ATTACHMENT_CAP;
		}
	}

	/** What a DAMA item says about its asset and the record that holds it, for a mapping's keyPredicate. */
	private static void describe(final ObjectNode pItem, final DamaAssets.Asset pAsset)
	{
		pItem.put("asset", pAsset.uuid);
		pItem.put("assetLabel", pAsset.label);
		if (pAsset.ref.record != null)
		{
			pItem.put("dataspace", pAsset.ref.dataspace);
			pItem.put("dataset", pAsset.ref.dataset);
			pItem.put("table", pAsset.ref.table);
			pItem.put("record", pAsset.ref.record);
			pItem.put("field", pAsset.ref.field);
		}
	}

	// ------------------------------------------------------------------ annotate (DAMA metadata and tags)

	private ObjectNode annotate(final ConnectorContext pContext, final Map<String, String> pInputs) throws ConnectorException
	{
		if (DocumentConnector.input(pInputs, "asset") == null && DocumentConnector.input(pInputs, "record") == null)
			throw new ConnectorException(this.id() + ": annotate needs a DAMA document (input record, or asset)");
		final Map<String, String> metadata;
		final int maxTags;
		final List<DocumentExtraction.Field> fields;
		try
		{
			metadata = DocumentConnector.metadataMap(DocumentConnector.input(pInputs, "metadata") != null ? DocumentConnector.input(pInputs, "metadata") : this.property("dama.metadata"));
			maxTags = DocumentConnector.maxTags(DocumentConnector.input(pInputs, "tags") != null ? DocumentConnector.input(pInputs, "tags") : this.property("dama.tags"));
			fields = DocumentExtraction.fields(DocumentConnector.input(pInputs, "fields") != null ? DocumentConnector.input(pInputs, "fields") : this.property("fields"));
		}
		catch (final IllegalArgumentException ex)
		{
			throw new ConnectorException(this.id() + ": " + ex.getMessage());
		}
		if (metadata.isEmpty() && maxTags == 0)
			throw new ConnectorException(this.id() + ": nothing to write on the asset (input or property metadata = attribute:CODE, or tags = true)");
		for (final String attribute : metadata.keySet())
		{
			boolean known = false;
			for (final DocumentExtraction.Field field : fields) known |= field.getName().equals(attribute);
			if (!known) throw new ConnectorException(this.id() + ": metadata names attribute '" + attribute + "', which is not in fields");
		}
		final String policy = DocumentConnector.input(pInputs, "policy") != null ? DocumentConnector.input(pInputs, "policy") : this.property("metadata.policy");
		final boolean always = "ALWAYS".equals(policy);
		if (policy != null && !always && !"IF_EMPTY".equals(policy)) throw new ConnectorException(this.id() + ": metadata.policy must be IF_EMPTY or ALWAYS");
		final boolean dryRun = "true".equalsIgnoreCase(DocumentConnector.input(pInputs, "dryRun"));
		final Source source = this.fromDama(pContext, this.damaRef(pInputs), true);
		final DamaAssets.Asset asset = source.asset;
		final DamaAssets dama = this.dama();
		// DAMA's public API cannot tag an asset: the tags go to a list field of the record that holds it, with the caller's permissions
		final String tagsField = maxTags > 0 ? this.inputOr(pInputs, "tagsField", "dama.tags.field") : null;
		final List<String> recordTags = tagsField == null ? Collections.<String>emptyList() : dama.recordList(asset, tagsField, !dryRun);
		if (!metadata.isEmpty())
		{
			// only the metadata types DAMA knows and lets a user enter by hand: never one DAMA computes itself
			final Map<String, Boolean> types = dama.metadataTypes(pContext.getSession(), asset);
			for (final String code : metadata.values())
			{
				if (!types.containsKey(code)) throw new ConnectorException(this.id() + ": DAMA has no metadata type " + code);
				if (!Boolean.TRUE.equals(types.get(code))) throw new ConnectorException(this.id() + ": DAMA metadata type " + code + " is not entered by hand (IsManually): it is not written");
			}
		}
		final Map<String, String> vocabulary = new LinkedHashMap<String, String>();
		if (maxTags > 0)
		{
			for (final DamaAssets.Tag tag : dama.vocabulary(pContext.getSession(), asset)) vocabulary.put(tag.code, tag.label);
			if (vocabulary.isEmpty()) throw new ConnectorException(this.id() + ": DAMA has no tag this field's assets may carry: no tag is ever created");
			final int ceiling = DocumentConnector.bounded(this.property("dama.tags.vocabulary.max"), DEFAULT_MAX_VOCABULARY, 1, 5000);
			if (vocabulary.size() > ceiling)
				throw new ConnectorException(this.id() + ": the tag vocabulary has " + vocabulary.size() + " tags, above dama.tags.vocabulary.max " + ceiling + " (not cut silently)");
		}
		final double threshold = this.threshold(pInputs);
		final Reading reading = this.read(pContext, source, fields, vocabulary, maxTags, threshold, "; nothing written");
		final ObjectNode item = reading.item;
		final ObjectNode tags = reading.tags;
		item.put("format", source.format);
		if (source.modified != null) item.put("modified", source.modified);
		DocumentConnector.describe(item, asset);

		final Map<String, String> writeMetadata = new LinkedHashMap<String, String>();
		final List<String> addTags = new ArrayList<String>();
		final List<MappingResult.Conflict> conflicts = new ArrayList<MappingResult.Conflict>();
		final ArrayNode pending = Json.mapper().createArrayNode();
		final ArrayNode kept = Json.mapper().createArrayNode();
		for (final Map.Entry<String, String> entry : metadata.entrySet())
		{
			final JsonNode found = item.get(entry.getKey());
			if (found == null || found.isNull()) continue;
			final String value = DocumentConnector.text(found);
			final String code = entry.getValue();
			final String current = asset.metadata.get(code);
			if (value.equals(current)) continue;
			if (!always && current != null && !current.trim().isEmpty())
			{
				final ObjectNode k = kept.addObject();
				k.put("metadata", code);
				k.put("current", current);
				k.put("extracted", value);
				continue;
			}
			final double confidence = item.path(DocumentExtraction.CONFIDENCE).path(entry.getKey()).asDouble(0);
			final String why = item.path(DocumentExtraction.SOURCES).path(entry.getKey()).asText(null);
			if (confidence >= threshold)
			{
				writeMetadata.put(code, value);
				continue;
			}
			DocumentConnector.pending(pending, DamaProposals.METADATA, code, value, confidence, why);
			conflicts.add(new MappingResult.Conflict(DamaProposals.path(asset.ref.field, asset.uuid, DamaProposals.METADATA, code), current, value, null, 100,
				DamaProposals.comment(confidence, threshold, why)));
		}
		for (final JsonNode tag : tags.path("chosen"))
		{
			final String code = tag.path("code").asText();
			if (tagsField == null || recordTags.contains(code)) continue;
			final double confidence = tag.path("confidence").asDouble(0);
			if (confidence >= threshold)
			{
				addTags.add(code);
				continue;
			}
			// an ordinary proposal on the record's list field: accepting it adds this one code
			DocumentConnector.pending(pending, DamaProposals.TAG, code, code, confidence, tag.path("source").asText(null));
			conflicts.add(new MappingResult.Conflict(tagsField, null, code, null, 100, DamaProposals.comment(confidence, threshold, tag.path("source").asText(null))));
		}
		if (!dryRun)
		{
			dama.update(asset, writeMetadata);
			if (tagsField != null) dama.addToRecordList(asset, tagsField, addTags);
		}
		final ObjectNode written = item.putObject("written");
		final ObjectNode writtenMetadata = written.putObject("metadata");
		for (final Map.Entry<String, String> entry : writeMetadata.entrySet()) writtenMetadata.put(entry.getKey(), entry.getValue());
		final ArrayNode writtenTags = written.putArray("tags");
		for (final String code : addTags) writtenTags.add(code);
		if (tagsField != null) written.put("tagsField", tagsField);
		else if (maxTags > 0) item.put("tagsNote", "tags are only returned: set dama.tags.field to a list field of the record to keep them (DAMA's public API cannot tag an asset)");
		item.set("pending", pending);
		item.set("kept", kept);
		item.set("tags", tags.path("chosen"));
		item.set("rejectedTags", tags.path("rejected"));
		if (tags.path("overMax").size() > 0) item.set("overMaxTags", tags.path("overMax"));
		if (dryRun)
		{
			item.put("dryRun", true);
		}
		else if (!conflicts.isEmpty())
		{
			if (asset.ref.record == null) item.put("proposedTo", "none: without a record the proposals are only returned here");
			else item.put("proposedTo", dama.propose(pContext.getSession(), asset, this.config, conflicts) ? "conflict manager" : "not saved (see the kernel log)");
		}
		return item;
	}

	private static void pending(final ArrayNode pPending, final String pKind, final String pCode, final String pValue, final double pConfidence, final String pSource)
	{
		final ObjectNode p = pPending.addObject();
		p.put("kind", pKind);
		p.put("code", pCode);
		p.put("value", pValue);
		p.put("confidence", pConfidence);
		if (pSource != null) p.put("source", pSource);
	}

	/** A metadata value as DAMA stores it: text as is, a number in plain notation, anything else as JSON. */
	static String text(final JsonNode pValue)
	{
		if (pValue.isNumber()) return pValue.decimalValue().toPlainString();
		if (pValue.isValueNode()) return pValue.asText();
		return Json.write(pValue);
	}

	/** {@code attribute:CODE, ...} to an ordered map; a code is written as is and cannot contain {@code /}. */
	static Map<String, String> metadataMap(final String pSpec)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pSpec == null || pSpec.trim().isEmpty()) return out;
		for (final String item : pSpec.split("[,\\n]"))
		{
			if (item.trim().isEmpty()) continue;
			final String[] parts = item.split(":", 2);
			if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty())
				throw new IllegalArgumentException("metadata: '" + item.trim() + "' must be attribute:CODE");
			final String code = parts[1].trim();
			if (code.contains("/")) throw new IllegalArgumentException("metadata: a DAMA code cannot contain '/': " + code);
			if (out.put(parts[0].trim(), code) != null) throw new IllegalArgumentException("metadata: attribute '" + parts[0].trim() + "' is listed twice");
		}
		return out;
	}

	/** {@code true} (5 tags), {@code false} or empty (none), {@code max:N} or {@code N} (1 to 50). */
	static int maxTags(final String pSpec)
	{
		if (pSpec == null || pSpec.trim().isEmpty() || "false".equalsIgnoreCase(pSpec.trim())) return 0;
		final String spec = pSpec.trim();
		if ("true".equalsIgnoreCase(spec)) return DEFAULT_MAX_TAGS;
		try
		{
			final int n = Integer.parseInt(spec.startsWith("max:") ? spec.substring(4).trim() : spec);
			if (n < 0 || n > 50) throw new IllegalArgumentException("tags: between 0 and 50");
			return n;
		}
		catch (final NumberFormatException ex)
		{
			throw new IllegalArgumentException("tags: true, false, or max:N");
		}
	}

	private Model model(final ConnectorContext pContext) throws ConnectorException
	{
		if (this.model != null) return this.model;
		final String id = this.property("llm.connector");
		if (id == null) throw new ConnectorException(this.id() + ": property llm.connector must name an LLM connector");
		if (pContext == null || pContext.getRepository() == null) throw new ConnectorException(this.id() + ": no repository to find the LLM connector " + id);
		final Connector found = ConnectorRegistry.get().connector(id, ConnectorService.get().repository(pContext.getRepository()));
		if (!(found instanceof LlmConnector)) throw new ConnectorException(this.id() + ": connector " + id + " is not an LLM connector");
		final LlmConnector llm = (LlmConnector) found;
		return new Model()
		{
			@Override
			public JsonNode ask(final String pSystem, final String pPrompt) throws ConnectorException
			{
				return this.parsed(llm.ask(pSystem, pPrompt, Integer.valueOf(4096), true, 0));
			}

			@Override
			public JsonNode ask(final String pSystem, final String pPrompt, final List<LlmConnector.Attachment> pAttachments) throws ConnectorException
			{
				return this.parsed(llm.ask(pSystem, pPrompt, pAttachments, Integer.valueOf(4096), true, 0));
			}

			private JsonNode parsed(final LlmConnector.Answer answer) throws ConnectorException
			{
				final JsonNode parsed = LlmConnector.jsonOf(answer.text);
				if (parsed == null || !parsed.isObject())
					throw new ConnectorException(DocumentConnector.this.id() + ": the model did not answer with a JSON object" + (answer.truncated ? " (answer cut: raise maxTokens)" : ""));
				return parsed;
			}
		};
	}

	private double threshold(final Map<String, String> pInputs)
	{
		final String raw = DocumentConnector.input(pInputs, "threshold") != null ? DocumentConnector.input(pInputs, "threshold") : this.property("confidence.threshold");
		try
		{
			return raw == null ? DocumentExtraction.DEFAULT_THRESHOLD : Math.max(0, Math.min(1, Double.parseDouble(raw.trim())));
		}
		catch (final NumberFormatException ex)
		{
			return DocumentExtraction.DEFAULT_THRESHOLD;
		}
	}

	private static ObjectNode textOf(final Source pSource, final List<DocumentText.Page> pPages)
	{
		final ObjectNode out = Json.object();
		out.put("document", pSource.name);
		out.put("format", pSource.format);
		final ArrayNode pages = out.putArray("pages");
		final ArrayNode empty = out.putArray("emptyPages");
		int chars = 0;
		for (final DocumentText.Page page : pPages)
		{
			final ObjectNode p = pages.addObject();
			p.put("page", page.getNumber());
			p.put("text", page.getText());
			chars += page.getText().length();
			if (page.getText().trim().isEmpty()) empty.add(page.getNumber());
		}
		out.put("chars", chars);
		return out;
	}

	// ------------------------------------------------------------------ sources

	/** One document in hand. */
	static final class Source
	{
		final String name;
		final String format;
		final byte[] content;
		final String modified;
		/** The DAMA asset the document comes from, or null. */
		final DamaAssets.Asset asset;

		Source(final String pName, final String pFormat, final byte[] pContent, final String pModified)
		{
			this(pName, pFormat, pContent, pModified, null);
		}

		Source(final String pName, final String pFormat, final byte[] pContent, final String pModified, final DamaAssets.Asset pAsset)
		{
			this.name = pName;
			this.format = pFormat;
			this.content = pContent;
			this.modified = pModified;
			this.asset = pAsset;
		}
	}

	private Source source(final ConnectorContext pContext, final Map<String, String> pInputs) throws ConnectorException
	{
		final String content = DocumentConnector.input(pInputs, "content");
		final String name = DocumentConnector.input(pInputs, "name");
		if (content != null)
		{
			final byte[] bytes;
			try
			{
				bytes = Base64.getMimeDecoder().decode(content);
			}
			catch (final IllegalArgumentException ex)
			{
				throw new ConnectorException(this.id() + ": input content must be the base64 of the document");
			}
			this.requireSize(bytes.length, name);
			return new Source(name == null ? "upload" : name, this.format(DocumentConnector.input(pInputs, "format"), name), bytes, null);
		}
		if (DocumentConnector.input(pInputs, "asset") != null || DocumentConnector.input(pInputs, "record") != null)
			return this.fromDama(pContext, this.damaRef(pInputs), false);
		final String path = DocumentConnector.input(pInputs, "path");
		if (path != null) return this.fromFolder(path);
		final SampleDataUrl sample = this.config == null ? null : SampleDataUrl.parse(this.config.getSampleData());
		if (sample != null)
		{
			final byte[] bytes;
			try
			{
				bytes = Base64.getMimeDecoder().decode(sample.payload());
			}
			catch (final IllegalArgumentException ex)
			{
				throw new ConnectorException(this.id() + ": the uploaded document is not valid base64");
			}
			final String sampleName = sample.name() == null ? "uploaded document" : sample.name();
			this.requireSize(bytes.length, sampleName);
			return new Source(sampleName, this.format(DocumentConnector.formatOfMime(sample.mime()), sampleName), bytes, null);
		}
		throw new ConnectorException(this.id() + ": no document (input path, record or asset, or content with name, or a document uploaded on the connector)");
	}

	/** Where a DAMA document is: the inputs, with the {@code dama.*} properties as defaults for the table and field. */
	private DamaAssets.Ref damaRef(final Map<String, String> pInputs)
	{
		return new DamaAssets.Ref(this.inputOr(pInputs, "dataspace", "dama.dataspace"), this.inputOr(pInputs, "dataset", "dama.dataset"),
			this.inputOr(pInputs, "table", "dama.table"), DocumentConnector.input(pInputs, "record"), this.inputOr(pInputs, "field", "dama.field"),
			DocumentConnector.input(pInputs, "asset"));
	}

	private String inputOr(final Map<String, String> pInputs, final String pInput, final String pProperty)
	{
		final String value = DocumentConnector.input(pInputs, pInput);
		return value != null ? value : this.property(pProperty);
	}

	/** extractAll reads a DAMA table when asked ({@code source = dama}), or when a DAMA field is named and no folder is set. */
	private boolean damaFeed(final Map<String, String> pInputs)
	{
		final String source = DocumentConnector.input(pInputs, "source");
		if (source != null) return "dama".equalsIgnoreCase(source);
		return this.property("folder") == null && this.inputOr(pInputs, "field", "dama.field") != null;
	}

	/** The content of a DAMA asset, under the caller's session; an image or another non-text type is refused before it is read. */
	private Source fromDama(final ConnectorContext pContext, final DamaAssets.Ref pRef, final boolean pForWrite) throws ConnectorException
	{
		if (pContext == null || pContext.getSession() == null) throw new ConnectorException(this.id() + ": a DAMA document is read with the caller's session, and there is none");
		final DamaAssets dama = this.dama();
		final DamaAssets.Asset asset = dama.open(pContext.getRepository(), pContext.getSession(), pRef, pForWrite);
		final String name = asset.label == null || asset.label.trim().isEmpty() ? asset.uuid : asset.label;
		String format = asset.extension == null ? null : DocumentText.formatOf(null, "document." + asset.extension.replaceFirst("^\\.", ""));
		if (format == null) format = DocumentText.formatOf(null, name);
		if (format == null && asset.extension != null) format = DocumentConnector.imageOf(asset.extension.replaceFirst("^\\.", ""));
		if (format == null) format = DocumentConnector.imageOf(DocumentConnector.extension(name));
		if (format == null && asset.type != null) format = DocumentConnector.formatOfMime(asset.type);
		if (format == null)
			throw new ConnectorException(this.id() + ": " + name + " is not a text document (pdf, docx, txt, md, csv, html) nor an image (png, jpeg, gif, webp)"
				+ (asset.type != null && asset.type.toLowerCase(Locale.ENGLISH).startsWith("image") ? ": no OCR, and this image type is not sent to a model" : ""));
		if (IMAGES.containsKey(format)) this.requireVision(name);
		final int max = DocumentConnector.bounded(this.property("max.bytes"), DEFAULT_MAX_BYTES, 1024, CEILING_MAX_BYTES);
		if (asset.size != null) this.requireSize(asset.size.longValue(), name);
		return new Source(name, format, dama.content(asset, max), asset.modified, asset);
	}

	private String format(final String pExplicit, final String pName) throws ConnectorException
	{
		final String format = DocumentText.formatOf(pExplicit, pName);
		if (format != null) return format;
		final String image = DocumentConnector.imageOf(pExplicit != null && !pExplicit.trim().isEmpty() ? pExplicit : DocumentConnector.extension(pName));
		if (image == null) throw new ConnectorException(this.id() + ": unsupported document '" + (pName == null ? pExplicit : pName) + "' (pdf, docx, txt, md, csv, html; png, jpeg, gif, webp with vision=true)");
		this.requireVision(pName == null ? "the document" : pName);
		return image;
	}

	/** The image format of an extension or format name ({@code jpg} is {@code jpeg}), or null when it is not an image sent to a model. */
	static String imageOf(final String pExtension)
	{
		if (pExtension == null) return null;
		final String extension = pExtension.trim().toLowerCase(Locale.ENGLISH);
		final String format = "jpg".equals(extension) ? "jpeg" : extension;
		return IMAGES.containsKey(format) ? format : null;
	}

	private static String extension(final String pName)
	{
		if (pName == null) return null;
		final int dot = pName.lastIndexOf('.');
		return dot < 0 ? null : pName.substring(dot + 1);
	}

	static String formatOfMime(final String pMime)
	{
		if (pMime == null) return null;
		final String mime = pMime.toLowerCase(Locale.ENGLISH);
		if (mime.contains("pdf")) return "pdf";
		if (mime.contains("wordprocessingml")) return "docx";
		if (mime.contains("html")) return "html";
		if (mime.contains("markdown")) return "md";
		if (mime.contains("csv")) return "csv";
		if (mime.startsWith("text/")) return "txt";
		if (mime.startsWith("image/")) return DocumentConnector.imageOf(mime.substring(6).replaceFirst(";.*", ""));
		return null;
	}

	private void requireSize(final long pSize, final String pName) throws ConnectorException
	{
		final int max = DocumentConnector.bounded(this.property("max.bytes"), DEFAULT_MAX_BYTES, 1024, CEILING_MAX_BYTES);
		if (pSize > max) throw new ConnectorException(this.id() + ": " + pName + " is " + pSize + " bytes, above max.bytes " + max);
	}

	private Source fromFolder(final String pName) throws ConnectorException
	{
		final Path folder = this.folder();
		if (pName.contains("/") || pName.contains("\\") || pName.startsWith(".") || pName.contains("\u0000"))
			throw new ConnectorException(this.id() + ": '" + pName + "' must be a file name of the folder, without path");
		final Path file = folder.resolve(pName);
		try
		{
			if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new ConnectorException(this.id() + ": no document '" + pName + "' in the folder");
			final Path real = file.toRealPath();
			if (!real.getParent().equals(folder)) throw new ConnectorException(this.id() + ": '" + pName + "' leaves the folder");
			this.requireSize(Files.size(real), pName);
			return new Source(pName, this.format(null, pName), Files.readAllBytes(real), Files.getLastModifiedTime(real).toInstant().toString());
		}
		catch (final IOException ex)
		{
			throw new ConnectorException(this.id() + ": cannot read '" + pName + "': " + ex.getClass().getSimpleName());
		}
	}

	private List<JsonNode> listFolder(final String pPattern, final int pMax) throws ConnectorException
	{
		final Path folder = this.folder();
		final List<JsonNode> out = new ArrayList<JsonNode>();
		final String glob = pPattern == null ? "*" : pPattern;
		if (glob.contains("/") || glob.contains("\\") || glob.contains("**")) throw new ConnectorException(this.id() + ": pattern must be a file-name pattern such as *.pdf");
		final List<Path> files = new ArrayList<Path>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, glob))
		{
			for (final Path path : stream)
			{
				final String name = path.getFileName().toString();
				final boolean readable = DocumentText.formatOf(null, name) != null || this.vision() && DocumentConnector.imageOf(DocumentConnector.extension(name)) != null;
				if (name.startsWith(".") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !readable) continue;
				files.add(path);
			}
		}
		catch (final IOException ex)
		{
			throw new ConnectorException(this.id() + ": cannot list the folder: " + ex.getClass().getSimpleName());
		}
		Collections.sort(files);
		for (final Path path : files)
		{
			if (out.size() >= pMax) break;
			final ObjectNode file = Json.object();
			file.put("name", path.getFileName().toString());
			file.put("format", DocumentText.formatOf(null, path.getFileName().toString()));
			try
			{
				file.put("size", Files.size(path));
				file.put("modified", Files.getLastModifiedTime(path).toInstant().toString());
			}
			catch (final IOException ex)
			{
				continue;
			}
			out.add(file);
		}
		return out;
	}

	private Path folder() throws ConnectorException
	{
		final String folder = this.property("folder");
		if (folder == null) throw new ConnectorException(this.id() + ": no folder (property folder)");
		final String problem = DocumentConnector.folderProblem(folder, System.getProperty(ROOTS_PROPERTY));
		if (problem != null) throw new ConnectorException(this.id() + ": " + problem);
		try
		{
			return Paths.get(folder.trim()).toRealPath();
		}
		catch (final IOException ex)
		{
			throw new ConnectorException(this.id() + ": folder not readable");
		}
	}

	/** Why a folder may not be read, null when it may: it must be absolute, exist, and sit inside one of the allowed roots. */
	static String folderProblem(final String pFolder, final String pRoots)
	{
		if (pRoots == null || pRoots.trim().isEmpty())
			return "no server folder may be read until the JVM property -D" + ROOTS_PROPERTY + " lists the allowed roots";
		final Path folder;
		try
		{
			final Path raw = Paths.get(pFolder.trim());
			if (!raw.isAbsolute()) return "folder must be an absolute path";
			folder = raw.toRealPath();
		}
		catch (final IOException | RuntimeException ex)
		{
			return "folder does not exist or is not readable";
		}
		if (!Files.isDirectory(folder)) return "folder is not a directory";
		for (final String root : pRoots.split("[,;]"))
		{
			if (root.trim().isEmpty()) continue;
			try
			{
				final Path allowed = Paths.get(root.trim()).toRealPath();
				if (folder.startsWith(allowed)) return null;
			}
			catch (final IOException | RuntimeException ex)
			{
				// a root that does not exist allows nothing
			}
		}
		return "folder is outside the roots allowed by -D" + ROOTS_PROPERTY;
	}

	// ------------------------------------------------------------------ helpers

	private int maxFiles(final Map<String, String> pInputs)
	{
		final int property = DocumentConnector.bounded(this.property("max.files"), 100, 1, 1000);
		return DocumentConnector.bounded(DocumentConnector.input(pInputs, "max"), property, 1, property);
	}

	private String property(final String pName)
	{
		final String value = this.config == null ? null : this.config.getProperty(pName, null);
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	private String id()
	{
		return this.config == null ? this.getType() : this.config.getId();
	}

	private static String input(final Map<String, String> pInputs, final String pName)
	{
		final String value = pInputs == null ? null : pInputs.get(pName);
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	static int bounded(final String pValue, final int pDefault, final int pMinimum, final int pMaximum)
	{
		if (pValue == null) return Math.max(pMinimum, Math.min(pMaximum, pDefault));
		try
		{
			return Math.max(pMinimum, Math.min(pMaximum, Integer.parseInt(pValue.trim())));
		}
		catch (final NumberFormatException ex)
		{
			return Math.max(pMinimum, Math.min(pMaximum, pDefault));
		}
	}
}
