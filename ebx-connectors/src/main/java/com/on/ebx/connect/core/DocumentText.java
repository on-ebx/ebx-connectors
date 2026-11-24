package com.on.ebx.connect.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.codecs.DocxDocumentCodecProvider;
import com.on.ebx.connect.spi.ConnectorException;

/**
 * The text of one unstructured document, page by page (document feed, Gartner lot A): PDF through
 * {@link PdfText}, DOCX through the module's bounded {@link DocxDocumentCodecProvider} (a DOCX has no
 * page numbers: its text is one page), plain text, Markdown, CSV and HTML (tags dropped) as one page.
 * Pure: no file, no network; the caller hands the bytes.
 */
public final class DocumentText
{
	/** Formats this class reads, by file extension. */
	public static final List<String> FORMATS = Collections.unmodifiableList(java.util.Arrays.asList("pdf", "docx", "txt", "text", "md", "csv", "html", "htm"));

	/** One page of text, 1-based. */
	public static final class Page
	{
		private final int number;
		private final String text;

		public Page(final int pNumber, final String pText)
		{
			this.number = pNumber;
			this.text = pText == null ? "" : pText;
		}

		public int getNumber()
		{
			return this.number;
		}

		public String getText()
		{
			return this.text;
		}
	}

	private DocumentText()
	{
	}

	/** The format of a document: the explicit one when given, else the extension of its name; null when not supported. */
	public static String formatOf(final String pExplicit, final String pName)
	{
		String format = pExplicit == null ? "" : pExplicit.trim().toLowerCase(Locale.ENGLISH);
		if (format.isEmpty() && pName != null)
		{
			final int dot = pName.lastIndexOf('.');
			format = dot < 0 ? "" : pName.substring(dot + 1).toLowerCase(Locale.ENGLISH);
		}
		if ("text".equals(format)) return "txt";
		if ("htm".equals(format)) return "html";
		return FORMATS.contains(format) ? format : null;
	}

	/** The pages of a document. */
	public static List<Page> pages(final byte[] pContent, final String pFormat) throws ConnectorException
	{
		if (pContent == null) throw new ConnectorException("empty document");
		final String format = DocumentText.formatOf(pFormat, null);
		if (format == null) throw new ConnectorException("unsupported document format '" + pFormat + "' (supported: pdf, docx, txt, md, csv, html)");
		final List<Page> out = new ArrayList<Page>();
		if ("pdf".equals(format))
		{
			try
			{
				for (final PdfText.Page page : PdfText.pages(pContent)) out.add(new Page(page.getNumber(), page.getText()));
			}
			catch (final PdfText.PdfException ex)
			{
				throw new ConnectorException("PDF: " + ex.getMessage());
			}
			return out;
		}
		if ("docx".equals(format))
		{
			final JsonNode parsed = new DocxDocumentCodecProvider().parse(pContent, "docx", 100000, 8 * 1024 * 1024); // the expanded size, capped by the codec
			final StringBuilder text = new StringBuilder();
			int block = -1;
			for (final JsonNode row : parsed.path("rows"))
			{
				// chunks of one paragraph share a block index; table cells of one block are separated by a tab
				final int rowBlock = row.path("blockIndex").asInt(-2);
				if (text.length() > 0 && rowBlock != block) text.append('\n');
				else if (text.length() > 0 && row.has("tableCell") && row.path("textStart").asInt() == 0) text.append('\t');
				text.append(row.path("text").asText(""));
				block = rowBlock;
			}
			out.add(new Page(1, text.toString()));
			return out;
		}
		String text = new String(pContent, StandardCharsets.UTF_8);
		if (text.startsWith("﻿")) text = text.substring(1);
		if ("html".equals(format))
		{
			text = text.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("(?i)<br\\s*/?>|</p>|</div>|</li>|</tr>|</h[1-6]>", "\n")
				.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
		}
		// a form feed is a page break in a text export
		final String[] parts = text.split("\f", -1);
		for (int i = 0; i < parts.length; i++)
		{
			if (i == parts.length - 1 && parts[i].trim().isEmpty() && i > 0) break;
			out.add(new Page(i + 1, parts[i]));
		}
		return out;
	}

	/** The whole text, pages separated by a {@code [[page N]]} marker the extraction prompt and the snippet check rely on. */
	public static String joined(final List<Page> pPages, final int pMaxChars)
	{
		final StringBuilder out = new StringBuilder();
		for (final Page page : pPages)
		{
			if (out.length() >= pMaxChars) break;
			out.append("[[page ").append(page.getNumber()).append("]]\n").append(page.getText()).append("\n\n");
		}
		return out.length() > pMaxChars ? out.substring(0, pMaxChars) : out.toString();
	}
}
