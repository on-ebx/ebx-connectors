package com.on.ebx.connect.core;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.schema.Path;

/**
 * Registry of named transformations and the pipeline parser.
 * <p>
 * Pipeline syntax: steps separated by {@code |}, arguments in parentheses separated by commas,
 * e.g. {@code trim|left(255)}, {@code map(PME=MEDIUM;ETI=LARGE;GE=VERY_LARGE)},
 * {@code fk(/root/ReferenceData/LegalForm)}. Register your own with {@link #register(String, Transformation)}.
 */
public final class Transformations
{
	private static final Map<String, Transformation> REGISTRY = new LinkedHashMap<String, Transformation>();

	static
	{
		Transformations.registerBuiltins();
	}

	private Transformations()
	{
	}

	public static synchronized void register(final String pName, final Transformation pTransformation)
	{
		Transformations.REGISTRY.put(pName.trim().toLowerCase(Locale.ROOT), pTransformation);
	}

	public static synchronized List<String> names()
	{
		return new ArrayList<String>(Transformations.REGISTRY.keySet());
	}

	/**
	 * Applies a whole pipeline; an empty pipeline returns the value untouched.
	 * <p>
	 * Volume lot 5, task 4: splitting a pipeline string into steps and arguments
	 * ({@link #splitPipeline}/{@link #splitArgs}/{@link #unquote}) is pure text work, redone identically
	 * for the same rule on every row of a run. When {@code pContext} carries a
	 * {@link TransformationRunCache} (one per {@code importDocument}/{@code syncRecord} run), the parsed
	 * {@link TransformationPipeline} is cached per pipeline string instead of being re-parsed per row;
	 * with no cache (a caller outside the cached run paths, or a test), parsing happens as before.
	 */
	public static Object apply(final String pPipeline, final Object pValue, final TransformationContext pContext)
		throws Exception
	{
		if (pPipeline == null || pPipeline.trim().isEmpty())
		{
			return pValue;
		}
		final TransformationRunCache cache = pContext == null ? null : pContext.getCache();
		final TransformationPipeline pipeline = cache == null ? TransformationPipeline.parse(pPipeline) : cache.pipeline(pPipeline);
		Object current = pValue;
		for (final TransformationPipeline.Step step : pipeline.getSteps())
		{
			final Transformation transformation;
			synchronized (Transformations.class)
			{
				transformation = Transformations.REGISTRY.get(step.getName());
			}
			if (transformation == null)
			{
				throw new IllegalArgumentException("Unknown transformation '" + step.getName() + "'");
			}
			current = transformation.apply(current, step.getArgs(), pContext);
		}
		return current;
	}

	/**
	 * Whether the pipeline has a {@code constant(...)} step anywhere (not only as the first or only
	 * step), case-insensitively on the step name — the same tokenization {@link #apply} uses: split on
	 * {@code |} (respecting parenthesis depth), name = the text before the first {@code (}, trimmed and
	 * lower-cased. So {@code upper|constant('X')} and {@code Constant('X')} are both detected.
	 */
	static boolean hasConstantStep(final String pPipeline)
	{
		return Transformations.hasStep(pPipeline, "constant");
	}

	/** Whether the pipeline has a step named {@code pName} (lower case) anywhere, tokenized as {@link #hasConstantStep} describes. */
	static boolean hasStep(final String pPipeline, final String pName)
	{
		if (pPipeline == null || pPipeline.trim().isEmpty())
		{
			return false;
		}
		for (final String step : Transformations.splitPipeline(pPipeline))
		{
			final String trimmed = step.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			final int open = trimmed.indexOf('(');
			final String name = (open < 0 ? trimmed : trimmed.substring(0, open)).trim().toLowerCase(Locale.ROOT);
			if (pName.equals(name))
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ helpers

	static String frVat(final String pSiren)
	{
		final long key = (12L + 3L * (Long.parseLong(pSiren) % 97L)) % 97L;
		return "FR" + (key < 10 ? "0" : "") + key + pSiren;
	}

	public static String text(final Object pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		if (pValue instanceof BigDecimal)
		{
			return ((BigDecimal) pValue).stripTrailingZeros().toPlainString();
		}
		if (pValue instanceof List)
		{
			final StringBuilder sb = new StringBuilder();
			for (final Object item : (List<?>) pValue)
			{
				if (item == null)
				{
					continue;
				}
				if (sb.length() > 0)
				{
					sb.append(", ");
				}
				sb.append(Transformations.text(item));
			}
			return sb.toString();
		}
		return String.valueOf(pValue);
	}

	private static boolean isBlank(final Object pValue)
	{
		return pValue == null || (pValue instanceof String && ((String) pValue).trim().isEmpty())
				|| (pValue instanceof List && ((List<?>) pValue).isEmpty());
	}

	private static String arg(final List<String> pArgs, final int pIndex, final String pDefault)
	{
		return pArgs.size() > pIndex ? pArgs.get(pIndex) : pDefault;
	}

	private static int intArg(final List<String> pArgs, final int pIndex, final int pDefault)
	{
		try
		{
			return Integer.parseInt(Transformations.arg(pArgs, pIndex, String.valueOf(pDefault)).trim());
		}
		catch (final NumberFormatException ex)
		{
			return pDefault;
		}
	}

	// package-private (not private): TransformationPipeline.parse reuses this exact splitting so a
	// cached parse (TransformationRunCache) behaves identically to the old per-call parse it replaces.
	static List<String> splitPipeline(final String pPipeline)
	{
		final List<String> steps = new ArrayList<String>();
		int depth = 0;
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < pPipeline.length(); i++)
		{
			final char c = pPipeline.charAt(i);
			if (c == '(')
			{
				depth++;
			}
			else if (c == ')')
			{
				depth--;
			}
			if (c == '|' && depth == 0)
			{
				steps.add(current.toString());
				current = new StringBuilder();
			}
			else
			{
				current.append(c);
			}
		}
		steps.add(current.toString());
		return steps;
	}

	static List<String> splitArgs(final String pInner)
	{
		final List<String> args = new ArrayList<String>();
		boolean quoted = false;
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < pInner.length(); i++)
		{
			final char c = pInner.charAt(i);
			if (c == '\'')
			{
				quoted = !quoted;
				current.append(c);
			}
			else if (c == ',' && !quoted)
			{
				args.add(current.toString());
				current = new StringBuilder();
			}
			else
			{
				current.append(c);
			}
		}
		if (current.length() > 0 || !args.isEmpty())
		{
			args.add(current.toString());
		}
		return args;
	}

	static String unquote(final String pArg)
	{
		final String trimmed = pArg.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'"))
		{
			return trimmed.substring(1, trimmed.length() - 1);
		}
		return trimmed;
	}

	// ------------------------------------------------------------------ built-ins

	private static void registerBuiltins()
	{
		Transformations.register("trim", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				return s == null ? null : s.trim();
			}
		});
		Transformations.register("upper", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				return s == null ? null : s.toUpperCase(Locale.ROOT);
			}
		});
		Transformations.register("lower", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				return s == null ? null : s.toLowerCase(Locale.ROOT);
			}
		});
		Transformations.register("capitalize", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				if (s == null || s.isEmpty())
				{
					return s;
				}
				final StringBuilder sb = new StringBuilder();
				boolean start = true;
				for (final char ch : s.toLowerCase(Locale.ROOT).toCharArray())
				{
					sb.append(start ? Character.toUpperCase(ch) : ch);
					start = ch == ' ' || ch == '-' || ch == '\'';
				}
				return sb.toString();
			}
		});
		Transformations.register("left", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				final int n = Transformations.intArg(a, 0, 0);
				return s == null || s.length() <= n ? s : s.substring(0, n);
			}
		});
		Transformations.register("right", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				final int n = Transformations.intArg(a, 0, 0);
				return s == null || s.length() <= n ? s : s.substring(s.length() - n);
			}
		});
		Transformations.register("substring", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				if (s == null)
				{
					return null;
				}
				final int start = Math.min(Math.max(Transformations.intArg(a, 0, 0), 0), s.length());
				final int end = Math.min(Math.max(Transformations.intArg(a, 1, s.length()), start), s.length());
				return s.substring(start, end);
			}
		});
		Transformations.register("replace", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				return s == null ? null : s.replace(Transformations.arg(a, 0, ""), Transformations.arg(a, 1, ""));
			}
		});
		Transformations.register("regex", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				if (s == null)
				{
					return null;
				}
				final String regex = Transformations.arg(a, 0, ".*");
				final TransformationRunCache cache = c == null ? null : c.getCache();
				final Pattern pattern = cache == null ? Pattern.compile(regex) : cache.pattern(regex);
				final Matcher m = pattern.matcher(s);
				if (!m.find())
				{
					return null;
				}
				final int group = Transformations.intArg(a, 1, m.groupCount() > 0 ? 1 : 0);
				return m.group(group);
			}
		});
		// SIREN (9 digits) -> French VAT number FRkk + SIREN, key = (12 + 3 * (SIREN mod 97)) mod 97
		Transformations.register("vatfr", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				if (s == null)
				{
					return null;
				}
				final String siren = s.replaceAll("[^0-9]", "");
				if (siren.length() == 14)
				{
					return Transformations.frVat(siren.substring(0, 9));
				}
				return siren.length() == 9 ? Transformations.frVat(siren) : s;
			}
		});
		Transformations.register("digits", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				return s == null ? null : s.replaceAll("[^0-9]", "");
			}
		});
		Transformations.register("prefix", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				return s == null ? null : Transformations.arg(a, 0, "") + s;
			}
		});
		Transformations.register("suffix", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				return s == null ? null : s + Transformations.arg(a, 0, "");
			}
		});
		Transformations.register("default", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				return Transformations.isBlank(v) ? Transformations.arg(a, 0, null) : v;
			}
		});
		Transformations.register("constant", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				return Transformations.arg(a, 0, null);
			}
		});
		Transformations.register("nullifempty", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				return Transformations.isBlank(v) ? null : v;
			}
		});
		Transformations.register("nullif", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				return s != null && s.equals(Transformations.arg(a, 0, "")) ? null : v;
			}
		});
		Transformations.register("isodate", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				return s == null || s.length() < 10 ? null : s.substring(0, 10);
			}
		});
		Transformations.register("isodatetime", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				if (s == null || s.length() < 19)
				{
					return null;
				}
				return s.substring(0, 19).replace(' ', 'T');
			}
		});
		Transformations.register("date", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c) throws Exception
			{
				final String s = Transformations.text(v);
				if (s == null || s.trim().isEmpty())
				{
					return null;
				}
				final SimpleDateFormat in = new SimpleDateFormat(Transformations.arg(a, 0, "dd/MM/yyyy"), Locale.ROOT);
				in.setLenient(false);
				final Date parsed = in.parse(s.trim());
				return new SimpleDateFormat(Transformations.arg(a, 1, "yyyy-MM-dd"), Locale.ROOT).format(parsed);
			}
		});
		// epochdate([outPattern]): an OData v2 JSON date "/Date(1598227200000)/" (also "/Date(ms+hhmm)/", "/Date(ms-hhmm)/") or bare epoch
		// milliseconds, formatted in UTC with outPattern (default yyyy-MM-dd). The milliseconds of an OData v2 value are already UTC: a
		// trailing offset only says where the value was written and is IGNORED for the date part (Factory B2B test 2026-10-23, SAP
		// Business Partner CreationDate). Blank -> null; any other text -> IllegalArgumentException (the row is refused, like date()).
		Transformations.register("epochdate", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				if (s == null || s.trim().isEmpty())
				{
					return null;
				}
				final long millis = Transformations.epochMillis(s.trim());
				final SimpleDateFormat out = new SimpleDateFormat(Transformations.arg(a, 0, "yyyy-MM-dd"), Locale.ROOT);
				out.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
				return out.format(new Date(millis));
			}
		});
		Transformations.register("map", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				String fallback = null;
				boolean hasFallback = false;
				for (final String arg : a)
				{
					for (final String pair : arg.split(";"))
					{
						final int eq = pair.indexOf('=');
						if (eq < 0)
						{
							continue;
						}
						final String key = pair.substring(0, eq).trim();
						final String value = pair.substring(eq + 1).trim();
						if ("*".equals(key))
						{
							fallback = value;
							hasFallback = true;
						}
						else if (s != null && key.equalsIgnoreCase(s.trim()))
						{
							return value;
						}
					}
				}
				return hasFallback ? fallback : null;
			}
		});
		Transformations.register("join", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				if (!(v instanceof List))
				{
					return v;
				}
				// join(, ): an unquoted separator containing a comma arrives split; glue it back
				String separator = a.isEmpty() ? ", " : a.get(0);
				for (int i = 1; i < a.size(); i++)
				{
					separator = separator + "," + a.get(i);
				}
				final StringBuilder sb = new StringBuilder();
				for (final Object item : (List<?>) v)
				{
					final String s = Transformations.text(item);
					if (s == null || s.isEmpty())
					{
						continue;
					}
					if (sb.length() > 0)
					{
						sb.append(separator);
					}
					sb.append(s);
				}
				return sb.length() == 0 ? null : sb.toString();
			}
		});
		Transformations.register("first", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				if (v instanceof List)
				{
					final List<?> list = (List<?>) v;
					return list.isEmpty() ? null : list.get(0);
				}
				return v;
			}
		});
		Transformations.register("count", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				return v instanceof List ? Integer.valueOf(((List<?>) v).size()) : (v == null ? Integer.valueOf(0) : Integer.valueOf(1));
			}
		});
		Transformations.register("boolean", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				if (v == null)
				{
					return null;
				}
				if (v instanceof Boolean)
				{
					return v;
				}
				final String s = Transformations.text(v).trim().toLowerCase(Locale.ROOT);
				if (s.isEmpty())
				{
					return null;
				}
				return Boolean.valueOf("true".equals(s) || "1".equals(s) || "yes".equals(s) || "oui".equals(s)
						|| "y".equals(s) || "o".equals(s) || "active".equals(s));
			}
		});
		Transformations.register("number", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				if (v == null || v instanceof BigDecimal)
				{
					return v;
				}
				final String s = Transformations.text(v).trim().replace(',', '.').replaceAll("[^0-9.\\-]", "");
				return s.isEmpty() || "-".equals(s) || ".".equals(s) ? null : new BigDecimal(s);
			}
		});
		Transformations.register("round", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				if (v == null)
				{
					return null;
				}
				final BigDecimal d = v instanceof BigDecimal ? (BigDecimal) v : new BigDecimal(Transformations.text(v).trim());
				return d.setScale(Transformations.intArg(a, 0, 0), BigDecimal.ROUND_HALF_UP);
			}
		});
		// fk(/root/ReferenceData/LegalForm): keeps the value only when the referenced record exists
		// in the dataset being synchronised; avoids the blocking "unknown foreign key" on save.
		Transformations.register("fk", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				if (s == null || s.trim().isEmpty() || c == null || c.getDataset() == null)
				{
					return null;
				}
				if (c.getSession() == null)
				{
					throw new SecurityException("lookup: an authenticated EBX session is required");
				}
				final AdaptationTable table = Transformations.table(c, Transformations.arg(a, 0, ""), Transformations.arg(a, 1, null), Transformations.arg(a, 2, null));
				if (table == null)
				{
					throw new IllegalArgumentException("fk: table " + Transformations.arg(a, 0, "") + " not found (add the dataspace and dataset: fk(path,dataspace,dataset))");
				}
				final Adaptation target = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(s.trim()));
				return target == null ? null : s.trim();
			}
		});
		// lookup(/root/ReferenceData/Country, ./Alpha3Code, ./Alpha2Code): value -> key of the first record
		// whose field equals the value (a value mapping backed by a reference table).
		Transformations.register("lookup", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				if (s == null || s.trim().isEmpty() || c == null || c.getDataset() == null)
				{
					return null;
				}
				final AdaptationTable table = Transformations.table(c, Transformations.arg(a, 0, ""), Transformations.arg(a, 3, null), Transformations.arg(a, 4, null));
				if (table == null)
				{
					throw new IllegalArgumentException("lookup: table " + Transformations.arg(a, 0, "") + " not found (add the dataspace and dataset: lookup(path,field,result,dataspace,dataset))");
				}
				final String field = Transformations.arg(a, 1, "");
				final String result = Transformations.arg(a, 2, "");
				// Volume lot 5, task 4: lookup(...) runs one XPath Request per call; memoize the outcome
				// (including "not found") per (table, field, result, value) for the life of the run, bounded
				// so a run against a huge reference table cannot grow the memo without limit.
				final TransformationRunCache cache = c.getCache();
				final String cacheKey = cache == null ? null
						: Transformations.tableIdentity(table) + '\u0001' + field + '\u0001' + result + '\u0001' + s.trim();
				if (cache != null && cache.hasLookup(cacheKey))
				{
					return cache.lookup(cacheKey);
				}
				final com.onwbp.adaptation.Request request = table.createRequest();
				request.setSession(c.getSession());
				request.setXPathFilter(field + "='" + s.trim().replace("'", "''") + "'");
				final com.onwbp.adaptation.RequestResult rows = request.execute();
				final String value;
				try
				{
					final Adaptation hit = rows.nextAdaptation();
					if (hit == null)
					{
						value = null;
					}
					else
					{
						if (!result.isEmpty())
						{
							final com.orchestranetworks.schema.SchemaNode resultNode = hit.getSchemaNode().getNode(Path.parse(result));
							if (resultNode == null || c.getSession().getPermissions().getNodeAccessPermission(resultNode, hit).isHidden())
							{
								throw new SecurityException("lookup: result field is unavailable or restricted");
							}
						}
						value = result.isEmpty() ? hit.getOccurrencePrimaryKey().format()
								: hit.getSchemaNode().getNode(Path.parse(result)).formatToXsString(hit.get(Path.parse(result)));
					}
				}
				finally
				{
					rows.close();
				}
				if (cache != null)
				{
					cache.putLookup(cacheKey, value);
				}
				return value;
			}
		});
		// golden(/root/Table[,dataspace,dataset]): the value is the formatted primary key of a record of
		// that table; resolves to its golden record's formatted key when the record is MERGED under Match
		// and Merge (ebx-addon-mame). Blank -> null; record not found, table unmanaged, or state not
		// MERGED -> the value unchanged (GoldenRecords#golden already returns the same record in every one
		// of those cases). Table not found -> IllegalArgumentException, worded like lookup's.
		Transformations.register("golden", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				if (s == null || s.trim().isEmpty() || c == null || c.getDataset() == null)
				{
					return null;
				}
				final AdaptationTable table = Transformations.table(c, Transformations.arg(a, 0, ""), Transformations.arg(a, 1, null), Transformations.arg(a, 2, null));
				if (table == null)
				{
					throw new IllegalArgumentException("golden: table " + Transformations.arg(a, 0, "") + " not found (add the dataspace and dataset: golden(path,dataspace,dataset))");
				}
				final String key = s.trim();
				final Adaptation record = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(key));
				if (record == null)
				{
					return key;
				}
				final Adaptation golden = c.getGoldenRecords().golden(record);
				return golden == null ? key : golden.getOccurrencePrimaryKey().format();
			}
		});
		// mdmState(/root/Table[,dataspace,dataset]): the Match and Merge state of the record whose primary
		// key is the value ("UNMANAGED" when the table is not under Match and Merge); null when blank or
		// the record is not found. Table not found -> IllegalArgumentException, worded like lookup's.
		Transformations.register("mdmstate", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				final String s = Transformations.text(v);
				if (s == null || s.trim().isEmpty() || c == null || c.getDataset() == null)
				{
					return null;
				}
				final AdaptationTable table = Transformations.table(c, Transformations.arg(a, 0, ""), Transformations.arg(a, 1, null), Transformations.arg(a, 2, null));
				if (table == null)
				{
					throw new IllegalArgumentException("mdmState: table " + Transformations.arg(a, 0, "") + " not found (add the dataspace and dataset: mdmState(path,dataspace,dataset))");
				}
				final Adaptation record = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(s.trim()));
				return record == null ? null : c.getGoldenRecords().state(record);
			}
		});
		// hash: SHA-256 hex digest of the (text-coerced) value; ignores the pipeline arguments.
		Transformations.register("hash", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				return Hashing.sha256(Transformations.text(v));
			}
		});
		// now([pattern]): current server time, ignoring the incoming value; default pattern is the
		// same 19-character shape isodatetime/LandingFlow.RECEIVED_AT_FORMAT already produce.
		Transformations.register("now", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				return new SimpleDateFormat(Transformations.arg(a, 0, "yyyy-MM-dd'T'HH:mm:ss"), Locale.ROOT).format(new Date());
			}
		});
		// runid: the id of the current run (a fresh one when no run was begun), held by RunIds so this
		// class never references ConnectorService — see RunIds' class comment and docs/TARGET-MAPPING.md.
		Transformations.register("runid", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				return RunIds.current();
			}
		});
		// concat(path1,path2,...): joins the value at each dotted path of the document root (the
		// same resolution a rule's own sourcePath uses, Json.at) with a fixed '|' separator; a
		// missing/null piece contributes "". Mirrors LandingFlow.concatCoalesce's SQL-side
		// COALESCE(expr,'') || '|' || COALESCE(expr,'') shape, so a feed-based (non-SQL) landing
		// mapping can build the same _sourceId/_rowHash as a SQL-based one.
		Transformations.register("concat", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				if (a.isEmpty())
				{
					throw new IllegalArgumentException("concat: at least one path argument is required, e.g. concat(id,name)");
				}
				if (c == null || c.getDocumentRoot() == null)
				{
					return null;
				}
				final StringBuilder sb = new StringBuilder();
				for (int i = 0; i < a.size(); i++)
				{
					if (i > 0)
					{
						sb.append('|');
					}
					final com.fasterxml.jackson.databind.JsonNode node = Json.at(c.getDocumentRoot(), a.get(i));
					final String piece = Transformations.text(node == null ? null : Json.toJava(node));
					sb.append(piece == null ? "" : piece);
				}
				return sb.toString();
			}
		});
		// joinwidth(width,path1,path2,...): rebuilds ONE value that its source cut into fixed-width pieces (SAP OrganizationBPName1 /
		// OrganizationBPName2, 40 characters each; a street on two lines). The pieces are read like concat's (dotted paths of the document
		// root); a piece that fills the width was cut inside a word and is followed DIRECTLY by the next one, a shorter piece was cut at a
		// space (the source dropped it) and is followed by one space. Blank pieces are skipped; nothing left -> null. Factory B2B test
		// 2026-10-23: "Compagnie Generale de Chimie Druarimar E" (40) + "urope SAS" and "...Morelidov" (39) + "Europe SASU".
		Transformations.register("joinwidth", new Transformation()
		{
			@Override
			public Object apply(final Object v, final List<String> a, final TransformationContext c)
			{
				if (a.size() < 2)
				{
					throw new IllegalArgumentException("joinwidth: a width and at least one path are required, e.g. joinwidth(40,name1,name2)");
				}
				final int width;
				try
				{
					width = Integer.parseInt(a.get(0).trim());
				}
				catch (final NumberFormatException ex)
				{
					throw new IllegalArgumentException("joinwidth: the first argument must be the width of a piece, e.g. joinwidth(40,name1,name2)");
				}
				if (c == null || c.getDocumentRoot() == null)
				{
					return null;
				}
				final StringBuilder sb = new StringBuilder();
				int previousLength = -1;
				for (int i = 1; i < a.size(); i++)
				{
					final com.fasterxml.jackson.databind.JsonNode node = Json.at(c.getDocumentRoot(), a.get(i));
					final String piece = Transformations.text(node == null ? null : Json.toJava(node));
					if (piece == null || piece.trim().isEmpty())
					{
						continue;
					}
					if (previousLength >= 0 && previousLength < width)
					{
						sb.append(' ');
					}
					sb.append(previousLength < 0 ? piece : piece.trim());
					previousLength = piece.length();
				}
				return sb.length() == 0 ? null : sb.toString();
			}
		});
	}

	private static final Pattern ODATA_DATE = Pattern.compile("^/Date\\((-?\\d+)(?:[+-]\\d{4})?\\)/$");
	private static final Pattern EPOCH_MILLIS = Pattern.compile("^-?\\d+$");

	/** The epoch milliseconds of {@code /Date(ms)/}, {@code /Date(ms+hhmm)/} or a bare number; IllegalArgumentException otherwise. */
	static long epochMillis(final String pText)
	{
		final Matcher odata = Transformations.ODATA_DATE.matcher(pText);
		final String digits;
		if (odata.matches())
		{
			digits = odata.group(1);
		}
		else if (Transformations.EPOCH_MILLIS.matcher(pText).matches())
		{
			digits = pText;
		}
		else
		{
			throw new IllegalArgumentException("epochdate: the value is neither an OData /Date(ms)/ date nor epoch milliseconds");
		}
		try
		{
			return Long.parseLong(digits);
		}
		catch (final NumberFormatException ex)
		{
			throw new IllegalArgumentException("epochdate: milliseconds out of range");
		}
	}

	/**
	 * The table of {@code fk} / {@code lookup}: {@code pPath} in the dataset being synchronised; when
	 * the dataset has no such table, the foreign key of the model that points to it is followed
	 * (its container dataspace and dataset, e.g. Currencies for /root/Currency); explicit
	 * {@code pDataspace} / {@code pDataset} arguments win over both.
	 * <p>
	 * Volume lot 5, task 4: resolution (a dataspace-tree search when a dataset but no dataspace is
	 * given - {@code DatasetLocator.search}, a foreign-key walk otherwise) is redone identically for
	 * the same arguments on every row of a run. When {@code pContext} carries a
	 * {@link TransformationRunCache}, the resolved table (including a "not found" result) is cached
	 * per {@code (path, dataspace, dataset)} instead of being re-resolved per row.
	 */
	static AdaptationTable table(final TransformationContext pContext, final String pPath, final String pDataspace, final String pDataset)
	{
		final TransformationRunCache cache = pContext.getCache();
		if (cache == null)
		{
			return Transformations.resolveTable(pContext, pPath, pDataspace, pDataset);
		}
		final String key = pPath.trim() + '\u0001' + (pDataspace == null ? "" : pDataspace.trim()) + '\u0001' + (pDataset == null ? "" : pDataset.trim());
		if (cache.hasTable(key))
		{
			return cache.table(key);
		}
		final AdaptationTable resolved = Transformations.resolveTable(pContext, pPath, pDataspace, pDataset);
		cache.putTable(key, resolved);
		return resolved;
	}

	/** A stable identity for a resolved table, for the {@code lookup(...)} memo key: dataspace, dataset, table path. */
	private static String tableIdentity(final AdaptationTable pTable)
	{
		final Adaptation container = pTable.getContainerAdaptation();
		return container.getHome().getKey().getName() + '\u0001' + container.getAdaptationName().getStringName()
				+ '\u0001' + pTable.getTablePath().format();
	}

	private static AdaptationTable resolveTable(final TransformationContext pContext, final String pPath, final String pDataspace, final String pDataset)
	{
		final Adaptation current = pContext.getDataset();
		final Path path = Path.parse(pPath.trim());
		if (pDataset != null && !pDataset.trim().isEmpty())
		{
			final Adaptation found = com.on.ebx.connect.ebx.DatasetLocator.find(current.getHome().getRepository(),
				pDataspace == null || pDataspace.trim().isEmpty() ? null : pDataspace.trim(), pDataset.trim(), pPath.trim());
			return found == null ? null : found.getTable(path);
		}
		if (current.getSchemaNode().getNode(path) != null)
		{
			return current.getTable(path);
		}
		final com.orchestranetworks.schema.info.SchemaFacetTableRef ref = Transformations.tableRefTo(current.getSchemaNode(), path, 0);
		if (ref == null)
		{
			return null;
		}
		com.onwbp.adaptation.AdaptationHome home = current.getHome();
		if (ref.getContainerHome() != null)
		{
			home = home.getRepository().lookupHome(ref.getContainerHome());
		}
		Adaptation container = current;
		if (home != null && ref.getContainerReference() != null)
		{
			container = home.findAdaptationOrNull(ref.getContainerReference());
		}
		else if (home != null && home != current.getHome())
		{
			container = home.findAdaptationOrNull(current.getAdaptationName());
		}
		return container == null || container.getSchemaNode().getNode(path) == null ? null : container.getTable(path);
	}

	/** First foreign key of the model whose target table is {@code pPath}. */
	private static com.orchestranetworks.schema.info.SchemaFacetTableRef tableRefTo(final com.orchestranetworks.schema.SchemaNode pNode, final Path pPath, final int pDepth)
	{
		if (pNode == null || pDepth > 12)
		{
			return null;
		}
		final com.orchestranetworks.schema.info.SchemaFacetTableRef ref = pNode.getFacetOnTableReference();
		if (ref != null && ref.getTablePath() != null && ref.getTablePath().format().equals(pPath.format()))
		{
			return ref;
		}
		// a table node lists its fields under its occurrence root, not as direct children
		final com.orchestranetworks.schema.SchemaNode scope = pNode.isTableNode() ? pNode.getTableOccurrenceRootNode() : pNode;
		for (final com.orchestranetworks.schema.SchemaNode child : scope.getNodeChildren())
		{
			final com.orchestranetworks.schema.info.SchemaFacetTableRef found = Transformations.tableRefTo(child, pPath, pDepth + 1);
			if (found != null)
			{
				return found;
			}
		}
		return null;
	}
}
