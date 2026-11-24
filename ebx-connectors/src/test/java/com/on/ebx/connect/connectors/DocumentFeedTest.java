package com.on.ebx.connect.connectors;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.DocumentExtraction;
import com.on.ebx.connect.core.DocumentText;
import com.on.ebx.connect.core.Json;
import com.on.ebx.connect.core.PdfText;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * Gartner lot A, offline: PDF text extraction on generated PDFs (simple font, composite font with a
 * ToUnicode map inside an object stream, scanned page, encrypted file), plain text and HTML pages, the
 * extraction answer with snippet verification and the confidence cap, and the documents connector with a
 * stubbed model (upload, folder confinement, extractAll that survives one broken document).
 */
public final class DocumentFeedTest
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
		catch (final ConnectorException | PdfText.PdfException ex)
		{
			check(ex.getMessage().contains(pReason), "expected '" + pReason + "', got: " + ex.getMessage());
			return;
		}
		throw new AssertionError("accepted, expected refusal: " + pReason);
	}

	// ------------------------------------------------------------------ PDF fixtures

	static byte[] deflate(final String pText)
	{
		final Deflater deflater = new Deflater();
		deflater.setInput(pText.getBytes(StandardCharsets.ISO_8859_1));
		deflater.finish();
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final byte[] buffer = new byte[4096];
		while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
		deflater.end();
		return out.toByteArray();
	}

	/** A PDF with the given objects (1-based), each a dictionary text or {dictionary, stream bytes}. */
	static byte[] pdf(final List<Object[]> pObjects, final String pTrailerExtra)
	{
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final List<Integer> offsets = new ArrayList<Integer>();
		write(out, "%PDF-1.7\n%âãÏÓ\n");
		for (int i = 0; i < pObjects.size(); i++)
		{
			offsets.add(Integer.valueOf(out.size()));
			final Object[] object = pObjects.get(i);
			write(out, (i + 1) + " 0 obj\n");
			if (object.length == 1)
			{
				write(out, (String) object[0] + "\nendobj\n");
			}
			else
			{
				final byte[] data = (byte[]) object[1];
				write(out, ((String) object[0]).replace("LEN", String.valueOf(data.length)) + "\nstream\n");
				out.write(data, 0, data.length);
				write(out, "\nendstream\nendobj\n");
			}
		}
		final int xref = out.size();
		write(out, "xref\n0 " + (pObjects.size() + 1) + "\n0000000000 65535 f \n");
		for (final Integer offset : offsets) write(out, String.format("%010d 00000 n \n", offset));
		write(out, "trailer\n<< /Size " + (pObjects.size() + 1) + " /Root 1 0 R " + pTrailerExtra + ">>\nstartxref\n" + xref + "\n%%EOF\n");
		return out.toByteArray();
	}

	private static void write(final ByteArrayOutputStream pOut, final String pText)
	{
		final byte[] b = pText.getBytes(StandardCharsets.ISO_8859_1);
		pOut.write(b, 0, b.length);
	}

	/** Three pages: WinAnsi text (FlateDecode), composite font with ToUnicode (page and font in an object stream), and a scanned page. */
	static byte[] invoicePdf()
	{
		final List<Object[]> objects = new ArrayList<Object[]>();
		// 1 catalog, 2 pages, 3 page 1, 4 content 1, 5 font F1, 6 object stream (7 page 2, 8 font F2), 9 content 2, 10 ToUnicode, 11 page 3, 12 content 3
		objects.add(new Object[] { "<< /Type /Catalog /Pages 2 0 R >>" });
		objects.add(new Object[] { "<< /Type /Pages /Kids [3 0 R 7 0 R 11 0 R] /Count 3 /Resources << /Font << /F1 5 0 R >> >> >>" });
		objects.add(new Object[] { "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R >>" });
		final String page1 = "BT /F1 12 Tf 72 760 Td (ACME Industries \\(France\\)) Tj 0 -16 Td [(Invoice n) 20 (\\260 INV-2026-0042)] TJ T* (Date: 2026-10-12) Tj ET";
		objects.add(new Object[] { "<< /Length LEN /Filter /FlateDecode >>", deflate(page1) });
		objects.add(new Object[] { "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>" });
		final String page2Dict = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 9 0 R /Resources << /Font << /F2 8 0 R >> >> >>";
		final String fontDict = "<< /Type /Font /Subtype /Type0 /BaseFont /Calibri /Encoding /Identity-H /ToUnicode 10 0 R >>";
		final String header = "7 0 8 " + (page2Dict.length() + 1) + " ";
		final String objStm = header + page2Dict + " " + fontDict;
		objects.add(new Object[] { "<< /Type /ObjStm /N 2 /First " + header.length() + " /Length LEN /Filter /FlateDecode >>", deflate(objStm) });
		objects.add(new Object[] { "null" }); // 7 and 8 live in the object stream: placeholders keep the numbering
		objects.add(new Object[] { "null" });
		// "Total: 1 234,50 EUR" with glyph ids 0x0001.. mapped by the ToUnicode CMap
		final String total = "Total: 1 234,50 EUR";
		final StringBuilder hex = new StringBuilder();
		final StringBuilder cmapChars = new StringBuilder();
		final Map<Character, Integer> glyphs = new HashMap<Character, Integer>();
		for (final char c : total.toCharArray())
		{
			Integer glyph = glyphs.get(Character.valueOf(c));
			if (glyph == null)
			{
				glyph = Integer.valueOf(glyphs.size() + 1);
				glyphs.put(Character.valueOf(c), glyph);
				cmapChars.append(String.format("<%04X> <%04X>\n", glyph, Integer.valueOf(c)));
			}
			hex.append(String.format("%04X", glyph));
		}
		objects.add(new Object[] { "<< /Length LEN /Filter /FlateDecode >>", deflate("BT /F2 11 Tf 72 700 Td <" + hex + "> Tj ET") });
		final String cmap = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n"
			+ glyphs.size() + " beginbfchar\n" + cmapChars + "endbfchar\nendcmap CMapName currentdict /CMap defineresource pop end end";
		objects.add(new Object[] { "<< /Length LEN >>", cmap.getBytes(StandardCharsets.ISO_8859_1) });
		objects.add(new Object[] { "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 12 0 R >>" });
		objects.add(new Object[] { "<< /Length LEN >>", "q 595 0 0 842 0 0 cm /Im1 Do Q".getBytes(StandardCharsets.ISO_8859_1) });
		return pdf(objects, "");
	}

	// ------------------------------------------------------------------ stub model

	/** Answers like a careful model: one verified value, one invented snippet, one low-confidence value. */
	static final class StubModel implements DocumentConnector.Model
	{
		final AtomicReference<String> lastPrompt = new AtomicReference<String>();

		@Override
		public JsonNode ask(final String pSystem, final String pPrompt) throws ConnectorException
		{
			this.lastPrompt.set(pPrompt);
			final ObjectNode answer = Json.object();
			final ObjectNode fields = answer.putObject("fields");
			final ObjectNode number = fields.putObject("invoiceNumber");
			number.put("value", "INV-2026-0042");
			number.put("confidence", 0.97);
			number.put("page", 3); // wrong page on purpose: the snippet check finds page 1
			number.put("snippet", "Invoice n°  INV-2026-0042");
			final ObjectNode supplier = fields.putObject("supplier");
			supplier.put("value", "ACME Holding");
			supplier.put("confidence", 0.95);
			supplier.put("snippet", "Supplier: ACME Holding"); // not in the document
			final ObjectNode total = fields.putObject("total");
			total.put("value", 1234.5);
			total.put("confidence", 0.6);
			total.put("page", 2);
			total.put("snippet", "total: 1 234,50 eur");
			fields.putObject("vatNumber").putNull("value");
			return answer;
		}
	}

	static final String FIELDS = "invoiceNumber:the invoice number, supplier:the supplier name, total:amount including tax:decimal, vatNumber:VAT number";

	public static void main(final String[] args) throws Exception
	{
		// ---- PDF
		final List<PdfText.Page> pages = PdfText.pages(invoicePdf());
		check(pages.size() == 3, "three pages in tree order, got " + pages.size());
		check(pages.get(0).getText().contains("ACME Industries (France)"), "escaped parentheses: " + pages.get(0).getText());
		check(pages.get(0).getText().contains("Invoice n° INV-2026-0042"), "TJ array and octal WinAnsi degree sign: " + pages.get(0).getText());
		check(pages.get(0).getText().contains("\nDate: 2026-10-12"), "T* is a new line: " + pages.get(0).getText());
		check("Total: 1 234,50 EUR".equals(pages.get(1).getText()), "ToUnicode composite font from an object stream: " + pages.get(1).getText());
		check(pages.get(2).isEmpty(), "a scanned page has no text");
		rejects("not a PDF", () -> PdfText.pages("hello".getBytes(StandardCharsets.UTF_8)));
		final List<Object[]> encrypted = new ArrayList<Object[]>();
		encrypted.add(new Object[] { "<< /Type /Catalog /Pages 2 0 R >>" });
		encrypted.add(new Object[] { "<< /Type /Pages /Kids [] /Count 0 >>" });
		encrypted.add(new Object[] { "<< /Filter /Standard /V 2 /R 3 >>" });
		rejects("encrypted", () -> PdfText.pages(pdf(encrypted, "/Encrypt 3 0 R ")));

		// ---- text and HTML
		final List<DocumentText.Page> text = DocumentText.pages("﻿first page\fsecond page\f".getBytes(StandardCharsets.UTF_8), "txt");
		check(text.size() == 2 && "first page".equals(text.get(0).getText()), "form feed splits pages, BOM dropped");
		final List<DocumentText.Page> html = DocumentText.pages("<html><script>steal()</script><p>Hello &amp; welcome</p></html>".getBytes(StandardCharsets.UTF_8), "html");
		check(html.get(0).getText().contains("Hello & welcome") && !html.get(0).getText().contains("steal"), "html tags and scripts dropped");
		final List<DocumentText.Page> docx = DocumentText.pages(DocxExtractionTest.fixture(DocxExtractionTest.p("Supplier: ACME") + DocxExtractionTest.p("Total: 12"), null, null), "docx");
		check(docx.size() == 1 && "Supplier: ACME\nTotal: 12".equals(docx.get(0).getText()), "docx paragraphs as lines of one page: " + docx.get(0).getText());
		check(DocumentText.formatOf(null, "scan.TIFF") == null && "pdf".equals(DocumentText.formatOf(null, "a.PDF")), "format by extension");

		// ---- fields
		check(DocumentExtraction.fields(FIELDS).size() == 4, "four fields");
		check("decimal".equals(DocumentExtraction.fields(FIELDS).get(2).getType()), "field type");
		check(DocumentExtraction.fields("[{\"name\":\"a\",\"description\":\"b\"}]").size() == 1, "JSON field list");
		try
		{
			DocumentExtraction.fields("bad name:x");
			throw new AssertionError("invalid attribute name accepted");
		}
		catch (final IllegalArgumentException expected)
		{
			// refused
		}

		// ---- connector, uploaded content
		final ConnectorConfig config = new ConnectorConfig();
		config.setId("docs");
		config.setType("documents");
		config.setProperty("fields", FIELDS);
		config.setProperty("confidence.threshold", "0.8");
		final StubModel model = new StubModel();
		final DocumentConnector connector = new DocumentConnector().withModel(model);
		connector.configure(config);
		check(connector.validateConfiguration(config).getIssues().isEmpty(), "valid configuration");
		final Map<String, String> inputs = new HashMap<String, String>();
		inputs.put("content", Base64.getEncoder().encodeToString(invoicePdf()));
		inputs.put("name", "invoice-42.pdf");
		final JsonNode item = connector.execute(null, "extract", inputs, null);
		check("INV-2026-0042".equals(item.path("invoiceNumber").asText()), "value at top level for the mapping");
		check(item.path("_confidence").path("invoiceNumber").asDouble() == 0.97, "verified value keeps its confidence");
		check(item.path("_evidence").path("invoiceNumber").path("page").asInt() == 1, "page is where the snippet is found, not what the model said");
		check(item.path("_sources").path("invoiceNumber").asText().startsWith("invoice-42.pdf p.1: \""), "provenance text: " + item.path("_sources"));
		check(item.path("_confidence").path("supplier").asDouble() == DocumentExtraction.UNVERIFIED_CAP, "invented snippet capped");
		check(item.path("_sources").path("supplier").asText().contains("not found"), "unverified provenance says so");
		check(item.path("_evidence").path("total").path("page").asInt() == 2 && item.path("total").asDouble() == 1234.5, "accent/case/space-insensitive snippet match on page 2");
		check(!item.has("vatNumber") && !item.path("_confidence").has("vatNumber"), "null value is not an attribute");
		check(item.path("_proposed").toString().equals("[\"supplier\",\"total\"]"), "below threshold proposed: " + item.path("_proposed"));
		check(item.path(DocumentExtraction.MIN_CONFIDENCE).asDouble() == 0.8, "threshold carried for the engine");
		check(model.lastPrompt.get().contains("<document>\n[[page 1]]") && model.lastPrompt.get().contains("[[page 2]]\nTotal: 1 234,50 EUR"), "page markers in the prompt");
		inputs.put("threshold", "0.5");
		check(connector.execute(null, "extract", inputs, null).path("_proposed").size() == 0, "threshold input overrides the property");
		inputs.remove("threshold");
		// a model's 1.0 is capped below 1: at or above the threshold writes, so threshold 1 proposes everything
		final List<DocumentText.Page> one = DocumentText.pages("Supplier code ACME-7".getBytes(StandardCharsets.UTF_8), "txt");
		final List<DocumentExtraction.Field> code = DocumentExtraction.fields("code:the supplier code");
		final JsonNode sure = Json.parse("{\"fields\": {\"code\": {\"value\": \"ACME-7\", \"confidence\": 1.0, \"page\": 1, \"snippet\": \"Supplier code ACME-7\"}}}");
		final ObjectNode atOne = DocumentExtraction.item(sure, code, one, "a.txt", 1);
		check(atOne.path("_confidence").path("code").asDouble() == DocumentExtraction.MODEL_CAP && atOne.path("_evidence").path("code").path("confidence").asDouble() == 1
			&& atOne.path("_proposed").toString().equals("[\"code\"]"), "threshold 1 proposes a value the model gave 1.0: " + atOne);
		check(DocumentExtraction.item(sure, code, one, "a.txt", DocumentExtraction.MODEL_CAP).path("_proposed").size() == 0, "at the cap itself it is written");
		final JsonNode textOut = connector.execute(null, "text", inputs, null);
		check(textOut.path("pages").size() == 3 && textOut.path("emptyPages").toString().equals("[3]"), "text operation reports empty pages");

		// a scanned document never calls the model
		model.lastPrompt.set(null);
		final Map<String, String> scanned = new HashMap<String, String>();
		final List<Object[]> scan = new ArrayList<Object[]>();
		scan.add(new Object[] { "<< /Type /Catalog /Pages 2 0 R >>" });
		scan.add(new Object[] { "<< /Type /Pages /Kids [3 0 R] /Count 1 >>" });
		scan.add(new Object[] { "<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>" });
		scan.add(new Object[] { "<< /Length LEN >>", "q /Im1 Do Q".getBytes(StandardCharsets.ISO_8859_1) });
		scanned.put("content", Base64.getEncoder().encodeToString(pdf(scan, "")));
		scanned.put("name", "scan.pdf");
		final JsonNode none = connector.execute(null, "extract", scanned, null);
		check(model.lastPrompt.get() == null && none.path("note").asText().contains("OCR"), "scanned: no call, a note");

		// ---- folder confinement
		check(DocumentConnector.folderProblem("/tmp", null).contains(DocumentConnector.ROOTS_PROPERTY), "no roots: no folder");
		final Path root = Files.createTempDirectory("ebx-docs-root");
		final Path inbox = Files.createDirectory(root.resolve("inbox"));
		final Path outside = Files.createTempDirectory("ebx-docs-outside");
		check(DocumentConnector.folderProblem(inbox.toString(), root.toString()) == null, "folder inside a root");
		check(DocumentConnector.folderProblem(outside.toString(), root.toString()).contains("outside"), "folder outside the roots");
		check(DocumentConnector.folderProblem("relative/inbox", root.toString()).contains("absolute"), "relative folder refused");
		Files.write(inbox.resolve("a-invoice.pdf"), invoicePdf());
		Files.write(inbox.resolve("b-broken.pdf"), "%PDF-1.4 garbage".getBytes(StandardCharsets.ISO_8859_1));
		Files.write(inbox.resolve("c-notes.txt"), "Invoice n° INV-2026-0042\nTotal: 1 234,50 EUR".getBytes(StandardCharsets.UTF_8));
		Files.write(inbox.resolve("d-image.png"), new byte[] { 1, 2, 3 });
		Files.write(inbox.resolve(".hidden.pdf"), invoicePdf());
		Files.write(outside.resolve("secret.txt"), "secret".getBytes(StandardCharsets.UTF_8));
		Files.createSymbolicLink(inbox.resolve("e-link.txt"), outside.resolve("secret.txt"));
		System.setProperty(DocumentConnector.ROOTS_PROPERTY, root.toString());
		try
		{
			config.setProperty("folder", inbox.toString());
			connector.configure(config);
			final JsonNode list = connector.execute(null, "list", new HashMap<String, String>(), null);
			check(list.path("count").asInt() == 3, "pdf, broken pdf, txt listed; png, hidden file and symbolic link skipped: " + list);
			final Map<String, String> byPath = new HashMap<String, String>();
			byPath.put("path", "../" + outside.getFileName() + "/secret.txt");
			rejects("without path", () -> connector.execute(null, "text", byPath, null));
			byPath.put("path", "e-link.txt");
			rejects("no document", () -> connector.execute(null, "text", byPath, null));
			final JsonNode all = connector.execute(null, "extractAll", new HashMap<String, String>(), null);
			check(all.path("count").asInt() == 2 && all.path("errors").size() == 1, "one broken document reported, the others extracted: " + all.path("errors"));
			check("b-broken.pdf".equals(all.path("errors").get(0).path("document").asText()), "error names the document");
			check(all.path("items").get(0).has("modified"), "folder items carry their modification date");
			final Map<String, String> pattern = new HashMap<String, String>();
			pattern.put("pattern", "*.txt");
			check(connector.execute(null, "list", pattern, null).path("count").asInt() == 1, "pattern narrows the listing");
		}
		finally
		{
			System.clearProperty(DocumentConnector.ROOTS_PROPERTY);
		}
		config.setProperty("folder", inbox.toString());
		check(!connector.validateConfiguration(config).getIssues().isEmpty(), "folder refused once the roots are gone");
		System.out.println("DocumentFeedTest PASS: PDF pages (WinAnsi, ToUnicode, object stream, scanned, encrypted), text/HTML pages, verified snippets and confidence cap, upload, folder confinement, extractAll");
	}
}
