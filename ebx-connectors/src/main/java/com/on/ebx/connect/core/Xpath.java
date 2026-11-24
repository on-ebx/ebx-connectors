package com.on.ebx.connect.core;

/** EBX XPath string literals and literal-template content. */
public final class Xpath
{
	private Xpath() { }
	public static String escape(final String value) { return value == null ? "" : value.replace("'", "''"); }
	public static String quote(final String value) { return "'" + escape(value) + "'"; }
}
