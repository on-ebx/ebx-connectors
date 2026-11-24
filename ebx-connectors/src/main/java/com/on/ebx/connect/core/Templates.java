package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code {name}} placeholders of the property-driven connectors (rest, soap, graphql). */
public final class Templates
{
	private Templates()
	{
	}

	public interface Escaper
	{
		String escape(String pValue);
	}

	public static final Escaper NONE = new Escaper()
	{
		@Override
		public String escape(final String pValue)
		{
			return pValue;
		}
	};

	public static final Escaper XML = new Escaper()
	{
		@Override
		public String escape(final String pValue)
		{
			return pValue.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
		}
	};

	public static final Escaper JSON = new Escaper()
	{
		@Override
		public String escape(final String pValue)
		{
			final String s = Json.write(Json.mapper().getNodeFactory().textNode(pValue));
			return s.substring(1, s.length() - 1);
		}
	};

	public static List<String> placeholders(final String pTemplate)
	{
		final List<String> names = new ArrayList<String>();
		int from = 0;
		while (pTemplate != null)
		{
			final int open = pTemplate.indexOf('{', from);
			final int close = open < 0 ? -1 : pTemplate.indexOf('}', open);
			if (open < 0 || close < 0)
			{
				break;
			}
			final String name = pTemplate.substring(open + 1, close).trim();
			if (!name.isEmpty() && name.matches("[A-Za-z0-9_.-]+") && !names.contains(name))
			{
				names.add(name);
			}
			from = close + 1;
		}
		return names;
	}

	public static String fill(final String pTemplate, final Map<String, String> pInputs, final Escaper pEscaper)
	{
		String out = pTemplate == null ? "" : pTemplate;
		for (final String name : Templates.placeholders(pTemplate))
		{
			final String value = pInputs == null || pInputs.get(name) == null ? "" : pInputs.get(name);
			out = out.replace("{" + name + "}", pEscaper.escape(value));
		}
		return out;
	}

	/** {@code op.<name>.required}: explicit list, "-" for none, default every placeholder of the template(s). */
	public static String[] required(final String pDeclared, final String... pTemplates)
	{
		final List<String> required = new ArrayList<String>();
		if (pDeclared == null)
		{
			for (final String t : pTemplates)
			{
				for (final String p : Templates.placeholders(t))
				{
					if (!required.contains(p))
					{
						required.add(p);
					}
				}
			}
		}
		else if (!"-".equals(pDeclared.trim()))
		{
			for (final String r : pDeclared.split(","))
			{
				if (!r.trim().isEmpty())
				{
					required.add(r.trim());
				}
			}
		}
		return required.toArray(new String[required.size()]);
	}
}
