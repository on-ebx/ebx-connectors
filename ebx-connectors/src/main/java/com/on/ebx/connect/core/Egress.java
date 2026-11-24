package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.connect.spi.ConnectorConfig;
import com.orchestranetworks.service.LoggingCategory;

/**
 * Outbound allow-list: the system property {@code ebx.connectors.allowedHosts} lists the hosts the
 * module may talk to ({@code api.insee.fr, *.api.gouv.fr, kafka.internal:9082, localhost}); empty or
 * absent = no restriction, {@code none} = nothing. Set by the infrastructure at JVM level, it cannot
 * be changed from the EBX user interface, which is the point: a connector whose base URL (or token
 * endpoint, vault address, discovered instance URL) points elsewhere is refused. Every HTTP call of
 * the module goes through {@link HttpJsonClient} and is checked, redirect hops included; base URLs
 * of the other types (JDBC, SFTP, Kafka bootstrap servers) are checked when the connector is loaded.
 * <p>
 * Whatever the list says, a host that is, or resolves to, a link-local or cloud metadata address
 * (169.254.0.0/16, fe80::/10, fd00:ec2::254, {@code metadata.google.internal}...) is refused unless
 * an exact entry of the list names it: a wildcard must never hand the instance credentials of the
 * machine to whoever can edit a base URL. The names are resolved here, so a DNS name pointing at
 * such an address is refused too. The check and the connection are two lookups: both are served by
 * the JVM's address cache, but an answer that changes between them is not covered. A call that
 * leaves through the JVM's HTTP proxy is judged on its literal host only: the proxy resolves it.
 * <p>
 * Private hosts are refused BY DEFAULT (audit decision 1, 2026-10-06): a host that is, or resolves to,
 * a loopback, private (RFC 1918, unique-local, carrier-grade NAT), reserved, multicast or unspecified
 * address, or that does not resolve, is refused, redirect hops included, whether or not an allow-list
 * is set. {@link #PRIVATE_EXCEPTIONS_PROPERTY} lists the exceptions (hosts, {@code host:port}, CIDR blocks),
 * and so does an exact entry of the allow-list; {@code -Debx.connectors.egress.blockPrivate=false}
 * switches the rule off for a whole development instance. The allow-list itself stays optional, and
 * {@link #REQUIRE_PROPERTY} still refuses every call while it is unset.
 */
public final class Egress
{
	public static final String PROPERTY = "ebx.connectors.allowedHosts";
	/** {@code true}: without an allow-list every outbound call is refused, instead of all being allowed. */
	public static final String REQUIRE_PROPERTY = "ebx.connectors.egress.requireAllowList";
	/**
	 * Loopback, private and unresolvable hosts are refused unless an exception names them. ON unless this is
	 * {@code false} (the default flipped on 2026-10-06; before, it was off unless {@code true}).
	 */
	public static final String BLOCK_PRIVATE_PROPERTY = "ebx.connectors.egress.blockPrivate";
	/**
	 * The exceptions to the private-host rule: comma-separated {@code host}, {@code host:port}, IPv4 or IPv6 address,
	 * {@code [ipv6]:port} or CIDR block ({@code 10.1.0.0/16}, {@code fd00::/8}). A name entry lifts the rule for that
	 * exact name; an address or CIDR entry lifts it for resolved addresses inside it. Never for link-local or cloud
	 * metadata addresses (those need an exact entry of {@link #PROPERTY}). {@code file:<path>} reads the entries from a
	 * file (one per line, {@code #} comments) re-read when it changes, checked at most every 30 s, like {@link #PROPERTY};
	 * an unreadable file allows nothing. A SEPARATE property on purpose: it re-allows only the targets it lists and does
	 * not turn the allow-list on for public hosts.
	 */
	public static final String PRIVATE_EXCEPTIONS_PROPERTY = "ebx.connectors.egress.privateExceptions";
	private static final Pattern HOST = Pattern.compile("(?:^|//|@)([A-Za-z0-9._-]+)(?::(\\d+))?");
	private static final Pattern NUMERIC_HOST = Pattern.compile("[0-9.]+|0[xX][0-9a-fA-F.xX]+");
	/** The only spelling of an IPv4 address accepted as a literal: four decimal parts, no leading zero. */
	private static final Pattern CANONICAL_IPV4 = Pattern.compile("(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
	/** Metadata services reached by name; their addresses are caught by {@link #metadataAddress}. */
	private static final java.util.Set<String> METADATA_NAMES = new java.util.HashSet<String>(
		java.util.Arrays.asList("metadata.google.internal", "metadata.goog"));
	private static final java.util.concurrent.atomic.AtomicBoolean WARNED = new java.util.concurrent.atomic.AtomicBoolean(false);

	private Egress()
	{
	}

	public static boolean enabled()
	{
		return !Egress.patterns().isEmpty();
	}

	/** True when {@link #REQUIRE_PROPERTY} is on: no allow-list means every outbound call is refused. */
	public static boolean allowListRequired()
	{
		return Egress.flag(Egress.REQUIRE_PROPERTY);
	}

	/** True unless {@link #BLOCK_PRIVATE_PROPERTY} is {@code false}: loopback, private and unresolvable hosts need an exception. */
	public static boolean privateHostsBlocked()
	{
		return !"false".equalsIgnoreCase(String.valueOf(System.getProperty(Egress.BLOCK_PRIVATE_PROPERTY)).trim());
	}

	/** How many exceptions {@link #PRIVATE_EXCEPTIONS_PROPERTY} lists (for the status route: the entries themselves are not shown). */
	public static int privateExceptionCount()
	{
		return Egress.exceptions().size();
	}

	/**
	 * Where a list property comes from and how much of it was understood (audit X3-01). A value that is a bare file path
	 * (without the {@code file:} prefix) is read as a one-entry list, which allows nothing and says nothing: this report counts the
	 * entries read and the entries ignored, and says when an entry looks like a path. It never changes how a list is read.
	 */
	public static final class ListReport
	{
		/** The JVM property. */
		public final String property;
		/** {@code unset}, {@code value} (the property holds the list) or {@code file} (the property is {@code file:<path>}). */
		public final String source;
		/** The file of a {@code file:} source, else null. */
		public final String path;
		/** For a {@code file:} source: whether the file exists and can be read. */
		public final boolean fileReadable;
		/** Entries that can match something. */
		public final int entries;
		/** Entries that can never match anything (not a host, an address or, for the exceptions, a CIDR block). */
		public final int ignored;
		/** True when a plain value looks like a file path: the {@code file:} prefix is missing. */
		public final boolean looksLikePath;
		/** The operator's warning, in English, null when nothing is wrong. */
		public final String warning;

		ListReport(final String pProperty, final String pSource, final String pPath, final boolean pReadable, final int pEntries, final int pIgnored,
			final boolean pLooksLikePath, final String pWarning)
		{
			this.property = pProperty;
			this.source = pSource;
			this.path = pPath;
			this.fileReadable = pReadable;
			this.entries = pEntries;
			this.ignored = pIgnored;
			this.looksLikePath = pLooksLikePath;
			this.warning = pWarning;
		}
	}

	/** {@link #report} of {@link #PRIVATE_EXCEPTIONS_PROPERTY}. */
	public static ListReport privateExceptionsReport()
	{
		return Egress.report(Egress.PRIVATE_EXCEPTIONS_PROPERTY, true);
	}

	/** {@link #report} of {@link #PROPERTY} (the allow-list). */
	public static ListReport allowedHostsReport()
	{
		return Egress.report(Egress.PROPERTY, false);
	}

	/** Audit X3-01: logs, at start-up, where each list comes from and how many entries were read and ignored; a WARN when something is off. */
	public static void logAtStartup()
	{
		final LoggingCategory log = LoggingCategory.getKernel();
		for (final ListReport report : new ListReport[] { Egress.privateExceptionsReport(), Egress.allowedHostsReport() })
		{
			if ("unset".equals(report.source))
			{
				continue;
			}
			final String line = "[connectors] " + report.property + ": " + report.entries + " entr" + (report.entries == 1 ? "y" : "ies") + " read from "
				+ ("file".equals(report.source) ? "file " + report.path + (report.fileReadable ? "" : " (unreadable or absent)") : "the property value")
				+ ", " + report.ignored + " ignored";
			if (report.warning != null)
			{
				log.warn(line + ". " + report.warning);
			}
			else
			{
				log.info(line);
			}
		}
	}

	/**
	 * Reads the list property without changing anything: its source, the entries that can match, the entries that cannot, and
	 * whether a plain value looks like a file path ({@code /…}, {@code ~…}, a drive letter, a backslash, or a file that exists).
	 */
	static ListReport report(final String pProperty, final boolean pCidr)
	{
		final String raw = System.getProperty(pProperty);
		if (raw == null || raw.trim().isEmpty())
		{
			return new ListReport(pProperty, "unset", null, false, 0, 0, false, null);
		}
		final boolean file = raw.trim().toLowerCase().startsWith("file:");
		final String path = file ? raw.trim().substring(5).trim() : null;
		boolean readable = false;
		String content = raw;
		if (file)
		{
			readable = new java.io.File(path).isFile();
			content = readable ? Egress.listProperty(pProperty, "") : "";
		}
		int entries = 0;
		int ignored = 0;
		String pathEntry = null;
		for (final String part : content == null ? new String[0] : content.split(","))
		{
			final String original = part.trim();
			if (original.isEmpty())
			{
				continue;
			}
			if (Egress.usableEntry(original.toLowerCase(), pCidr))
			{
				entries++;
			}
			else
			{
				ignored++;
			}
			if (!file && pathEntry == null && Egress.looksLikePath(original))
			{
				pathEntry = original;
			}
		}
		final StringBuilder warning = new StringBuilder();
		if (pathEntry != null)
		{
			warning.append("The value looks like a file path (").append(pathEntry).append("): the prefix file: is expected (-D").append(pProperty)
				.append("=file:<path>). Without it the value is read as a plain list: ").append(entries).append(" usable entr").append(entries == 1 ? "y" : "ies").append('.');
		}
		else if (ignored > 0)
		{
			warning.append(ignored).append(" entr").append(ignored == 1 ? "y is" : "ies are").append(" not a host, an address").append(pCidr ? " or a CIDR block" : "")
				.append(" and can never match.");
		}
		if (file && !readable)
		{
			warning.append(warning.length() == 0 ? "" : " ").append("The file is absent or unreadable: ")
				.append(Egress.PROPERTY.equals(pProperty) ? "every outbound call is refused." : "no private target is allowed.");
		}
		else if (file && entries == 0 && ignored == 0)
		{
			warning.append(warning.length() == 0 ? "" : " ").append("The file lists no entry.");
		}
		return new ListReport(pProperty, file ? "file" : "value", path, readable, entries, ignored, pathEntry != null,
			warning.length() == 0 ? null : warning.toString());
	}

	private static boolean looksLikePath(final String pEntry)
	{
		return pEntry.startsWith("/") || pEntry.startsWith("~") || pEntry.indexOf('\\') >= 0 || pEntry.matches("[A-Za-z]:[\\\\/].*")
			|| (pEntry.indexOf('.') >= 0 && new java.io.File(pEntry).isFile());
	}

	/** Whether an entry (lower case) can ever match: a host name, a wildcard name, an address, {@code host:port}, {@code [ipv6]:port}, a CIDR block when allowed, or {@code none}. */
	private static boolean usableEntry(final String pEntry, final boolean pCidr)
	{
		if ("none".equals(pEntry))
		{
			return true;
		}
		if (pEntry.indexOf('/') >= 0)
		{
			final int slash = pEntry.indexOf('/');
			if (!pCidr || slash < 1)
			{
				return false;
			}
			final String net = pEntry.substring(0, slash).replace("[", "").replace("]", "");
			try
			{
				final int prefix = Integer.parseInt(pEntry.substring(slash + 1).trim());
				return net.matches("[0-9a-f:.]+") && prefix >= 0 && prefix <= (net.indexOf(':') >= 0 ? 128 : 32);
			}
			catch (final NumberFormatException ex)
			{
				return false;
			}
		}
		if (pEntry.startsWith("["))
		{
			final int close = pEntry.indexOf(']');
			return close > 1 && pEntry.substring(1, close).matches("[0-9a-f:.]+") && (pEntry.length() == close + 1 || pEntry.substring(close + 1).matches(":\\d+"));
		}
		final int colon = pEntry.indexOf(':');
		if (colon >= 0 && colon != pEntry.lastIndexOf(':'))
		{
			return pEntry.matches("[0-9a-f:.]+");
		}
		final String host = colon < 0 ? pEntry : pEntry.substring(0, colon);
		if (colon >= 0 && !pEntry.substring(colon + 1).matches("\\d+"))
		{
			return false;
		}
		return host.matches("\\*|(\\*\\.)?[a-z0-9._-]+");
	}

	/** Refuses (IllegalStateException) a URL, {@code host[:port]} or comma-separated list whose host is not allowed. */
	public static void check(final String pTarget)
	{
		final String target = pTarget == null ? "" : pTarget.trim();
		final String lower = target.toLowerCase();
		if (target.indexOf(',') < 0 && (lower.startsWith("http://") || lower.startsWith("https://")))
		{
			try
			{
				final java.net.URL url = new java.net.URL(target);
				if (url.getHost() != null && !url.getHost().trim().isEmpty())
				{
					Egress.check(url);
					return;
				}
			}
			catch (final java.net.MalformedURLException ex)
			{
				// a template or a placeholder rather than a URL: read as text below
			}
		}
		Egress.verify(pTarget == null ? null : Egress.hostsOf(pTarget), true, false);
	}

	/**
	 * Same check for a parsed HTTP URL: the host and port are the ones the connection will use, so
	 * what is checked and what is called cannot differ by a parsing subtlety.
	 */
	public static void check(final java.net.URL pUrl)
	{
		final String host = pUrl.getHost() == null ? "" : pUrl.getHost().trim();
		if (host.isEmpty())
		{
			throw new IllegalStateException("outbound call refused: the URL names no host");
		}
		final List<String[]> hosts = new ArrayList<String[]>();
		hosts.add(new String[] { Egress.normalize(host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host),
			pUrl.getPort() < 0 ? null : String.valueOf(pUrl.getPort()) });
		Egress.verify(hosts, !Egress.throughProxy(pUrl), false);
	}

	/**
	 * Whether the JVM sends this URL to an HTTP proxy. The proxy then resolves the name, on its own
	 * network: a lookup from here would judge another answer, or fail where only the proxy has DNS.
	 * Such a call is judged on its literal host.
	 */
	private static boolean throughProxy(final java.net.URL pUrl)
	{
		try
		{
			final java.net.ProxySelector selector = java.net.ProxySelector.getDefault();
			final List<java.net.Proxy> proxies = selector == null ? null : selector.select(pUrl.toURI());
			return proxies != null && !proxies.isEmpty() && proxies.get(0) != null && proxies.get(0).type() != java.net.Proxy.Type.DIRECT;
		}
		catch (final Exception ex)
		{
			return false;
		}
	}

	/**
	 * The check for the module's own workload-identity lookups (EC2 / ECS / Azure / Google metadata
	 * services): their address is fixed by the platform or set at JVM level, never by a connector, so
	 * the link-local refusal does not apply. The allow-list, when there is one, still does.
	 */
	public static void checkPlatformMetadata(final String pTarget)
	{
		Egress.verify(pTarget == null ? null : Egress.hostsOf(pTarget), true, true);
	}

	private static void verify(final List<String[]> pHosts, final boolean pResolve, final boolean pPlatformMetadata)
	{
		final List<String[]> patterns = Egress.patterns();
		final boolean required = Egress.flag(Egress.REQUIRE_PROPERTY);
		// Insecure-by-default posture (the security review of September 2026, finding 07): logged once so an
		// operator who never set ebx.connectors.allowedHosts finds out from the log, not from an incident.
		if (patterns.isEmpty() && !required && Egress.WARNED.compareAndSet(false, true))
		{
			LoggingCategory.getKernel().warn("[connectors] " + Egress.PROPERTY + " is not set: outbound connector calls are not restricted to an"
					+ " allow-list of hosts (optional). "
					+ (Egress.privateHostsBlocked()
						? "Loopback, private, link-local and cloud metadata addresses are refused (exceptions: -D" + Egress.PRIVATE_EXCEPTIONS_PROPERTY + ")."
						: "Link-local and cloud metadata addresses are refused; loopback and private ones are NOT, because -D" + Egress.BLOCK_PRIVATE_PROPERTY + "=false is set.")
					+ " Set " + Egress.PROPERTY + " (or file:<path> to a maintained list) to restrict egress to named hosts; -D"
					+ Egress.REQUIRE_PROPERTY + "=true refuses every call until it is set.");
		}
		if (pHosts == null)
		{
			return;
		}
		for (final String[] host : pHosts)
		{
			final String label = host[0] + (host[1] == null ? "" : ":" + host[1]);
			if (patterns.isEmpty())
			{
				if (required)
				{
					throw new IllegalStateException("outbound call to " + label + " refused: " + Egress.REQUIRE_PROPERTY
							+ "=true and " + Egress.PROPERTY + " is not set (list the hosts the connectors may call, or file:<path> to a maintained list)");
				}
			}
			else if (!Egress.allowed(host[0], host[1], patterns, false))
			{
				throw new IllegalStateException("outbound call to " + label + " refused: not in " + Egress.PROPERTY + " (" + Egress.allowList() + ")");
			}
			if (pPlatformMetadata || Egress.allowed(host[0], host[1], patterns, true))
			{
				continue;
			}
			final String reason = Egress.addressRefusal(host[0], host[1], pResolve);
			if (reason != null)
			{
				throw new IllegalStateException("outbound call to " + label + " refused: " + reason);
			}
		}
	}

	private static final String METADATA_ADVICE = " (to allow it, name this host as an exact entry of " + Egress.PROPERTY + ", not through a wildcard)";
	private static final String PRIVATE_ADVICE = " (to allow it, add the host, host:port or CIDR block to -D" + Egress.PRIVATE_EXCEPTIONS_PROPERTY
		+ ", or name it as an exact entry of -D" + Egress.PROPERTY + "; -D" + Egress.BLOCK_PRIVATE_PROPERTY + "=false lifts the rule for the whole instance)";

	/**
	 * Why the addresses behind a host forbid the call, null when they do not. Without
	 * {@code pResolve} only literal addresses and known names are judged (no DNS lookup: the admin
	 * screens call this for every connector they list).
	 */
	private static String addressRefusal(final String pHost, final String pPort, final boolean pResolve)
	{
		final String name = pHost.endsWith(".") ? pHost.substring(0, pHost.length() - 1) : pHost;
		if (Egress.METADATA_NAMES.contains(name))
		{
			return "it is a cloud metadata service" + Egress.METADATA_ADVICE;
		}
		final boolean blockPrivate = Egress.privateHostsBlocked();
		final boolean literal = name.indexOf(':') >= 0 || Egress.NUMERIC_HOST.matcher(name).matches();
		if (!literal && !pResolve)
		{
			return null;
		}
		if (blockPrivate && !name.contains(":") && Egress.NUMERIC_HOST.matcher(name).matches() && !Egress.CANONICAL_IPV4.matcher(name).matches())
		{
			// 2130706433, 0x7f.1, 0177.0.0.1, 127.1: this JVM, a proxy, curl and a driver do not all read them the same way
			return "it is a numeric address in a non-standard spelling (write an IPv4 address as four decimal parts, e.g. 10.0.0.1)";
		}
		final List<Object[]> exceptions = blockPrivate ? Egress.exceptions() : java.util.Collections.<Object[]>emptyList();
		// a name the operator listed is allowed whatever it resolves to, except link-local and metadata addresses (below)
		final boolean nameExcepted = Egress.nameExcepted(name, pPort, exceptions);
		final java.net.InetAddress[] addresses;
		try
		{
			addresses = java.net.InetAddress.getAllByName(name);
		}
		catch (final java.net.UnknownHostException ex)
		{
			// an unknown name cannot be called: unless private hosts are blocked, the connection reports it better than a refusal here
			return blockPrivate && !nameExcepted ? "the host does not resolve and " + Egress.BLOCK_PRIVATE_PROPERTY + " is on" + Egress.PRIVATE_ADVICE : null;
		}
		catch (final SecurityException ex)
		{
			return blockPrivate && !nameExcepted ? "the host cannot be resolved and " + Egress.BLOCK_PRIVATE_PROPERTY + " is on" + Egress.PRIVATE_ADVICE : null;
		}
		for (final java.net.InetAddress address : addresses)
		{
			final java.net.InetAddress effective = Egress.embeddedIpv4(address);
			if (effective.isLinkLocalAddress() || Egress.metadataAddress(effective))
			{
				return "it " + (literal ? "is" : "resolves to") + " a link-local or cloud metadata address (" + effective.getHostAddress() + ")"
					+ Egress.METADATA_ADVICE;
			}
			if (blockPrivate && !nameExcepted && Egress.privateAddress(effective) && !Egress.addressExcepted(effective, pPort, exceptions))
			{
				return "it " + (literal ? "is" : "resolves to") + " a loopback or private address (" + effective.getHostAddress() + ") and "
						+ Egress.BLOCK_PRIVATE_PROPERTY + " is on" + Egress.PRIVATE_ADVICE;
			}
		}
		return null;
	}

	/**
	 * The parsed {@link #PRIVATE_EXCEPTIONS_PROPERTY}: {@code {kind, host-or-address, port, prefix}} where kind is
	 * {@code "name"}, {@code "ip"} (host = canonical text) or {@code "cidr"} (host = byte[] network, prefix = Integer).
	 * Port is a String or null.
	 */
	private static List<Object[]> exceptions()
	{
		final List<Object[]> out = new ArrayList<Object[]>();
		final String value = Egress.listProperty(Egress.PRIVATE_EXCEPTIONS_PROPERTY, "");
		if (value == null || value.trim().isEmpty())
		{
			return out;
		}
		for (final String part : value.split(","))
		{
			final String entry = part.trim().toLowerCase();
			if (entry.isEmpty())
			{
				continue;
			}
			String host = entry;
			String port = null;
			int slash = entry.indexOf('/');
			if (slash > 0)
			{
				// CIDR: address/prefix
				final String net = entry.substring(0, slash).replace("[", "").replace("]", "");
				try
				{
					final byte[] network = java.net.InetAddress.getByName(net).getAddress();
					final int prefix = Integer.parseInt(entry.substring(slash + 1).trim());
					if (prefix >= 0 && prefix <= network.length * 8)
					{
						out.add(new Object[] { "cidr", network, null, Integer.valueOf(prefix) });
					}
				}
				catch (final Exception ex)
				{
					// an entry that does not parse allows nothing
				}
				continue;
			}
			final int close = entry.startsWith("[") ? entry.indexOf(']') : -1;
			if (close > 1)
			{
				final String rest = entry.substring(close + 1);
				host = Egress.normalize(entry.substring(1, close));
				port = rest.matches(":\\d+") ? rest.substring(1) : null;
			}
			else if (entry.indexOf(':') == entry.lastIndexOf(':') && entry.indexOf(':') > 0 && entry.substring(entry.indexOf(':') + 1).matches("\\d+"))
			{
				host = entry.substring(0, entry.indexOf(':'));
				port = entry.substring(entry.indexOf(':') + 1);
			}
			else if (entry.indexOf(':') >= 0)
			{
				host = Egress.normalize(entry); // a bare IPv6 address
			}
			final boolean ip = host.indexOf(':') >= 0 || Egress.NUMERIC_HOST.matcher(host).matches();
			out.add(new Object[] { ip ? "ip" : "name", host, port, null });
		}
		return out;
	}

	private static boolean nameExcepted(final String pName, final String pPort, final List<Object[]> pExceptions)
	{
		for (final Object[] e : pExceptions)
		{
			if ("name".equals(e[0]) && e[1].equals(pName) && (e[2] == null || e[2].equals(pPort)))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean addressExcepted(final java.net.InetAddress pAddress, final String pPort, final List<Object[]> pExceptions)
	{
		final byte[] bytes = pAddress.getAddress();
		for (final Object[] e : pExceptions)
		{
			if ("cidr".equals(e[0]))
			{
				if (Egress.inCidr(bytes, (byte[]) e[1], ((Integer) e[3]).intValue()))
				{
					return true;
				}
			}
			else if ("ip".equals(e[0]) && (e[2] == null || e[2].equals(pPort)))
			{
				try
				{
					if (java.net.InetAddress.getByName((String) e[1]).equals(pAddress))
					{
						return true;
					}
				}
				catch (final java.net.UnknownHostException ex)
				{
					// not an address
				}
			}
		}
		return false;
	}

	/** Whether an address lies in a CIDR block of the same family (an IPv4 address never matches an IPv6 block). */
	static boolean inCidr(final byte[] pAddress, final byte[] pNetwork, final int pPrefix)
	{
		if (pAddress.length != pNetwork.length)
		{
			return false;
		}
		final int whole = pPrefix / 8;
		for (int i = 0; i < whole; i++)
		{
			if (pAddress[i] != pNetwork[i])
			{
				return false;
			}
		}
		final int rest = pPrefix % 8;
		if (rest == 0)
		{
			return true;
		}
		final int mask = 0xff << (8 - rest) & 0xff;
		return (pAddress[whole] & mask) == (pNetwork[whole] & mask);
	}

	/** The IPv4 address an IPv6 address stands for (IPv4-compatible {@code ::a.b.c.d}, NAT64 {@code 64:ff9b::/96}), else the address itself. */
	private static java.net.InetAddress embeddedIpv4(final java.net.InetAddress pAddress)
	{
		final byte[] b = pAddress.getAddress();
		if (b.length != 16)
		{
			return pAddress;
		}
		boolean zeroPrefix = true;
		for (int i = 0; i < 12; i++)
		{
			zeroPrefix &= b[i] == 0;
		}
		boolean nat64 = b[0] == 0 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
		for (int i = 4; i < 12; i++)
		{
			nat64 &= b[i] == 0;
		}
		// 6to4 (2002::/16) carries the IPv4 address in bytes 2..5
		if ((b[0] & 0xff) == 0x20 && b[1] == 0x02)
		{
			try
			{
				return java.net.InetAddress.getByAddress(new byte[] { b[2], b[3], b[4], b[5] });
			}
			catch (final java.net.UnknownHostException ex)
			{
				return pAddress;
			}
		}
		// ::, ::1 keep their IPv6 meaning (unspecified, loopback)
		final boolean compatible = zeroPrefix && (b[12] != 0 || b[13] != 0 || b[14] != 0 || (b[15] & 0xff) > 1);
		if (!compatible && !nat64)
		{
			return pAddress;
		}
		try
		{
			return java.net.InetAddress.getByAddress(new byte[] { b[12], b[13], b[14], b[15] });
		}
		catch (final java.net.UnknownHostException ex)
		{
			return pAddress;
		}
	}

	/** Metadata services that do not sit in a link-local range: AWS over IPv6, Alibaba Cloud, Oracle Cloud. */
	private static boolean metadataAddress(final java.net.InetAddress pAddress)
	{
		final byte[] b = pAddress.getAddress();
		if (b.length == 4)
		{
			final int a0 = b[0] & 0xff, a1 = b[1] & 0xff, a2 = b[2] & 0xff, a3 = b[3] & 0xff;
			return (a0 == 100 && a1 == 100 && a2 == 100 && a3 == 200) || (a0 == 192 && a1 == 0 && a2 == 0 && a3 == 192);
		}
		// fd00:ec2::254
		if ((b[0] & 0xff) != 0xfd || b[1] != 0 || (b[2] & 0xff) != 0x0e || (b[3] & 0xff) != 0xc2 || b[14] != 0x02 || b[15] != 0x54)
		{
			return false;
		}
		for (int i = 4; i < 14; i++)
		{
			if (b[i] != 0)
			{
				return false;
			}
		}
		return true;
	}

	private static boolean privateAddress(final java.net.InetAddress pAddress)
	{
		if (pAddress.isLoopbackAddress() || pAddress.isAnyLocalAddress() || pAddress.isSiteLocalAddress() || pAddress.isMulticastAddress())
		{
			return true;
		}
		final byte[] b = pAddress.getAddress();
		if (b.length == 4)
		{
			final int a0 = b[0] & 0xff, a1 = b[1] & 0xff;
			// 0.0.0.0/8 (this network), 100.64.0.0/10 (carrier-grade NAT), 192.0.0.0/24 (IETF protocol assignments),
			// 198.18.0.0/15 (benchmarking), 240.0.0.0/4 (reserved, incl. 255.255.255.255)
			return a0 == 0 || (a0 == 100 && a1 >= 64 && a1 <= 127) || (a0 == 192 && a1 == 0 && (b[2] & 0xff) == 0) || (a0 == 198 && (a1 == 18 || a1 == 19))
				|| a0 >= 240;
		}
		// fc00::/7, the IPv6 unique local addresses
		return (b[0] & 0xfe) == 0xfc;
	}

	private static boolean flag(final String pProperty)
	{
		return "true".equalsIgnoreCase(String.valueOf(System.getProperty(pProperty)).trim());
	}

	/**
	 * Connector types that never open a network connection: the {@code sample-file} type serves an uploaded file, and its
	 * base URL ({@code https://persons-sap.sample-file.local/data}, {@code local://sample-file}) is a placeholder that is
	 * never called, kept because the validator wants an absolute URL and the catalog derives its OpenLineage namespace from
	 * the host. The {@code documents} type reads an upload, its sample document or a folder under
	 * {@code ebx.connectors.documents.roots}; its model is reached through another connector ({@code llm.connector}), which is
	 * checked when that connector loads. A type is listed here only after reading its code: every type that can call out
	 * stays checked.
	 */
	private static final java.util.Set<String> NO_OUTBOUND_TYPES = java.util.Collections.unmodifiableSet(
			new java.util.HashSet<String>(java.util.Arrays.asList("sample-file", "documents")));

	/** False for a connector type that makes no outbound call, whose base URL (and other URL properties) are placeholders. */
	public static boolean callsOut(final ConnectorConfig pConfig)
	{
		return pConfig == null || pConfig.getType() == null || !Egress.NO_OUTBOUND_TYPES.contains(pConfig.getType().trim().toLowerCase());
	}

	public static void checkBaseUrl(final ConnectorConfig pConfig)
	{
		if (!Egress.callsOut(pConfig))
		{
			return;
		}
		Egress.check(pConfig.getBaseUrl());
		Egress.check(pConfig.getProperty("oauth.tokenUrl", null));
		Egress.check(pConfig.getProperty("instanceUrl", null));
	}

	/**
	 * Read-only posture of one connector against the current allow-list, for the admin UI — never
	 * throws, never blocks a call. {@code NOT_ENFORCED}: {@link #PROPERTY} is not set at all, this
	 * connector (like every other) is unrestricted. {@code COVERED}: the list is set and already
	 * names every host this connector would call. {@code NOT_COVERED}: the list is set but is stale
	 * relative to this connector — it will be refused with {@link #check} the next time it actually
	 * runs, so the human who maintains the list needs to add it now, not find out from an incident.
	 */
	public enum Posture
	{
		NOT_ENFORCED, COVERED, NOT_COVERED
	}

	public static Posture posture(final ConnectorConfig pConfig)
	{
		if (!Egress.callsOut(pConfig))
		{
			return Egress.enabled() ? Posture.COVERED : Posture.NOT_ENFORCED;
		}
		try
		{
			// literal addresses and names only: this is drawn for every connector of a list, no DNS lookup here
			for (final String target : new String[] { pConfig.getBaseUrl(), pConfig.getProperty("oauth.tokenUrl", null),
				pConfig.getProperty("instanceUrl", null) })
			{
				Egress.verify(target == null ? null : Egress.hostsOf(target), false, false);
			}
		}
		catch (final IllegalStateException ex)
		{
			return Posture.NOT_COVERED;
		}
		return Egress.enabled() ? Posture.COVERED : Posture.NOT_ENFORCED;
	}

	/**
	 * Where the allow-list currently comes from — for the admin UI only, never used to enforce
	 * anything. {@code null} when {@link #PROPERTY} is not set at all.
	 */
	public static String source()
	{
		final String value = System.getProperty(Egress.PROPERTY);
		if (value == null || value.trim().isEmpty())
		{
			return null;
		}
		final String trimmed = value.trim();
		if (trimmed.toLowerCase().startsWith("file:"))
		{
			final String path = trimmed.substring(5).trim();
			return new java.io.File(path).isFile() ? "file:" + path : "file:" + path + " (not found on disk — treated as deny-all)";
		}
		return Egress.PROPERTY + " (set directly at the JVM level, not a file)";
	}

	/**
	 * A ready-to-paste pattern covering every host this connector would actually call (base URL,
	 * OAuth2 token URL, discovered instance URL) — for the admin UI only. {@code null} when the
	 * connector has no outbound host yet (e.g. its base URL is still a placeholder).
	 */
	public static String example(final ConnectorConfig pConfig)
	{
		if (!Egress.callsOut(pConfig))
		{
			return null;
		}
		final java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<String>();
		for (final String target : new String[] { pConfig.getBaseUrl(), pConfig.getProperty("oauth.tokenUrl", null),
			pConfig.getProperty("instanceUrl", null) })
		{
			if (target == null || target.trim().isEmpty())
			{
				continue;
			}
			for (final String[] host : Egress.hostsOf(target))
			{
				hosts.add(host[0] + (host[1] == null ? "" : ":" + host[1]));
			}
		}
		return hosts.isEmpty() ? null : String.join(",", hosts);
	}

	/** The hosts named in a URL, a JDBC URL ({@code //host} or {@code @host}), a {@code host:port} or a comma-separated list. */
	static List<String[]> hostsOf(final String pTarget)
	{
		final List<String[]> out = new ArrayList<String[]>();
		for (final String part : pTarget.split(","))
		{
			final String target = part.trim();
			if (target.isEmpty())
			{
				continue;
			}
			final int scheme = target.indexOf("://");
			if (scheme >= 0)
			{
				// URL (also jdbc:xxx://host): the authority up to the next / ; ?
				final String rest = target.substring(scheme + 3);
				final String authority = rest.split("[/;?#]", 2)[0];
				final String hostPort = authority.contains("@") ? authority.substring(authority.lastIndexOf('@') + 1) : authority;
				Egress.add(out, hostPort);
			}
			else if (target.contains("@"))
			{
				// jdbc:oracle:thin:@host:1521/svc
				Egress.add(out, target.substring(target.lastIndexOf('@') + 1).split("[/;?]", 2)[0]);
			}
			else if (target.toLowerCase().startsWith("jdbc:"))
			{
				// jdbc:h2:mem, jdbc:sqlite:file... no host: local
			}
			else
			{
				Egress.add(out, target.split("[/;?]", 2)[0]);
			}
		}
		return out;
	}

	private static void add(final List<String[]> pOut, final String pHostPort)
	{
		final String value = pHostPort.trim();
		if (value.isEmpty())
		{
			return;
		}
		final int close = value.startsWith("[") ? value.indexOf(']') : -1;
		if (close > 1)
		{
			// [IPv6]:port
			final String rest = value.substring(close + 1);
			pOut.add(new String[] { Egress.normalize(value.substring(1, close)), rest.matches(":\\d+") ? rest.substring(1) : null });
			return;
		}
		final Matcher m = Egress.HOST.matcher(value);
		if (m.find() && m.start() == 0)
		{
			pOut.add(new String[] { m.group(1).toLowerCase(), m.group(2) });
			return;
		}
		// not a host this class can read (a bare IPv6 address, an odd character): kept whole, so
		// that an allow-list refuses it instead of the target passing as "no host, nothing to check"
		pOut.add(new String[] { Egress.normalize(value), null });
	}

	/** Lower case; an IPv6 literal in its canonical text form, without zone, so that {@code ::1} and {@code 0:0:0:0:0:0:0:1} compare equal. */
	private static String normalize(final String pHost)
	{
		final String host = pHost.trim().toLowerCase();
		if (host.indexOf(':') < 0)
		{
			return host;
		}
		try
		{
			final String text = java.net.InetAddress.getByName(host).getHostAddress();
			final int zone = text.indexOf('%');
			return zone < 0 ? text : text.substring(0, zone);
		}
		catch (final Exception ex)
		{
			return host;
		}
	}

	/**
	 * Whether a pattern of the list covers the host. {@code pExactOnly}: wildcards do not count,
	 * which is what lifts the address rules (link-local, metadata, private) for a named host.
	 */
	private static boolean allowed(final String pHost, final String pPort, final List<String[]> pPatterns, final boolean pExactOnly)
	{
		for (final String[] pattern : pPatterns)
		{
			final String host = pattern[0];
			final boolean wildcard = host.indexOf('*') >= 0;
			if (pExactOnly && wildcard)
			{
				continue;
			}
			final boolean hostOk = "*".equals(host) || host.equals(pHost)
					|| (host.startsWith("*.") && (pHost.endsWith(host.substring(1)) || pHost.equals(host.substring(2))));
			final boolean portOk = pattern[1] == null || pattern[1].equals(pPort);
			if (hostOk && portOk)
			{
				return true;
			}
		}
		return false;
	}

	private static final java.util.concurrent.ConcurrentHashMap<String, Object[]> FILE_CACHE = new java.util.concurrent.ConcurrentHashMap<String, Object[]>(); // property -> {path, mtime, content, checkedAt}

	/**
	 * The allow-list: the property itself, or, when it is {@code file:/etc/ebx/egress.txt}, the
	 * content of that file (one pattern per line, {@code #} comments), re-read when the file changes
	 * (checked at most every 30 s): the operations team edits the file, no restart.
	 */
	static String allowList()
	{
		return Egress.listProperty(Egress.PROPERTY, "none"); // an unreadable file refuses everything rather than opening everything
	}

	/** {@link #allowList()} for any of the module's list properties; {@code pUnreadable} is what an unreadable or empty file stands for. */
	private static String listProperty(final String pProperty, final String pUnreadable)
	{
		final String value = System.getProperty(pProperty);
		if (value == null || !value.trim().toLowerCase().startsWith("file:"))
		{
			return value;
		}
		final String path = value.trim().substring(5).trim();
		final long now = System.currentTimeMillis();
		final Object[] cached = Egress.FILE_CACHE.get(pProperty);
		if (cached != null && path.equals(cached[0]) && now - ((Long) cached[3]).longValue() < 30000L)
		{
			return (String) cached[2];
		}
		final java.io.File file = new java.io.File(path);
		final long mtime = file.lastModified();
		if (cached != null && path.equals(cached[0]) && mtime == ((Long) cached[1]).longValue())
		{
			Egress.FILE_CACHE.put(pProperty, new Object[] { path, Long.valueOf(mtime), cached[2], Long.valueOf(now) });
			return (String) cached[2];
		}
		String content = pUnreadable;
		if (file.isFile())
		{
			try
			{
				final StringBuilder b = new StringBuilder();
				for (final String line : SecretResolvers.readFile(file).split("\\r?\\n"))
				{
					final String l = line.replaceAll("#.*$", "").trim();
					if (!l.isEmpty())
					{
						b.append(b.length() == 0 ? "" : ",").append(l);
					}
				}
				content = b.length() == 0 ? pUnreadable : b.toString();
			}
			catch (final Exception ex)
			{
				content = pUnreadable;
			}
		}
		Egress.FILE_CACHE.put(pProperty, new Object[] { path, Long.valueOf(mtime), content, Long.valueOf(now) });
		return content;
	}

	private static List<String[]> patterns()
	{
		final List<String[]> out = new ArrayList<String[]>();
		final String value = Egress.allowList();
		if (value == null || value.trim().isEmpty())
		{
			return out;
		}
		for (final String part : value.split(","))
		{
			final String p = part.trim().toLowerCase();
			if (p.isEmpty())
			{
				continue;
			}
			if ("none".equals(p))
			{
				out.add(new String[] { "", null }); // matches nothing
				continue;
			}
			final int close = p.startsWith("[") ? p.indexOf(']') : -1;
			if (close > 1)
			{
				// [IPv6] or [IPv6]:port
				final String rest = p.substring(close + 1);
				out.add(new String[] { Egress.normalize(p.substring(1, close)), rest.matches(":\\d+") ? rest.substring(1) : null });
				continue;
			}
			if (p.indexOf(':') != p.lastIndexOf(':'))
			{
				// a bare IPv6 address: its colons are not a port separator
				out.add(new String[] { Egress.normalize(p), null });
				continue;
			}
			final int colon = p.lastIndexOf(':');
			out.add(colon > 0 && p.substring(colon + 1).matches("\\d+") ? new String[] { p.substring(0, colon), p.substring(colon + 1) } : new String[] { p, null });
		}
		return out;
	}
}
