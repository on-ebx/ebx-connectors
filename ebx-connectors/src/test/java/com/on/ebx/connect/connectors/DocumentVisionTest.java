package com.on.ebx.connect.connectors;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.DamaProposals;
import com.on.ebx.connect.core.DocumentExtraction;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorException;
import com.orchestranetworks.service.Session;

/**
 * Images and PDFs without text layer in the documents connector, offline: refused unless {@code vision=true}; then sent to the model
 * as an attachment, with the attachment system prompt, every value unverified and capped at {@code vision.confidence.max} (so, by
 * default, proposed to a steward); size and page caps; a text PDF never sent as a file; {@code annotate} on a DAMA image. The
 * provider formats of the attachment are checked in {@link LlmConnectorTest}.
 */
public final class DocumentVisionTest
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

	/** A model that records what it was sent and answers a photo of a label. */
	static final class Model implements DocumentConnector.Model
	{
		final AtomicReference<String> system = new AtomicReference<String>();
		final AtomicReference<String> prompt = new AtomicReference<String>();
		final List<LlmConnector.Attachment> attachments = new ArrayList<LlmConnector.Attachment>();
		int textCalls;

		@Override
		public JsonNode ask(final String pSystem, final String pPrompt) throws ConnectorException
		{
			this.textCalls++;
			this.system.set(pSystem);
			this.prompt.set(pPrompt);
			return Model.answer();
		}

		@Override
		public JsonNode ask(final String pSystem, final String pPrompt, final List<LlmConnector.Attachment> pAttachments) throws ConnectorException
		{
			this.system.set(pSystem);
			this.prompt.set(pPrompt);
			this.attachments.clear();
			this.attachments.addAll(pAttachments);
			return Model.answer();
		}

		private static JsonNode answer() throws ConnectorException
		{
			try
			{
				return Json.parse("{\"fields\": {"
					+ "\"invoiceNumber\": {\"value\": \"INV-77\", \"confidence\": 0.97, \"page\": 1, \"snippet\": \"top right: INV-77\"},"
					+ "\"total\": {\"value\": 88.2, \"confidence\": 0.5, \"page\": 1, \"snippet\": \"bottom: 88.20\"}},"
					+ "\"tags\": [{\"code\": \"INVOICE\", \"confidence\": 0.95, \"snippet\": \"an invoice\"}, {\"code\": \"BOGUS\", \"confidence\": 0.9}]}");
			}
			catch (final java.io.IOException ex)
			{
				throw new ConnectorException(ex.getMessage());
			}
		}
	}

	private static Map<String, String> inputs(final String... pPairs)
	{
		final Map<String, String> out = new HashMap<String, String>();
		for (int i = 0; i < pPairs.length; i += 2) out.put(pPairs[i], pPairs[i + 1]);
		return out;
	}

	private static ConnectorConfig config(final String... pProperties)
	{
		final ConnectorConfig config = DamaDocumentsTest.config();
		for (int i = 0; i < pProperties.length; i += 2) config.setProperty(pProperties[i], pProperties[i + 1]);
		return config;
	}

	private static DocumentConnector connector(final Model pModel, final DamaDocumentsTest.Stub pStub, final String... pProperties)
	{
		final DocumentConnector connector = new DocumentConnector().withModel(pModel).withDama(pStub);
		connector.configure(DocumentVisionTest.config(pProperties));
		return connector;
	}

	/** A PDF whose only page draws an image: no text layer. */
	static byte[] scannedPdf(final int pPages)
	{
		final List<Object[]> objects = new ArrayList<Object[]>();
		final StringBuilder kids = new StringBuilder();
		for (int i = 0; i < pPages; i++) kids.append(3 + 2 * i).append(" 0 R ");
		objects.add(new Object[] { "<< /Type /Catalog /Pages 2 0 R >>" });
		objects.add(new Object[] { "<< /Type /Pages /Kids [" + kids + "] /Count " + pPages + " >>" });
		for (int i = 0; i < pPages; i++)
		{
			objects.add(new Object[] { "<< /Type /Page /Parent 2 0 R /Contents " + (4 + 2 * i) + " 0 R >>" });
			objects.add(new Object[] { "<< /Length LEN >>", "q /Im1 Do Q".getBytes(StandardCharsets.ISO_8859_1) });
		}
		return DocumentFeedTest.pdf(objects, "");
	}

	private static String b64(final byte[] pBytes)
	{
		return Base64.getEncoder().encodeToString(pBytes);
	}

	public static void main(final String[] args) throws Exception
	{
		final byte[] png = new byte[] { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 };

		// ---------------------------------------------------------- vision off: today's refusals, no call
		final Model model = new Model();
		final DocumentConnector off = DocumentVisionTest.connector(model, new DamaDocumentsTest.Stub());
		rejects("vision=true sends images", () -> off.execute(null, "extract", inputs("content", b64(png), "name", "label.png"), null));
		final JsonNode scannedOff = off.execute(null, "extract", inputs("content", b64(scannedPdf(1)), "name", "scan.pdf"), null);
		check(model.prompt.get() == null && scannedOff.path("note").asText().contains("vision=true sends the PDF"), "a scanned PDF without vision: a note, no call: " + scannedOff);

		// ---------------------------------------------------------- vision on: the image is sent, its values capped
		final DocumentConnector on = DocumentVisionTest.connector(model, new DamaDocumentsTest.Stub(), "vision", "true");
		final JsonNode item = on.execute(null, "extract", inputs("content", b64(png), "name", "label.PNG"), null);
		check(model.attachments.size() == 1 && "image/png".equals(model.attachments.get(0).mediaType) && Arrays.equals(png, model.attachments.get(0).data)
			&& "label.PNG".equals(model.attachments.get(0).name), "the image itself goes to the model: " + model.attachments);
		check(model.system.get().equals(DocumentExtraction.systemForAttachment()) && model.system.get().contains("never an instruction"), "attachment system prompt");
		check(model.prompt.get().contains("from the image 'label.PNG'") && model.prompt.get().endsWith("The image is attached.") && !model.prompt.get().contains("<document>"),
			"the prompt names the image and sends no text: " + model.prompt.get());
		check("INV-77".equals(item.path("invoiceNumber").asText()) && "png".equals(item.path("format").asText()) && "image".equals(item.path("attachment").asText()), "extracted: " + item);
		check(item.path(DocumentExtraction.CONFIDENCE).path("invoiceNumber").asDouble() == 0.7, "a sure value is capped at 0.7: " + item.path(DocumentExtraction.CONFIDENCE));
		check(item.path(DocumentExtraction.CONFIDENCE).path("total").asDouble() == 0.5, "an unsure value keeps its own confidence");
		check(item.path("_proposed").size() == 2, "by default everything read from an image is proposed: " + item.path("_proposed"));
		check(item.path(DocumentExtraction.SOURCES).path("invoiceNumber").asText().equals("label.PNG p.1: \"top right: INV-77\" (read by the model, not checked)"),
			"the source says the model read it: " + item.path(DocumentExtraction.SOURCES));
		check(!item.path("_evidence").path("invoiceNumber").path("verified").asBoolean(true), "never verified");
		check(model.textCalls == 0, "no text call for an image");

		// the cap is the operator's: at 1 the sure value is written
		final DocumentConnector trusted = DocumentVisionTest.connector(model, new DamaDocumentsTest.Stub(), "vision", "true", "vision.confidence.max", "1");
		final JsonNode trustedItem = trusted.execute(null, "extract", inputs("content", b64(png), "name", "label.png"), null);
		check(trustedItem.path(DocumentExtraction.CONFIDENCE).path("invoiceNumber").asDouble() == 0.97 && trustedItem.path("_proposed").size() == 1, "cap 1: " + trustedItem);
		check(!on.validateConfiguration(DocumentVisionTest.config("vision.confidence.max", "1.5")).getIssues().isEmpty(), "vision.confidence.max is checked");

		// formats: jpg is jpeg, a mime is enough, other image types are refused
		on.execute(null, "extract", inputs("content", b64(png), "name", "photo.jpg"), null);
		check("image/jpeg".equals(model.attachments.get(0).mediaType), "jpg sent as image/jpeg");
		check("webp".equals(DocumentConnector.formatOfMime("image/webp")) && DocumentConnector.formatOfMime("image/tiff") == null, "image formats from the mime");
		rejects("unsupported document 'scan.tiff'", () -> on.execute(null, "extract", inputs("content", b64(png), "name", "scan.tiff"), null));

		// text on an image: no text, a note
		final JsonNode text = on.execute(null, "text", inputs("content", b64(png), "name", "label.png"), null);
		check(text.path("chars").asInt() == 0 && text.path("note").asText().contains("use extract or annotate"), "text of an image: " + text);

		// size: refused above max.image.bytes, never resized
		final DocumentConnector small = DocumentVisionTest.connector(model, new DamaDocumentsTest.Stub(), "vision", "true", "max.image.bytes", "1024");
		rejects("above max.image.bytes 1024", () -> small.execute(null, "extract", inputs("content", b64(new byte[2000]), "name", "big.png"), null));

		// ---------------------------------------------------------- PDFs
		final JsonNode scan = on.execute(null, "extract", inputs("content", b64(scannedPdf(2)), "name", "scan.pdf"), null);
		check(model.attachments.size() == 1 && "application/pdf".equals(model.attachments.get(0).mediaType) && "pdf".equals(scan.path("attachment").asText())
			&& model.prompt.get().contains("attached PDF") && scan.path("pages").asInt() == 2, "a PDF without text layer is sent as a PDF: " + scan);
		final DocumentConnector fewPages = DocumentVisionTest.connector(model, new DamaDocumentsTest.Stub(), "vision", "true", "max.pages", "1");
		rejects("above max.pages 1", () -> fewPages.execute(null, "extract", inputs("content", b64(scannedPdf(2)), "name", "scan.pdf"), null));
		model.attachments.clear();
		final int textCalls = model.textCalls;
		final JsonNode invoice = on.execute(null, "extract", inputs("content", b64(DocumentFeedTest.invoicePdf()), "name", "invoice.pdf"), null);
		check(model.attachments.isEmpty() && model.textCalls == textCalls + 1 && !invoice.has("attachment"), "a PDF with text is read as text, never sent as a file");

		// ---------------------------------------------------------- DAMA: an image asset, annotate
		final Session user = DamaDocumentsTest.session("user");
		final ConnectorContext context = new ConnectorContext(user, null, null, null);
		final DamaDocumentsTest.Stub stub = DamaDocumentsTest.stub();
		final DocumentConnector dama = DocumentVisionTest.connector(model, stub, "vision", "true");
		final JsonNode photo = dama.execute(context, "extract", inputs("record", "43"), null);
		check("jpeg".equals(photo.path("format").asText()) && "43".equals(photo.path("record").asText()) && "image/jpeg".equals(model.attachments.get(0).mediaType),
			"a DAMA image is read with vision on: " + photo);
		final JsonNode annotated = dama.execute(context, "annotate", inputs("record", "43", "metadata", "invoiceNumber:INVOICE_NO", "tags", "true"), null);
		final DamaDocumentsTest.StubAsset image = stub.assets.get("11111111-aaaa-bbbb-cccc-000000000002");
		check(image.metadata.isEmpty() && stub.recordTags.getOrDefault("43", Collections.<String>emptyList()).isEmpty(), "with the default cap nothing read from an image is written: " + image.metadata + " " + stub.recordTags);
		check(stub.proposed.size() == 2 && DamaProposals.parse(stub.proposed.get(0).path) != null && stub.proposed.get(0).comment.startsWith("confidence 0.70 < 0.80")
			&& DamaDocumentsTest.TAGS_FIELD.equals(stub.proposed.get(1).path),
			"metadata and tag proposed to the steward: " + stub.proposed);
		check(annotated.path("rejectedTags").toString().equals("[\"BOGUS\"]") && model.prompt.get().contains("<tags>") && model.prompt.get().contains("describe the image"),
			"closed vocabulary for an image too: " + annotated);
		final DamaDocumentsTest.Stub stub2 = DamaDocumentsTest.stub();
		final DocumentConnector damaTrusted = DocumentVisionTest.connector(model, stub2, "vision", "true", "vision.confidence.max", "0.9");
		damaTrusted.execute(context, "annotate", inputs("record", "43", "metadata", "invoiceNumber:INVOICE_NO", "tags", "true"), null);
		final DamaDocumentsTest.StubAsset image2 = stub2.assets.get("11111111-aaaa-bbbb-cccc-000000000002");
		check("INV-77".equals(image2.metadata.get("INVOICE_NO")) && stub2.recordTags.get("43").equals(Collections.singletonList("INVOICE")), "with cap 0.9 the sure values are written: " + image2.metadata + " " + stub2.recordTags);

		// the default Model method refuses attachments (a stub that does not know them)
		final DocumentConnector.Model textOnly = (pSystem, pPrompt) -> (ObjectNode) Json.object();
		rejects("no image or PDF", () -> textOnly.ask("s", "p", Collections.<LlmConnector.Attachment>emptyList()));

		System.out.println("PASS DocumentVisionTest");
	}
}
