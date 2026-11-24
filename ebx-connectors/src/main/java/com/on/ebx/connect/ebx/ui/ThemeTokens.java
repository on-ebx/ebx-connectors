package com.on.ebx.connect.ebx.ui;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.orchestranetworks.ui.UIBodyWriter;
import com.orchestranetworks.ui.form.UIFormContext;
import com.orchestranetworks.ui.perspective.Perspective;
import com.orchestranetworks.ui.perspective.PerspectiveTheme;
import com.orchestranetworks.userservice.UserServiceRequest;

/**
 * The colours of the perspective that displays a screen, as CSS custom properties (audit C-40). Every screen of the module
 * writes {@link #of} once per request; its CSS reads {@code var(--ebx-main, <the module's own colour>)}, so a token this
 * block does not set (no perspective, no theme, a colour the theme leaves empty or that is not a plain colour) falls back
 * to the colour the screen always had.
 *
 * <p>Shared tokens (same names in ebx-connectors, ebx-data-catalog and ebx-mdm-factory), each fed by one getter of
 * {@link PerspectiveTheme}: {@code --ebx-main} (main colour), {@code --ebx-focus}, {@code --ebx-header},
 * {@code --ebx-second-header}, {@code --ebx-button} (buttons highlight colour), {@code --ebx-table-header},
 * {@code --ebx-table-odd}, {@code --ebx-table-even}, {@code --ebx-table-border} (table body inner horizontal borders,
 * else outer border), {@code --ebx-selection} (navigation pane selection colour). {@code --ebx-main-text},
 * {@code --ebx-header-text} and {@code --ebx-button-text} are the text drawn on those backgrounds: the theme's own text
 * colour when it reaches a 4.5:1 contrast with its background, else white, near-black or black, the first that does.
 *
 * <p>Module tokens, derived from the main colour so the tints of the accent follow its hue: {@code --ftc-accent-soft}
 * (light tint for selected or highlighted areas), {@code --ftc-accent-line} (tinted border) and {@code --ftc-accent-ink}
 * (the main colour, darkened until it reads at 4.5:1 on white and on the soft tint, for accent text and links).
 *
 * <p>Every value goes through {@link #sanitize}: only {@code #rgb}, {@code #rrggbb}, {@code rgb()}/{@code rgba()} with
 * integer channels and CSS named colours pass, anything else is dropped, since the values end up in a style block.
 */
public final class ThemeTokens
{
	static final String MAIN = "--ebx-main";
	static final String MAIN_TEXT = "--ebx-main-text";
	static final String FOCUS = "--ebx-focus";
	static final String HEADER = "--ebx-header";
	static final String HEADER_TEXT = "--ebx-header-text";
	static final String SECOND_HEADER = "--ebx-second-header";
	static final String BUTTON = "--ebx-button";
	static final String BUTTON_TEXT = "--ebx-button-text";
	static final String TABLE_HEADER = "--ebx-table-header";
	static final String TABLE_ODD = "--ebx-table-odd";
	static final String TABLE_EVEN = "--ebx-table-even";
	static final String TABLE_BORDER = "--ebx-table-border";
	static final String SELECTION = "--ebx-selection";
	static final String ACCENT_SOFT = "--ftc-accent-soft";
	static final String ACCENT_LINE = "--ftc-accent-line";
	static final String ACCENT_INK = "--ftc-accent-ink";

	/** Marks the block, so a page copied elsewhere (the release notes printed in a new window) can carry it along. */
	static final String ATTRIBUTE = "data-ebx-theme";
	/** Minimum contrast of a text colour on its background (WCAG AA, normal text). */
	static final double MIN_CONTRAST = 4.5;
	static final String WHITE = "#ffffff";
	static final String NEAR_BLACK = "#202124";
	static final String BLACK = "#000000";
	/** Share of the main colour in its soft tint and in its tinted border, the rest being white. */
	private static final double SOFT_SHARE = 0.09;
	private static final double LINE_SHARE = 0.25;

	private static final Pattern HEX = Pattern.compile("#?([0-9a-f]{3}|[0-9a-f]{6})");
	private static final Pattern RGB = Pattern.compile("rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*(?:,\\s*(0|1|0?\\.\\d{1,4}|1\\.0{1,4})\\s*)?\\)");

	private ThemeTokens()
	{
	}

	/** The block of the perspective of a user service request; "" when there is nothing to set or it cannot be read. */
	public static String of(final UserServiceRequest pRequest)
	{
		try
		{
			return pRequest == null ? "" : ThemeTokens.styleBlock(pRequest.getCurrentPerspective());
		}
		catch (final RuntimeException e)
		{
			return "";
		}
	}

	/** The block of the perspective of a record form; "" when there is nothing to set or it cannot be read. */
	public static String of(final UIFormContext pContext)
	{
		try
		{
			return pContext == null ? "" : ThemeTokens.styleBlock(pContext.getCurrentPerspective());
		}
		catch (final RuntimeException e)
		{
			return "";
		}
	}

	/** Writes the block of a user service request (nothing when empty). */
	public static void write(final UIBodyWriter pWriter, final UserServiceRequest pRequest)
	{
		final String block = ThemeTokens.of(pRequest);
		if (!block.isEmpty())
		{
			pWriter.add(block);
		}
	}

	/** Writes the block of a record form (nothing when empty). */
	public static void write(final UIBodyWriter pWriter, final UIFormContext pContext)
	{
		final String block = ThemeTokens.of(pContext);
		if (!block.isEmpty())
		{
			pWriter.add(block);
		}
	}

	static String styleBlock(final Perspective pPerspective)
	{
		if (pPerspective == null)
		{
			return "";
		}
		final PerspectiveTheme theme;
		try
		{
			theme = pPerspective.getTheme();
		}
		catch (final RuntimeException e)
		{
			return "";
		}
		return ThemeTokens.styleBlock(theme);
	}

	/** {@code <style data-ebx-theme>:root{...}</style>} of the tokens of a theme, or "" when it sets none. */
	static String styleBlock(final PerspectiveTheme pTheme)
	{
		final Map<String, String> tokens = ThemeTokens.tokens(pTheme);
		if (tokens.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder("<style ").append(ATTRIBUTE).append(">:root{");
		for (final Map.Entry<String, String> token : tokens.entrySet())
		{
			out.append(token.getKey()).append(':').append(token.getValue()).append(';');
		}
		return out.append("}</style>").toString();
	}

	/** The tokens a theme sets, in a fixed order; empty for a null theme. */
	static Map<String, String> tokens(final PerspectiveTheme pTheme)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pTheme == null)
		{
			return out;
		}
		final String main = ThemeTokens.sanitize(ThemeTokens.read(pTheme, Getter.MAIN));
		if (main != null)
		{
			out.put(MAIN, main);
			out.put(MAIN_TEXT, ThemeTokens.readableText(main, ThemeTokens.read(pTheme, Getter.MAIN_TEXT)));
		}
		ThemeTokens.put(out, FOCUS, ThemeTokens.read(pTheme, Getter.FOCUS));
		final String header = ThemeTokens.sanitize(ThemeTokens.read(pTheme, Getter.HEADER));
		if (header != null)
		{
			out.put(HEADER, header);
			out.put(HEADER_TEXT, ThemeTokens.readableText(header, ThemeTokens.read(pTheme, Getter.HEADER_TEXT)));
		}
		ThemeTokens.put(out, SECOND_HEADER, ThemeTokens.read(pTheme, Getter.SECOND_HEADER));
		final String button = ThemeTokens.sanitize(ThemeTokens.read(pTheme, Getter.BUTTON));
		if (button != null)
		{
			out.put(BUTTON, button);
			out.put(BUTTON_TEXT, ThemeTokens.readableText(button, ThemeTokens.read(pTheme, Getter.BUTTON_TEXT)));
		}
		ThemeTokens.put(out, TABLE_HEADER, ThemeTokens.read(pTheme, Getter.TABLE_HEADER));
		ThemeTokens.put(out, TABLE_ODD, ThemeTokens.read(pTheme, Getter.TABLE_ODD));
		ThemeTokens.put(out, TABLE_EVEN, ThemeTokens.read(pTheme, Getter.TABLE_EVEN));
		final String border = ThemeTokens.sanitize(ThemeTokens.read(pTheme, Getter.TABLE_BORDER));
		ThemeTokens.put(out, TABLE_BORDER, border != null ? border : ThemeTokens.read(pTheme, Getter.TABLE_OUTER_BORDER));
		ThemeTokens.put(out, SELECTION, ThemeTokens.read(pTheme, Getter.SELECTION));
		if (main != null)
		{
			final double[] rgb = ThemeTokens.rgb(main);
			final double[] soft = ThemeTokens.mix(rgb, new double[] { 255, 255, 255 }, SOFT_SHARE);
			out.put(ACCENT_SOFT, ThemeTokens.hex(soft));
			out.put(ACCENT_LINE, ThemeTokens.hex(ThemeTokens.mix(rgb, new double[] { 255, 255, 255 }, LINE_SHARE)));
			out.put(ACCENT_INK, ThemeTokens.ink(rgb, soft));
		}
		return out;
	}

	private static void put(final Map<String, String> pOut, final String pToken, final String pRaw)
	{
		final String value = ThemeTokens.sanitize(pRaw);
		if (value != null)
		{
			pOut.put(pToken, value);
		}
	}

	/** The getters the tokens read; one call each, a failing getter reads as an empty colour. */
	enum Getter
	{
		MAIN, MAIN_TEXT, FOCUS, HEADER, HEADER_TEXT, SECOND_HEADER, BUTTON, BUTTON_TEXT, TABLE_HEADER, TABLE_ODD, TABLE_EVEN,
		TABLE_BORDER, TABLE_OUTER_BORDER, SELECTION
	}

	static String read(final PerspectiveTheme pTheme, final Getter pGetter)
	{
		try
		{
			switch (pGetter)
			{
			case MAIN: return pTheme.getMainColor();
			case MAIN_TEXT: return pTheme.getMainTextIconColor();
			case FOCUS: return pTheme.getFocusColor();
			case HEADER: return pTheme.getHeaderColor();
			case HEADER_TEXT: return pTheme.getHeaderTextColor();
			case SECOND_HEADER: return pTheme.getSecondHeaderColor();
			case BUTTON: return pTheme.getButtonsHighlightColor();
			case BUTTON_TEXT: return pTheme.getButtonsHighlightTextColor();
			case TABLE_HEADER: return pTheme.getTableHeaderBackgroundColor();
			case TABLE_ODD: return pTheme.getTableOddBackgroundColor();
			case TABLE_EVEN: return pTheme.getTableEvenBackgroundColor();
			case TABLE_BORDER: return pTheme.getTableBodyInnerHorizontalBordersColor();
			case TABLE_OUTER_BORDER: return pTheme.getTableBodyOuterBorderColor();
			case SELECTION: return pTheme.getPerspectiveNavigationPaneSelectionColor();
			default: return null;
			}
		}
		catch (final RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * A colour safe to write in a style block, normalised (lower case, {@code #rrggbb} kept, {@code #rgb} kept, a bare
	 * six-digit hex prefixed with {@code #}, {@code rgb()} without spaces), or null when the value is not a plain colour.
	 */
	static String sanitize(final String pRaw)
	{
		if (pRaw == null)
		{
			return null;
		}
		final String value = pRaw.trim().toLowerCase(Locale.ROOT);
		if (value.isEmpty() || value.length() > 40)
		{
			return null;
		}
		final Matcher hex = HEX.matcher(value);
		if (hex.matches())
		{
			// a bare value must be six digits: "add" or "bad" are words, not colours
			if (value.charAt(0) != '#' && value.length() != 6)
			{
				return null;
			}
			return "#" + hex.group(1);
		}
		final Matcher rgb = RGB.matcher(value);
		if (rgb.matches())
		{
			final int r = Integer.parseInt(rgb.group(1));
			final int g = Integer.parseInt(rgb.group(2));
			final int b = Integer.parseInt(rgb.group(3));
			if (r > 255 || g > 255 || b > 255)
			{
				return null;
			}
			return rgb.group(4) == null ? "rgb(" + r + "," + g + "," + b + ")" : "rgba(" + r + "," + g + "," + b + "," + rgb.group(4) + ")";
		}
		return NAMED.containsKey(value) ? value : null;
	}

	/**
	 * The text colour for a background: the preferred one (the theme's own) when it reaches {@link #MIN_CONTRAST}, else
	 * white, else near-black, else whichever of white and black contrasts more. Null background: null.
	 */
	static String readableText(final String pBackground, final String pPreferred)
	{
		final String background = ThemeTokens.sanitize(pBackground);
		if (background == null)
		{
			return null;
		}
		final String preferred = ThemeTokens.sanitize(pPreferred);
		if (preferred != null && ThemeTokens.contrast(preferred, background) >= MIN_CONTRAST)
		{
			return preferred;
		}
		if (ThemeTokens.contrast(WHITE, background) >= MIN_CONTRAST)
		{
			return WHITE;
		}
		if (ThemeTokens.contrast(NEAR_BLACK, background) >= MIN_CONTRAST)
		{
			return NEAR_BLACK;
		}
		return ThemeTokens.contrast(WHITE, background) >= ThemeTokens.contrast(BLACK, background) ? WHITE : BLACK;
	}

	/** WCAG contrast ratio of two sanitized colours (1 to 21); a translucent colour is laid over white first. */
	static double contrast(final String pA, final String pB)
	{
		final double a = ThemeTokens.luminance(ThemeTokens.rgb(pA));
		final double b = ThemeTokens.luminance(ThemeTokens.rgb(pB));
		return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
	}

	/** The main colour mixed with black in steps of 10 % until it reads at 4.5:1 on white and on the soft tint. */
	private static String ink(final double[] pMain, final double[] pSoft)
	{
		final String soft = ThemeTokens.hex(pSoft);
		for (int step = 0; step <= 10; step++)
		{
			final String candidate = ThemeTokens.hex(ThemeTokens.mix(new double[] { 0, 0, 0 }, pMain, step / 10.0));
			if (ThemeTokens.contrast(candidate, WHITE) >= MIN_CONTRAST && ThemeTokens.contrast(candidate, soft) >= MIN_CONTRAST)
			{
				return candidate;
			}
		}
		return BLACK;
	}

	/** {@code pShare} of {@code pA}, the rest of {@code pB}. */
	private static double[] mix(final double[] pA, final double[] pB, final double pShare)
	{
		return new double[] { pA[0] * pShare + pB[0] * (1 - pShare), pA[1] * pShare + pB[1] * (1 - pShare), pA[2] * pShare + pB[2] * (1 - pShare) };
	}

	private static String hex(final double[] pRgb)
	{
		return String.format(Locale.ROOT, "#%02x%02x%02x", Long.valueOf(Math.round(pRgb[0])), Long.valueOf(Math.round(pRgb[1])), Long.valueOf(Math.round(pRgb[2])));
	}

	/** Channels 0-255 of a sanitized colour; a translucent one laid over white. */
	static double[] rgb(final String pColour)
	{
		final String value = ThemeTokens.sanitize(pColour);
		if (value == null)
		{
			throw new IllegalArgumentException("Not a colour: " + pColour);
		}
		if (value.charAt(0) == '#')
		{
			String digits = value.substring(1);
			if (digits.length() == 3)
			{
				digits = "" + digits.charAt(0) + digits.charAt(0) + digits.charAt(1) + digits.charAt(1) + digits.charAt(2) + digits.charAt(2);
			}
			return new double[] { Integer.parseInt(digits.substring(0, 2), 16), Integer.parseInt(digits.substring(2, 4), 16), Integer.parseInt(digits.substring(4, 6), 16) };
		}
		final Matcher rgb = RGB.matcher(value);
		if (rgb.matches())
		{
			final double alpha = rgb.group(4) == null ? 1 : Double.parseDouble(rgb.group(4));
			final double[] channels = { Integer.parseInt(rgb.group(1)), Integer.parseInt(rgb.group(2)), Integer.parseInt(rgb.group(3)) };
			return ThemeTokens.mix(channels, new double[] { 255, 255, 255 }, alpha);
		}
		return ThemeTokens.rgb(NAMED.get(value));
	}

	private static double luminance(final double[] pRgb)
	{
		return 0.2126 * ThemeTokens.linear(pRgb[0]) + 0.7152 * ThemeTokens.linear(pRgb[1]) + 0.0722 * ThemeTokens.linear(pRgb[2]);
	}

	private static double linear(final double pChannel)
	{
		final double c = pChannel / 255.0;
		return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	/** The CSS named colours (CSS Color Module Level 4), with their value; "transparent" and system colours are not colours here. */
	private static final Map<String, String> NAMED = new HashMap<String, String>();
	static
	{
		final String[] pairs = ("aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc bisque ffe4c4 "
			+ "black 000000 blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a burlywood deb887 cadetblue 5f9ea0 "
			+ "chartreuse 7fff00 chocolate d2691e coral ff7f50 cornflowerblue 6495ed cornsilk fff8dc crimson dc143c cyan 00ffff "
			+ "darkblue 00008b darkcyan 008b8b darkgoldenrod b8860b darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9 darkkhaki bdb76b "
			+ "darkmagenta 8b008b darkolivegreen 556b2f darkorange ff8c00 darkorchid 9932cc darkred 8b0000 darksalmon e9967a "
			+ "darkseagreen 8fbc8f darkslateblue 483d8b darkslategray 2f4f4f darkslategrey 2f4f4f darkturquoise 00ced1 darkviolet 9400d3 "
			+ "deeppink ff1493 deepskyblue 00bfff dimgray 696969 dimgrey 696969 dodgerblue 1e90ff firebrick b22222 floralwhite fffaf0 "
			+ "forestgreen 228b22 fuchsia ff00ff gainsboro dcdcdc ghostwhite f8f8ff gold ffd700 goldenrod daa520 gray 808080 "
			+ "green 008000 greenyellow adff2f grey 808080 honeydew f0fff0 hotpink ff69b4 indianred cd5c5c indigo 4b0082 ivory fffff0 "
			+ "khaki f0e68c lavender e6e6fa lavenderblush fff0f5 lawngreen 7cfc00 lemonchiffon fffacd lightblue add8e6 lightcoral f08080 "
			+ "lightcyan e0ffff lightgoldenrodyellow fafad2 lightgray d3d3d3 lightgreen 90ee90 lightgrey d3d3d3 lightpink ffb6c1 "
			+ "lightsalmon ffa07a lightseagreen 20b2aa lightskyblue 87cefa lightslategray 778899 lightslategrey 778899 "
			+ "lightsteelblue b0c4de lightyellow ffffe0 lime 00ff00 limegreen 32cd32 linen faf0e6 magenta ff00ff maroon 800000 "
			+ "mediumaquamarine 66cdaa mediumblue 0000cd mediumorchid ba55d3 mediumpurple 9370db mediumseagreen 3cb371 "
			+ "mediumslateblue 7b68ee mediumspringgreen 00fa9a mediumturquoise 48d1cc mediumvioletred c71585 midnightblue 191970 "
			+ "mintcream f5fffa mistyrose ffe4e1 moccasin ffe4b5 navajowhite ffdead navy 000080 oldlace fdf5e6 olive 808000 "
			+ "olivedrab 6b8e23 orange ffa500 orangered ff4500 orchid da70d6 palegoldenrod eee8aa palegreen 98fb98 "
			+ "paleturquoise afeeee palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9 peru cd853f pink ffc0cb plum dda0dd "
			+ "powderblue b0e0e6 purple 800080 rebeccapurple 663399 red ff0000 rosybrown bc8f8f royalblue 4169e1 saddlebrown 8b4513 "
			+ "salmon fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee sienna a0522d silver c0c0c0 skyblue 87ceeb "
			+ "slateblue 6a5acd slategray 708090 slategrey 708090 snow fffafa springgreen 00ff7f steelblue 4682b4 tan d2b48c "
			+ "teal 008080 thistle d8bfd8 tomato ff6347 turquoise 40e0d0 violet ee82ee wheat f5deb3 white ffffff whitesmoke f5f5f5 "
			+ "yellow ffff00 yellowgreen 9acd32").split(" ");
		for (int i = 0; i + 1 < pairs.length; i += 2)
		{
			NAMED.put(pairs[i], "#" + pairs[i + 1]);
		}
	}
}
