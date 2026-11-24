package com.on.ebx.connect.ebx.ui;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import com.orchestranetworks.ui.perspective.Perspective;
import com.orchestranetworks.ui.perspective.PerspectiveTheme;

/** The perspective colours as CSS tokens (audit C-40): what passes the sanitizer, readable text, nothing when there is no theme. */
public final class ThemeTokensTest
{
	public static void main(final String[] pArgs)
	{
		// sanitizer: plain colours pass, normalised; anything that could leave the declaration is dropped
		check("#1a73e8".equals(ThemeTokens.sanitize(" #1A73E8 ")), "a hex colour is kept, lower case");
		check("#abc".equals(ThemeTokens.sanitize("#ABC")), "a short hex colour is kept");
		check("#1a73e8".equals(ThemeTokens.sanitize("1a73e8")), "a bare six-digit hex gets its #");
		check(ThemeTokens.sanitize("add") == null && ThemeTokens.sanitize("bad") == null, "a bare three-letter word is not a colour");
		check("rgb(10,20,30)".equals(ThemeTokens.sanitize("rgb( 10 , 20 , 30 )")), "rgb() is kept without spaces");
		check("rgba(10,20,30,0.5)".equals(ThemeTokens.sanitize("rgba(10,20,30,0.5)")), "rgba() is kept");
		check(ThemeTokens.sanitize("rgb(300,0,0)") == null, "a channel above 255 is dropped");
		check("rebeccapurple".equals(ThemeTokens.sanitize("RebeccaPurple")), "a named colour is kept");
		check(ThemeTokens.sanitize("notacolour") == null && ThemeTokens.sanitize("transparent") == null, "an unknown name is dropped");
		check(ThemeTokens.sanitize("red;}body{display:none") == null, "a value that closes the declaration is dropped");
		check(ThemeTokens.sanitize("#fff</style><script>alert(1)</script>") == null, "a value that closes the style block is dropped");
		check(ThemeTokens.sanitize("url(javascript:alert(1))") == null && ThemeTokens.sanitize("expression(alert(1))") == null, "no url() or expression()");
		check(ThemeTokens.sanitize("var(--x)") == null && ThemeTokens.sanitize("#12345") == null && ThemeTokens.sanitize("") == null
			&& ThemeTokens.sanitize(null) == null, "var(), five digits, empty and null are dropped");

		// contrast and the text colour of a background
		check(Math.abs(ThemeTokens.contrast("#000000", "#ffffff") - 21.0) < 0.01, "black on white is 21:1");
		check(Math.abs(ThemeTokens.contrast("white", "#fff") - 1.0) < 0.001, "a named colour and its hex are the same");
		check(ThemeTokens.WHITE.equals(ThemeTokens.readableText("#1a1a5e", null)), "white on a dark background");
		check(ThemeTokens.NEAR_BLACK.equals(ThemeTokens.readableText("#ffe14d", null)), "near-black on a light background");
		check(ThemeTokens.NEAR_BLACK.equals(ThemeTokens.readableText("#ffe14d", "#ffffff")), "the theme's own text colour is refused when unreadable");
		check("#ffeecc".equals(ThemeTokens.readableText("#202020", "#ffeecc")), "the theme's own text colour is kept when readable");
		for (final String background : new String[] { "#777777", "#808080", "#767676", "#1a73e8", "#7137d8", "#ff0000", "#00ff00", "yellow", "rgb(120,120,120)" })
		{
			final String text = ThemeTokens.readableText(background, null);
			check(ThemeTokens.contrast(text, background) >= ThemeTokens.MIN_CONTRAST, "readable text on " + background + " (got " + text + ")");
		}
		check(ThemeTokens.readableText("javascript:", "#fff") == null, "no text colour for a background that is not a colour");

		// no perspective, no theme, an empty theme: nothing is written, the CSS fallbacks apply
		check("".equals(ThemeTokens.styleBlock((Perspective) null)), "no perspective, no block");
		check("".equals(ThemeTokens.styleBlock(perspective(null))), "a perspective without theme, no block");
		check("".equals(ThemeTokens.styleBlock(theme(new HashMap<String, String>()))), "a theme without colours, no block");
		final Map<String, String> broken = new HashMap<String, String>();
		broken.put("getMainColor", "red;}body{display:none");
		broken.put("getFocusColor", "");
		check("".equals(ThemeTokens.styleBlock(theme(broken))), "unsafe or empty colours write nothing");
		check("".equals(ThemeTokens.styleBlock(failing())), "a theme whose getters throw writes nothing");

		// a dark theme: every token set, every text colour readable on its background
		final Map<String, String> dark = new HashMap<String, String>();
		dark.put("getMainColor", "#0b2545");
		dark.put("getMainTextIconColor", "#13315c");
		dark.put("getFocusColor", "#ff9f1c");
		dark.put("getHeaderColor", "#111111");
		dark.put("getHeaderTextColor", "#ffffff");
		dark.put("getSecondHeaderColor", "#222222");
		dark.put("getButtonsHighlightColor", "#8d0801");
		dark.put("getButtonsHighlightTextColor", "#000000");
		dark.put("getTableHeaderBackgroundColor", "#e0e0e0");
		dark.put("getTableOddBackgroundColor", "#ffffff");
		dark.put("getTableEvenBackgroundColor", "#f4f4f4");
		dark.put("getTableBodyInnerHorizontalBordersColor", "#cccccc");
		dark.put("getPerspectiveNavigationPaneSelectionColor", "#13315c");
		final Map<String, String> tokens = ThemeTokens.tokens(theme(dark));
		check("#0b2545".equals(tokens.get("--ebx-main")) && "#ff9f1c".equals(tokens.get("--ebx-focus")) && "#111111".equals(tokens.get("--ebx-header"))
			&& "#222222".equals(tokens.get("--ebx-second-header")) && "#8d0801".equals(tokens.get("--ebx-button"))
			&& "#e0e0e0".equals(tokens.get("--ebx-table-header")) && "#ffffff".equals(tokens.get("--ebx-table-odd"))
			&& "#f4f4f4".equals(tokens.get("--ebx-table-even")) && "#cccccc".equals(tokens.get("--ebx-table-border"))
			&& "#13315c".equals(tokens.get("--ebx-selection")), "each token reads its getter");
		check(ThemeTokens.WHITE.equals(tokens.get("--ebx-main-text")), "the theme's dark text on a dark main colour is replaced by white");
		check("#ffffff".equals(tokens.get("--ebx-header-text")), "the theme's readable header text is kept");
		check(ThemeTokens.WHITE.equals(tokens.get("--ebx-button-text")), "black on a dark red button is replaced by white");
		for (final String[] pair : new String[][] { { "--ebx-main", "--ebx-main-text" }, { "--ebx-header", "--ebx-header-text" }, { "--ebx-button", "--ebx-button-text" } })
		{
			check(ThemeTokens.contrast(tokens.get(pair[1]), tokens.get(pair[0])) >= ThemeTokens.MIN_CONTRAST, pair[1] + " is readable");
		}
		check(ThemeTokens.contrast(tokens.get("--ftc-accent-ink"), "#ffffff") >= ThemeTokens.MIN_CONTRAST
			&& ThemeTokens.contrast(tokens.get("--ftc-accent-ink"), tokens.get("--ftc-accent-soft")) >= ThemeTokens.MIN_CONTRAST, "the accent ink reads on white and on the soft tint");
		final String block = ThemeTokens.styleBlock(perspective(theme(dark)));
		check(block.startsWith("<style data-ebx-theme>:root{--ebx-main:#0b2545;") && block.endsWith("}</style>"), "the block is one :root rule: " + block);

		// a light theme: the accent ink is darkened until readable, the tints stay light
		final Map<String, String> light = new HashMap<String, String>();
		light.put("getMainColor", "#ffd23f");
		final Map<String, String> pale = ThemeTokens.tokens(theme(light));
		check(ThemeTokens.NEAR_BLACK.equals(pale.get("--ebx-main-text")), "dark text on a yellow main colour");
		check(ThemeTokens.contrast(pale.get("--ftc-accent-ink"), "#ffffff") >= ThemeTokens.MIN_CONTRAST, "a yellow accent is darkened for text: " + pale.get("--ftc-accent-ink"));
		check(ThemeTokens.contrast(pale.get("--ftc-accent-soft"), "#ffffff") < 1.2, "the soft tint stays close to white");
		check(!pale.containsKey("--ebx-focus") && !pale.containsKey("--ebx-header-text") && !pale.containsKey("--ebx-table-border"), "colours the theme leaves empty are not written");

		// the table border falls back on the outer border colour
		final Map<String, String> outer = new HashMap<String, String>();
		outer.put("getTableBodyOuterBorderColor", "#bbbbbb");
		check("#bbbbbb".equals(ThemeTokens.tokens(theme(outer)).get("--ebx-table-border")), "the outer border feeds the table border when the inner one is empty");

		System.out.println("ThemeTokensTest: OK");
	}

	private static PerspectiveTheme theme(final Map<String, String> pColours)
	{
		final InvocationHandler handler = (proxy, method, args) -> method.getReturnType() == String.class ? pColours.get(method.getName()) : null;
		return (PerspectiveTheme) Proxy.newProxyInstance(ThemeTokensTest.class.getClassLoader(), new Class<?>[] { PerspectiveTheme.class }, handler);
	}

	private static PerspectiveTheme failing()
	{
		final InvocationHandler handler = (proxy, method, args) -> { throw new IllegalStateException("theme not available"); };
		return (PerspectiveTheme) Proxy.newProxyInstance(ThemeTokensTest.class.getClassLoader(), new Class<?>[] { PerspectiveTheme.class }, handler);
	}

	private static Perspective perspective(final PerspectiveTheme pTheme)
	{
		final InvocationHandler handler = (proxy, method, args) -> "getTheme".equals(method.getName()) ? pTheme : null;
		return (Perspective) Proxy.newProxyInstance(ThemeTokensTest.class.getClassLoader(), new Class<?>[] { Perspective.class }, handler);
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
