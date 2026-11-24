package com.on.ebx.connect.core;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;

/**
 * Expressions of the service inputs (and of any template):
 * <ul>
 * <li>{@code ${./Identification/LegalEntityIdentifier}} is a record field, {@code ${param.name}} a
 * parameter; unknown placeholders resolve to "";</li>
 * <li>a placeholder may carry its own pipeline, {@code ${./Address/City|upper|left(40)}}, run
 * with the same transformations as the mapping rules;</li>
 * <li>the whole expression may end with {@code |> pipeline}, applied to the assembled text:
 * {@code ${./Address/Street} ${./Address/Zipcode} |> trim|upper}.</li>
 * </ul>
 */
public final class Expressions
{
	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");
	private static final String WHOLE_PIPELINE = "|>";

	private Expressions()
	{
	}

	public static String resolve(final String pTemplate, final Adaptation pRecord, final Map<String, String> pParams)
	{
		return Expressions.resolve(pTemplate, pRecord, pParams,
			pRecord == null ? null : new TransformationContext(pRecord.getContainer(), pRecord, null));
	}

	/** Index of the '}' closing a placeholder whose content starts at {@code pFrom}, honouring nested ${...} and quotes. */
	private static int matchingBrace(final String pText, final int pFrom)
	{
		int depth = 0;
		char quote = 0;
		for (int i = pFrom; i < pText.length(); i++)
		{
			final char c = pText.charAt(i);
			if (quote != 0)
			{
				if (c == quote)
				{
					quote = 0;
				}
				continue;
			}
			if (c == '\'' || c == '"')
			{
				quote = c;
			}
			else if (c == '$' && i + 1 < pText.length() && pText.charAt(i + 1) == '{')
			{
				depth++;
				i++;
			}
			else if (c == '}')
			{
				if (depth == 0)
				{
					return i;
				}
				depth--;
			}
		}
		return -1;
	}

	public static String resolve(final String pTemplate, final Adaptation pRecord, final Map<String, String> pParams,
		final TransformationContext pContext)
	{
		if (pTemplate == null)
		{
			return null;
		}
		String template = pTemplate;
		String wholePipeline = null;
		final int arrow = template.lastIndexOf(Expressions.WHOLE_PIPELINE);
		if (arrow >= 0)
		{
			wholePipeline = template.substring(arrow + Expressions.WHOLE_PIPELINE.length()).trim();
			template = template.substring(0, arrow);
		}
		final StringBuilder out = new StringBuilder();
		int from = 0;
		while (true)
		{
			final int open = template.indexOf("${", from);
			if (open < 0)
			{
				out.append(template.substring(from));
				break;
			}
			final int close = Expressions.matchingBrace(template, open + 2);
			if (close < 0)
			{
				out.append(template.substring(from));
				break;
			}
			out.append(template, from, open);
			// nested placeholders are resolved first: default(${./other/field}) works
			final String inner = Expressions.resolve(template.substring(open + 2, close), pRecord, pParams, pContext).trim();
			final int pipe = Expressions.firstPipe(inner);
			final String key = (pipe < 0 ? inner : inner.substring(0, pipe)).trim();
			final String pipeline = pipe < 0 ? null : inner.substring(pipe + 1).trim();
			String value = "";
			if (key.startsWith("param."))
			{
				final String v = pParams == null ? null : pParams.get(key.substring(6));
				value = v == null ? "" : v;
			}
			else if (pRecord != null)
			{
				value = Expressions.recordValue(pRecord, key);
			}
			out.append(Expressions.transform(value, pipeline, pContext));
			from = close + 1;
		}
		final String assembled = wholePipeline == null ? out.toString() : out.toString().trim();
		return Expressions.transform(assembled, wholePipeline, pContext);
	}

	private static String transform(final String pValue, final String pPipeline, final TransformationContext pContext)
	{
		if (pPipeline == null || pPipeline.isEmpty())
		{
			return pValue;
		}
		try
		{
			final Object result = Transformations.apply(pPipeline, pValue, pContext);
			final String text = Transformations.text(result);
			return text == null ? "" : text;
		}
		catch (final Exception ex)
		{
			throw new IllegalArgumentException("Expression pipeline '" + pPipeline + "' failed: " + ex.getMessage(), ex);
		}
	}

	/** Position of the first {@code |} outside parentheses and quotes, -1 when none. */
	private static int firstPipe(final String pText)
	{
		int depth = 0;
		char quote = 0;
		for (int i = 0; i < pText.length(); i++)
		{
			final char c = pText.charAt(i);
			if (quote != 0)
			{
				if (c == quote)
				{
					quote = 0;
				}
				continue;
			}
			if (c == '\'' || c == '"')
			{
				quote = c;
			}
			else if (c == '(')
			{
				depth++;
			}
			else if (c == ')')
			{
				depth--;
			}
			else if (c == '|' && depth == 0)
			{
				return i;
			}
		}
		return -1;
	}

	/** Value of a record field as its XML-schema string, "" when null or unreadable. */
	public static String recordValue(final Adaptation pRecord, final String pPath)
	{
		try
		{
			final Path path = Path.parse(pPath.startsWith("./") || pPath.startsWith("/") ? pPath : "./" + pPath);
			final SchemaNode node = pRecord.getSchemaNode().getNode(path);
			if (node == null)
			{
				return "";
			}
			final Object value = pRecord.get(path);
			if (value == null)
			{
				return "";
			}
			return node.isTerminalValue() ? node.formatToXsString(value) : String.valueOf(value);
		}
		catch (final RuntimeException ex)
		{
			return "";
		}
	}

	public static boolean isBlank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}
}
