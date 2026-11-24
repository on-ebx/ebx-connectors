package com.on.ebx.connect.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Text of a PDF, page by page, without any third-party library (document feed, Gartner lot A). It reads
 * what a born-digital PDF carries: the page tree, object streams, FlateDecode content streams, simple
 * fonts (WinAnsi read as windows-1252) and the {@code /ToUnicode} maps of composite fonts (what Word,
 * LibreOffice and most generators write). It does not do OCR: a scanned page has no text operator and
 * comes back empty, which {@link Page#isEmpty} lets the caller report. Encrypted files are refused.
 * <p>
 * Bounds: the input size is the caller's; each inflated stream is capped at {@link #MAX_STREAM} bytes,
 * the whole text at {@link #MAX_TEXT} characters and the page count at {@link #MAX_PAGES}. Nothing here
 * opens a file, a URL or an external entity: the input is a byte array.
 */
public final class PdfText
{
	public static final int MAX_STREAM = 16 * 1024 * 1024;
	public static final int MAX_TEXT = 2 * 1024 * 1024;
	public static final int MAX_PAGES = 2000;

	/** One page: its 1-based number and its text (lines separated by {@code \n}). */
	public static final class Page
	{
		private final int number;
		private final String text;

		Page(final int pNumber, final String pText)
		{
			this.number = pNumber;
			this.text = pText;
		}

		public int getNumber()
		{
			return this.number;
		}

		public String getText()
		{
			return this.text;
		}

		public boolean isEmpty()
		{
			return this.text.trim().isEmpty();
		}
	}

	/** A PDF that cannot be read (damaged, encrypted, not a PDF). */
	public static final class PdfException extends Exception
	{
		private static final long serialVersionUID = 1L;

		PdfException(final String pMessage)
		{
			super(pMessage);
		}
	}

	private static final Charset LATIN = StandardCharsets.ISO_8859_1;
	private static final Charset WIN_ANSI = Charset.forName("windows-1252");
	private static final Pattern OBJ = Pattern.compile("(\\d+)\\s+(\\d+)\\s+obj\\b");

	private final byte[] bytes;
	private final String raw;
	private final Map<Integer, Object> objects = new HashMap<Integer, Object>();
	private final Map<Integer, byte[]> streams = new HashMap<Integer, byte[]>();
	private final Map<Integer, Map<String, Object>> streamDicts = new HashMap<Integer, Map<String, Object>>();
	private final Map<Integer, Font> fonts = new HashMap<Integer, Font>();
	private int textBudget = MAX_TEXT;

	private PdfText(final byte[] pBytes)
	{
		this.bytes = pBytes;
		this.raw = new String(pBytes, LATIN);
	}

	/** The pages of a PDF, in reading order. */
	public static List<Page> pages(final byte[] pBytes) throws PdfException
	{
		if (pBytes == null || pBytes.length < 8 || !new String(pBytes, 0, 5, LATIN).equals("%PDF-"))
		{
			throw new PdfException("not a PDF (no %PDF- header)");
		}
		final PdfText pdf = new PdfText(pBytes);
		pdf.index();
		if (pdf.raw.contains("/Encrypt"))
		{
			throw new PdfException("encrypted PDF: remove the password protection before importing it");
		}
		return pdf.read();
	}

	// ------------------------------------------------------------------ object table

	private void index() throws PdfException
	{
		final Matcher m = OBJ.matcher(this.raw);
		int from = 0;
		while (m.find(from))
		{
			final int number = Integer.parseInt(m.group(1));
			final Parser parser = new Parser(this.raw, m.end());
			Object value;
			try
			{
				value = parser.value();
			}
			catch (final RuntimeException ex)
			{
				from = m.end();
				continue;
			}
			parser.skipSpace();
			if (value instanceof Map && this.raw.startsWith("stream", parser.pos))
			{
				@SuppressWarnings("unchecked")
				final Map<String, Object> dict = (Map<String, Object>) value;
				int start = parser.pos + 6;
				if (start < this.raw.length() && this.raw.charAt(start) == '\r') start++;
				if (start < this.raw.length() && this.raw.charAt(start) == '\n') start++;
				final Object declared = dict.get("Length");
				int end = -1;
				if (declared instanceof Number)
				{
					final int length = ((Number) declared).intValue();
					if (length >= 0 && start + length <= this.raw.length() && this.raw.startsWith("endstream", PdfText.skip(this.raw, start + length)))
						end = start + length;
				}
				if (end < 0)
				{
					end = this.raw.indexOf("endstream", start);
					if (end < 0) break;
					while (end > start && (this.raw.charAt(end - 1) == '\n' || this.raw.charAt(end - 1) == '\r')) end--;
				}
				final byte[] data = new byte[end - start];
				System.arraycopy(this.bytes, start, data, 0, data.length);
				this.streams.put(Integer.valueOf(number), data);
				this.streamDicts.put(Integer.valueOf(number), dict);
				this.objects.put(Integer.valueOf(number), dict);
				from = end;
			}
			else
			{
				this.objects.put(Integer.valueOf(number), value);
				from = Math.max(parser.pos, m.end());
			}
		}
		// compressed object streams (PDF 1.5+): the page and font dictionaries often live there
		for (final Integer number : new ArrayList<Integer>(this.streamDicts.keySet()))
		{
			final Map<String, Object> dict = this.streamDicts.get(number);
			if (!"ObjStm".equals(dict.get("Type"))) continue;
			final byte[] data = this.decoded(number.intValue());
			if (data == null) continue;
			final String text = new String(data, LATIN);
			final int count = PdfText.intOf(dict.get("N"), 0);
			final int first = PdfText.intOf(dict.get("First"), 0);
			final Parser header = new Parser(text, 0);
			for (int i = 0; i < count; i++)
			{
				try
				{
					final int objectNumber = ((Number) header.value()).intValue();
					final int offset = ((Number) header.value()).intValue();
					if (this.objects.get(Integer.valueOf(objectNumber)) == null && first + offset < text.length())
						this.objects.put(Integer.valueOf(objectNumber), new Parser(text, first + offset).value());
				}
				catch (final RuntimeException ex)
				{
					break;
				}
			}
		}
		if (this.objects.isEmpty()) throw new PdfException("no PDF object found (damaged file)");
	}

	private static int skip(final String pText, final int pFrom)
	{
		int i = pFrom;
		while (i < pText.length() && Character.isWhitespace(pText.charAt(i))) i++;
		return i;
	}

	private Object resolve(final Object pValue)
	{
		Object value = pValue;
		for (int depth = 0; value instanceof Ref && depth < 32; depth++)
		{
			value = this.objects.get(Integer.valueOf(((Ref) value).number));
		}
		return value instanceof Ref ? null : value;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> dict(final Object pValue)
	{
		final Object value = this.resolve(pValue);
		return value instanceof Map ? (Map<String, Object>) value : null;
	}

	/** The decoded bytes of a stream object, null when its filter is not supported. */
	private byte[] decoded(final int pNumber)
	{
		final byte[] data = this.streams.get(Integer.valueOf(pNumber));
		final Map<String, Object> dict = this.streamDicts.get(Integer.valueOf(pNumber));
		if (data == null || dict == null) return null;
		Object filter = this.resolve(dict.get("Filter"));
		if (filter instanceof List)
		{
			final List<?> filters = (List<?>) filter;
			if (filters.isEmpty()) filter = null;
			else if (filters.size() == 1) filter = filters.get(0);
			else return null;
		}
		if (filter == null) return data;
		if (!"FlateDecode".equals(filter) && !"Fl".equals(filter)) return null;
		final Inflater inflater = new Inflater();
		try
		{
			inflater.setInput(data);
			final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(MAX_STREAM, data.length * 4 + 64));
			final byte[] buffer = new byte[8192];
			while (!inflater.finished())
			{
				final int n = inflater.inflate(buffer);
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
				out.write(buffer, 0, n);
				if (out.size() > MAX_STREAM) return null;
			}
			return out.toByteArray();
		}
		catch (final DataFormatException ex)
		{
			return null;
		}
		finally
		{
			inflater.end();
		}
	}

	// ------------------------------------------------------------------ pages

	private List<Page> read() throws PdfException
	{
		final List<Map<String, Object>> pageDicts = new ArrayList<Map<String, Object>>();
		final Map<Map<String, Object>, Map<String, Object>> resources = new LinkedHashMap<Map<String, Object>, Map<String, Object>>();
		Map<String, Object> catalog = null;
		for (final Object value : this.objects.values())
		{
			if (value instanceof Map && "Catalog".equals(((Map<?, ?>) value).get("Type")))
			{
				@SuppressWarnings("unchecked")
				final Map<String, Object> found = (Map<String, Object>) value;
				catalog = found;
				break;
			}
		}
		if (catalog != null)
		{
			this.walk(this.dict(catalog.get("Pages")), null, pageDicts, resources, new HashSet<Object>(), 0);
		}
		if (pageDicts.isEmpty())
		{
			// no usable page tree: every /Type /Page object, in object-number order
			for (final Object value : new TreeMap<Integer, Object>(this.objects).values())
			{
				if (value instanceof Map && "Page".equals(((Map<?, ?>) value).get("Type")))
				{
					@SuppressWarnings("unchecked")
					final Map<String, Object> page = (Map<String, Object>) value;
					pageDicts.add(page);
					resources.put(page, this.dict(page.get("Resources")));
					if (pageDicts.size() >= MAX_PAGES) break;
				}
			}
		}
		if (pageDicts.isEmpty()) throw new PdfException("no page found (damaged file)");
		final List<Page> pages = new ArrayList<Page>();
		for (final Map<String, Object> page : pageDicts)
		{
			final StringBuilder content = new StringBuilder();
			final Object contents = this.resolve(page.get("Contents"));
			final List<Object> parts = new ArrayList<Object>();
			if (contents instanceof List) parts.addAll((List<?>) contents);
			else if (page.get("Contents") != null) parts.add(page.get("Contents"));
			for (final Object part : parts)
			{
				if (!(part instanceof Ref)) continue;
				final byte[] data = this.decoded(((Ref) part).number);
				if (data != null) content.append(new String(data, LATIN)).append('\n');
			}
			final String text = this.textBudget <= 0 ? "" : this.interpret(content.toString(), this.fontsOf(resources.get(page)));
			this.textBudget -= text.length();
			pages.add(new Page(pages.size() + 1, text));
		}
		return pages;
	}

	private void walk(final Map<String, Object> pNode, final Map<String, Object> pInherited, final List<Map<String, Object>> pOut,
		final Map<Map<String, Object>, Map<String, Object>> pResources, final Set<Object> pSeen, final int pDepth)
	{
		if (pNode == null || pDepth > 64 || pOut.size() >= MAX_PAGES || !pSeen.add(System.identityHashCode(pNode) + ":" + pNode.size())) return;
		final Map<String, Object> own = this.dict(pNode.get("Resources"));
		final Map<String, Object> resources = own != null ? own : pInherited;
		final Object kids = this.resolve(pNode.get("Kids"));
		if ("Page".equals(pNode.get("Type")) || !(kids instanceof List))
		{
			pOut.add(pNode);
			pResources.put(pNode, resources);
			return;
		}
		for (final Object kid : (List<?>) kids)
		{
			this.walk(this.dict(kid), resources, pOut, pResources, pSeen, pDepth + 1);
		}
	}

	// ------------------------------------------------------------------ fonts

	/** How the bytes of a string shown with one font become text. */
	static final class Font
	{
		int codeLength = 1;
		Map<Integer, String> unicode;
		/** Simple fonts: the base single-byte encoding and the /Differences that override it. */
		Charset single = WIN_ANSI;
		Map<Integer, String> differences;

		String decode(final String pBytes)
		{
			if (this.unicode == null)
			{
				if (this.differences == null) return new String(pBytes.getBytes(LATIN), this.single);
				final StringBuilder out = new StringBuilder();
				for (int i = 0; i < pBytes.length(); i++)
				{
					final String mapped = this.differences.get(Integer.valueOf(pBytes.charAt(i) & 0xFF));
					out.append(mapped != null ? mapped : new String(new byte[] { (byte) pBytes.charAt(i) }, this.single));
				}
				return out.toString();
			}
			final StringBuilder out = new StringBuilder();
			for (int i = 0; i + this.codeLength <= pBytes.length(); i += this.codeLength)
			{
				int code = 0;
				for (int k = 0; k < this.codeLength; k++) code = (code << 8) | (pBytes.charAt(i + k) & 0xFF);
				final String mapped = this.unicode.get(Integer.valueOf(code));
				if (mapped != null) out.append(mapped);
				else if (this.codeLength == 1) out.append(new String(new byte[] { (byte) code }, WIN_ANSI));
			}
			return out.toString();
		}
	}

	private Map<String, Font> fontsOf(final Map<String, Object> pResources)
	{
		final Map<String, Font> out = new HashMap<String, Font>();
		final Map<String, Object> fontDict = pResources == null ? null : this.dict(pResources.get("Font"));
		if (fontDict == null) return out;
		for (final Map.Entry<String, Object> entry : fontDict.entrySet())
		{
			final Object ref = entry.getValue();
			final Integer key = ref instanceof Ref ? Integer.valueOf(((Ref) ref).number) : null;
			Font font = key == null ? null : this.fonts.get(key);
			if (font == null)
			{
				font = this.font(this.dict(ref));
				if (key != null) this.fonts.put(key, font);
			}
			out.put(entry.getKey(), font);
		}
		return out;
	}

	private Font font(final Map<String, Object> pFont)
	{
		final Font font = new Font();
		if (pFont == null) return font;
		final boolean composite = "Type0".equals(pFont.get("Subtype"));
		if (composite) font.codeLength = 2;
		final Object toUnicode = pFont.get("ToUnicode");
		if (toUnicode instanceof Ref)
		{
			final byte[] cmap = this.decoded(((Ref) toUnicode).number);
			if (cmap != null)
			{
				final Map<Integer, String> map = new HashMap<Integer, String>();
				final int length = PdfText.parseCMap(new String(cmap, LATIN), map);
				font.unicode = map;
				if (length > 0) font.codeLength = length;
			}
		}
		else if (composite)
		{
			// Identity-H without ToUnicode: glyph ids, not characters. Nothing faithful can be said.
			font.unicode = new HashMap<Integer, String>();
		}
		if (!composite)
		{
			final Object encoding = this.resolve(pFont.get("Encoding"));
			final Object base = encoding instanceof Map ? this.resolve(((Map<?, ?>) encoding).get("BaseEncoding")) : encoding;
			if ("MacRomanEncoding".equals(base) && MAC_ROMAN != null) font.single = MAC_ROMAN;
			if (encoding instanceof Map && this.resolve(((Map<?, ?>) encoding).get("Differences")) instanceof List)
			{
				font.differences = new HashMap<Integer, String>();
				int code = 0;
				for (final Object entry : (List<?>) this.resolve(((Map<?, ?>) encoding).get("Differences")))
				{
					if (entry instanceof Number) code = ((Number) entry).intValue();
					else if (entry instanceof String)
					{
						final String character = PdfText.glyph((String) entry);
						if (character != null) font.differences.put(Integer.valueOf(code), character);
						code++;
					}
				}
			}
		}
		return font;
	}

	private static final Charset MAC_ROMAN = PdfText.charset("x-MacRoman");
	private static final Map<String, String> GLYPHS = new HashMap<String, String>();
	static
	{
		final String[] names = { "space", " ", "exclam", "!", "quotedbl", "\"", "numbersign", "#", "dollar", "$", "percent", "%", "ampersand", "&",
			"quotesingle", "'", "quoteright", "\u2019", "quoteleft", "\u2018", "parenleft", "(", "parenright", ")", "asterisk", "*", "plus", "+",
			"comma", ",", "hyphen", "-", "period", ".", "slash", "/", "zero", "0", "one", "1", "two", "2", "three", "3", "four", "4", "five", "5",
			"six", "6", "seven", "7", "eight", "8", "nine", "9", "colon", ":", "semicolon", ";", "less", "<", "equal", "=", "greater", ">",
			"question", "?", "at", "@", "bracketleft", "[", "backslash", "\\", "bracketright", "]", "underscore", "_", "braceleft", "{", "bar", "|",
			"braceright", "}", "asciitilde", "~", "quotedblleft", "\u201C", "quotedblright", "\u201D", "quotedblbase", "\u201E", "endash", "\u2013",
			"emdash", "\u2014", "bullet", "\u2022", "ellipsis", "\u2026", "Euro", "\u20AC", "degree", "\u00B0", "section", "\u00A7",
			"copyright", "\u00A9", "registered", "\u00AE", "trademark", "\u2122", "guillemotleft", "\u00AB", "guillemotright", "\u00BB",
			"germandbls", "\u00DF", "ae", "\u00E6", "AE", "\u00C6", "oe", "\u0153", "OE", "\u0152", "oslash", "\u00F8", "Oslash", "\u00D8",
			"ccedilla", "\u00E7", "Ccedilla", "\u00C7", "fi", "fi", "fl", "fl", "nbspace", "\u00A0", "sterling", "\u00A3", "yen", "\u00A5",
			"periodcentered", "\u00B7", "minus", "\u2212", "multiply", "\u00D7", "divide", "\u00F7", "eth", "\u00F0", "thorn", "\u00FE" };
		for (int i = 0; i + 1 < names.length; i += 2) GLYPHS.put(names[i], names[i + 1]);
		final String[][] accents = { { "grave", "\u0300" }, { "acute", "\u0301" }, { "circumflex", "\u0302" }, { "tilde", "\u0303" },
			{ "dieresis", "\u0308" }, { "ring", "\u030A" }, { "cedilla", "\u0327" }, { "caron", "\u030C" } };
		for (final String letter : new String[] { "a", "e", "i", "o", "u", "y", "n", "c", "s", "z", "A", "E", "I", "O", "U", "Y", "N", "C", "S", "Z" })
		{
			for (final String[] accent : accents)
			{
				final String composed = java.text.Normalizer.normalize(letter + accent[1], java.text.Normalizer.Form.NFC);
				if (composed.length() == 1) GLYPHS.put(letter + accent[0], composed);
			}
		}
	}

	private static Charset charset(final String pName)
	{
		try
		{
			return Charset.forName(pName);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** The character of a glyph name ({@code eacute}, {@code uni00E9}, {@code A}); null when unknown. */
	static String glyph(final String pName)
	{
		if (pName.length() == 1) return pName;
		final String known = GLYPHS.get(pName);
		if (known != null) return known;
		if (pName.matches("uni[0-9A-Fa-f]{4}")) return String.valueOf((char) Integer.parseInt(pName.substring(3), 16));
		if (pName.matches("u[0-9A-Fa-f]{4,6}")) return new String(Character.toChars(Integer.parseInt(pName.substring(1), 16)));
		final int dot = pName.indexOf('.');
		return dot > 0 ? PdfText.glyph(pName.substring(0, dot)) : null;
	}

	/** Reads the bfchar / bfrange entries of a ToUnicode CMap; returns the code length in bytes (0 when unknown). */
	static int parseCMap(final String pCMap, final Map<Integer, String> pOut)
	{
		int codeLength = 0;
		final Matcher space = Pattern.compile("begincodespacerange\\s*<([0-9A-Fa-f]+)>").matcher(pCMap);
		if (space.find()) codeLength = space.group(1).length() / 2;
		final Matcher chars = Pattern.compile("beginbfchar(.*?)endbfchar", Pattern.DOTALL).matcher(pCMap);
		while (chars.find())
		{
			final Matcher pair = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]*)>").matcher(chars.group(1));
			while (pair.find())
			{
				if (codeLength == 0) codeLength = pair.group(1).length() / 2;
				pOut.put(Integer.valueOf(Integer.parseInt(pair.group(1), 16)), PdfText.utf16(pair.group(2)));
			}
		}
		final Matcher ranges = Pattern.compile("beginbfrange(.*?)endbfrange", Pattern.DOTALL).matcher(pCMap);
		while (ranges.find())
		{
			final Matcher range = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<[0-9A-Fa-f]*>|\\[[^\\]]*\\])").matcher(ranges.group(1));
			while (range.find())
			{
				if (codeLength == 0) codeLength = range.group(1).length() / 2;
				final int low = Integer.parseInt(range.group(1), 16);
				final int high = Math.min(Integer.parseInt(range.group(2), 16), low + 65535);
				final String target = range.group(3);
				if (target.startsWith("["))
				{
					final Matcher each = Pattern.compile("<([0-9A-Fa-f]*)>").matcher(target);
					for (int code = low; code <= high && each.find(); code++)
						pOut.put(Integer.valueOf(code), PdfText.utf16(each.group(1)));
				}
				else
				{
					final String hex = target.substring(1, target.length() - 1);
					if (hex.isEmpty()) continue;
					final String start = PdfText.utf16(hex);
					for (int code = low; code <= high; code++)
					{
						final int delta = code - low;
						pOut.put(Integer.valueOf(code), start.substring(0, start.length() - 1) + (char) (start.charAt(start.length() - 1) + delta));
					}
				}
			}
		}
		return codeLength;
	}

	private static String utf16(final String pHex)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i + 4 <= pHex.length(); i += 4) out.append((char) Integer.parseInt(pHex.substring(i, i + 4), 16));
		if (pHex.length() == 2) out.append((char) Integer.parseInt(pHex, 16));
		return out.toString();
	}

	// ------------------------------------------------------------------ content stream

	private String interpret(final String pContent, final Map<String, Font> pFonts)
	{
		final StringBuilder out = new StringBuilder();
		final Parser parser = new Parser(pContent, 0);
		final List<Object> operands = new ArrayList<Object>();
		Font font = new Font();
		while (out.length() < this.textBudget)
		{
			final Object token;
			try
			{
				token = parser.contentToken();
			}
			catch (final RuntimeException ex)
			{
				break;
			}
			if (token == null) break;
			if (!(token instanceof Operator))
			{
				operands.add(token);
				if (operands.size() > 64) operands.remove(0);
				continue;
			}
			final String op = ((Operator) token).name;
			if ("BI".equals(op))
			{
				parser.skipInlineImage();
			}
			else if ("Tf".equals(op) && operands.size() >= 2 && operands.get(operands.size() - 2) instanceof String)
			{
				final Font chosen = pFonts.get(operands.get(operands.size() - 2));
				font = chosen == null ? new Font() : chosen;
			}
			else if ("Tj".equals(op) && !operands.isEmpty() && operands.get(operands.size() - 1) instanceof PdfString)
			{
				out.append(font.decode(((PdfString) operands.get(operands.size() - 1)).value));
			}
			else if (("'".equals(op) || "\"".equals(op)) && !operands.isEmpty() && operands.get(operands.size() - 1) instanceof PdfString)
			{
				PdfText.newline(out);
				out.append(font.decode(((PdfString) operands.get(operands.size() - 1)).value));
			}
			else if ("TJ".equals(op) && !operands.isEmpty() && operands.get(operands.size() - 1) instanceof List)
			{
				for (final Object part : (List<?>) operands.get(operands.size() - 1))
				{
					if (part instanceof PdfString) out.append(font.decode(((PdfString) part).value));
					else if (part instanceof Number && ((Number) part).doubleValue() < -200 && out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
				}
			}
			else if ("T*".equals(op) || "ET".equals(op))
			{
				PdfText.newline(out);
			}
			else if (("Td".equals(op) || "TD".equals(op)) && operands.size() >= 2 && operands.get(operands.size() - 1) instanceof Number)
			{
				final double ty = ((Number) operands.get(operands.size() - 1)).doubleValue();
				final double tx = operands.get(operands.size() - 2) instanceof Number ? ((Number) operands.get(operands.size() - 2)).doubleValue() : 0;
				if (Math.abs(ty) > 0.01) PdfText.newline(out);
				else if (tx > 0.01 && out.length() > 0 && out.charAt(out.length() - 1) != ' ' && out.charAt(out.length() - 1) != '\n') out.append(' ');
			}
			else if ("Tm".equals(op))
			{
				PdfText.newline(out);
			}
			operands.clear();
		}
		return PdfText.tidy(out.toString());
	}

	private static void newline(final StringBuilder pOut)
	{
		if (pOut.length() > 0 && pOut.charAt(pOut.length() - 1) != '\n') pOut.append('\n');
	}

	private static String tidy(final String pText)
	{
		final StringBuilder out = new StringBuilder();
		for (final String line : pText.split("\n"))
		{
			final String trimmed = line.replaceAll("[ \\t\\u00A0]+", " ").trim();
			if (!trimmed.isEmpty()) out.append(trimmed).append('\n');
		}
		return out.toString().trim();
	}

	private static int intOf(final Object pValue, final int pDefault)
	{
		return pValue instanceof Number ? ((Number) pValue).intValue() : pDefault;
	}

	// ------------------------------------------------------------------ object syntax

	/** An indirect reference {@code N G R}. */
	static final class Ref
	{
		final int number;

		Ref(final int pNumber)
		{
			this.number = pNumber;
		}
	}

	/** A string operand, its bytes kept as ISO-8859-1 characters. */
	static final class PdfString
	{
		final String value;

		PdfString(final String pValue)
		{
			this.value = pValue;
		}
	}

	/** A content-stream operator. */
	static final class Operator
	{
		final String name;

		Operator(final String pName)
		{
			this.name = pName;
		}
	}

	/** Recursive-descent reader of PDF objects and content-stream tokens. Names come back as String without the slash. */
	static final class Parser
	{
		private final String text;
		int pos;

		Parser(final String pText, final int pPos)
		{
			this.text = pText;
			this.pos = pPos;
		}

		void skipSpace()
		{
			while (this.pos < this.text.length())
			{
				final char c = this.text.charAt(this.pos);
				if (c == '%')
				{
					while (this.pos < this.text.length() && this.text.charAt(this.pos) != '\n' && this.text.charAt(this.pos) != '\r') this.pos++;
				}
				else if (Character.isWhitespace(c) || c == 0)
				{
					this.pos++;
				}
				else
				{
					return;
				}
			}
		}

		Object value()
		{
			this.skipSpace();
			if (this.pos >= this.text.length()) throw new IllegalStateException("end of data");
			final char c = this.text.charAt(this.pos);
			if (c == '<' && this.peek(1) == '<') return this.dictionary();
			if (c == '<') return this.hex();
			if (c == '(') return this.literal();
			if (c == '[') return this.array();
			if (c == '/') return this.name();
			if (c == '+' || c == '-' || c == '.' || Character.isDigit(c))
			{
				final Number number = this.number();
				// N G R
				final int save = this.pos;
				if (number instanceof Integer)
				{
					this.skipSpace();
					if (this.pos < this.text.length() && Character.isDigit(this.text.charAt(this.pos)))
					{
						final Number generation = this.number();
						this.skipSpace();
						if (generation instanceof Integer && this.pos < this.text.length() && this.text.charAt(this.pos) == 'R'
							&& !this.isRegular(this.peek(1)))
						{
							this.pos++;
							return new Ref(number.intValue());
						}
					}
				}
				this.pos = save;
				return number;
			}
			final String word = this.word();
			if ("true".equals(word)) return Boolean.TRUE;
			if ("false".equals(word)) return Boolean.FALSE;
			if ("null".equals(word)) return null;
			throw new IllegalStateException("unexpected token " + word);
		}

		/** One content-stream token: an operand (number, string, name, array, dictionary) or an {@link Operator}; null at the end. */
		Object contentToken()
		{
			this.skipSpace();
			if (this.pos >= this.text.length()) return null;
			final char c = this.text.charAt(this.pos);
			if (c == '<' || c == '(' || c == '[' || c == '/' || c == '+' || c == '-' || c == '.' || Character.isDigit(c))
			{
				if (c == '+' || c == '-' || c == '.' || Character.isDigit(c)) return this.number();
				return this.value();
			}
			if (c == ']' || c == '>' || c == ')' || c == '{' || c == '}')
			{
				this.pos++;
				return this.contentToken();
			}
			return new Operator(this.word());
		}

		void skipInlineImage()
		{
			final int end = this.text.indexOf("EI", this.pos);
			this.pos = end < 0 ? this.text.length() : end + 2;
		}

		private char peek(final int pOffset)
		{
			return this.pos + pOffset < this.text.length() ? this.text.charAt(this.pos + pOffset) : 0;
		}

		private boolean isRegular(final char pChar)
		{
			return pChar != 0 && !Character.isWhitespace(pChar) && "()<>[]{}/%".indexOf(pChar) < 0;
		}

		private String word()
		{
			final int start = this.pos;
			while (this.pos < this.text.length() && this.isRegular(this.text.charAt(this.pos))) this.pos++;
			if (this.pos == start) this.pos++;
			return this.text.substring(start, this.pos);
		}

		private Number number()
		{
			final int start = this.pos;
			while (this.pos < this.text.length() && "+-.0123456789".indexOf(this.text.charAt(this.pos)) >= 0) this.pos++;
			final String s = this.text.substring(start, this.pos);
			try
			{
				if (s.indexOf('.') < 0) return Integer.valueOf(Integer.parseInt(s));
				return Double.valueOf(Double.parseDouble(s));
			}
			catch (final NumberFormatException ex)
			{
				return Integer.valueOf(0);
			}
		}

		private String name()
		{
			this.pos++;
			final StringBuilder out = new StringBuilder();
			while (this.pos < this.text.length() && this.isRegular(this.text.charAt(this.pos)))
			{
				final char c = this.text.charAt(this.pos);
				if (c == '#' && this.pos + 2 < this.text.length())
				{
					try
					{
						out.append((char) Integer.parseInt(this.text.substring(this.pos + 1, this.pos + 3), 16));
						this.pos += 3;
						continue;
					}
					catch (final NumberFormatException ex)
					{
						// a plain #
					}
				}
				out.append(c);
				this.pos++;
			}
			return out.toString();
		}

		private Map<String, Object> dictionary()
		{
			this.pos += 2;
			final Map<String, Object> out = new LinkedHashMap<String, Object>();
			while (true)
			{
				this.skipSpace();
				if (this.pos >= this.text.length()) throw new IllegalStateException("unterminated dictionary");
				if (this.text.charAt(this.pos) == '>' && this.peek(1) == '>')
				{
					this.pos += 2;
					return out;
				}
				if (this.text.charAt(this.pos) != '/') throw new IllegalStateException("dictionary key expected");
				final String key = this.name();
				out.put(key, this.value());
			}
		}

		private List<Object> array()
		{
			this.pos++;
			final List<Object> out = new ArrayList<Object>();
			while (true)
			{
				this.skipSpace();
				if (this.pos >= this.text.length()) throw new IllegalStateException("unterminated array");
				if (this.text.charAt(this.pos) == ']')
				{
					this.pos++;
					return out;
				}
				out.add(this.value());
			}
		}

		private PdfString hex()
		{
			this.pos++;
			final StringBuilder digits = new StringBuilder();
			while (this.pos < this.text.length() && this.text.charAt(this.pos) != '>')
			{
				final char c = this.text.charAt(this.pos++);
				if (Character.digit(c, 16) >= 0) digits.append(c);
			}
			this.pos++;
			if (digits.length() % 2 == 1) digits.append('0');
			final StringBuilder out = new StringBuilder();
			for (int i = 0; i < digits.length(); i += 2) out.append((char) Integer.parseInt(digits.substring(i, i + 2), 16));
			return new PdfString(out.toString());
		}

		private PdfString literal()
		{
			this.pos++;
			final StringBuilder out = new StringBuilder();
			int depth = 1;
			while (this.pos < this.text.length())
			{
				final char c = this.text.charAt(this.pos++);
				if (c == '\\' && this.pos < this.text.length())
				{
					final char e = this.text.charAt(this.pos++);
					switch (e)
					{
						case 'n': out.append('\n'); break;
						case 'r': out.append('\r'); break;
						case 't': out.append('\t'); break;
						case 'b': out.append('\b'); break;
						case 'f': out.append('\f'); break;
						case '\r': if (this.pos < this.text.length() && this.text.charAt(this.pos) == '\n') this.pos++; break;
						case '\n': break;
						default:
							if (e >= '0' && e <= '7')
							{
								int code = e - '0';
								for (int k = 0; k < 2 && this.pos < this.text.length() && this.text.charAt(this.pos) >= '0' && this.text.charAt(this.pos) <= '7'; k++)
									code = code * 8 + (this.text.charAt(this.pos++) - '0');
								out.append((char) (code & 0xFF));
							}
							else out.append(e);
					}
				}
				else if (c == '(')
				{
					depth++;
					out.append(c);
				}
				else if (c == ')')
				{
					if (--depth == 0) break;
					out.append(c);
				}
				else out.append(c);
			}
			return new PdfString(out.toString());
		}
	}
}
