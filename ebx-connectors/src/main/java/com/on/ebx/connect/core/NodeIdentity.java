package com.on.ebx.connect.core;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.Date;

/**
 * Who a running import belongs to, so that the start of one EBX node does not declare dead a run that another node of the
 * same repository is executing. A run holds a lease in its {@code ExecutionRun} row ({@code leaseOwner},
 * {@code leaseExpiresAt}), written when it starts and renewed at each batch, cleared when it ends.
 * <p>
 * The owner is {@code <node>#<JVM start time in milliseconds>}. The node is {@value #PROPERTY_NODE} or, by default, the host
 * name; the start time tells one incarnation of a node from the next, so a node that restarts recognises the runs of its own
 * previous incarnation (they are dead) without ever mistaking those of another JVM. Two JVMs on one host that share a
 * repository must be given different {@value #PROPERTY_NODE} values; with the default they count as the same node, which is
 * what happened to every node before leases existed.
 */
public final class NodeIdentity
{
	/** JVM property: the name of this node (default: the host name). */
	public static final String PROPERTY_NODE = "ebx.connectors.nodeId";
	/** JVM property: seconds a run's lease lasts after each renewal (default 1800, 60 to 3600). */
	public static final String PROPERTY_LEASE_SECONDS = "ebx.connectors.run.leaseSeconds";

	/** Longest owner text the {@code leaseOwner} field takes. */
	private static final int MAX_OWNER = 160;
	private static final long STARTED = ManagementFactory.getRuntimeMXBean().getStartTime();
	private static volatile String hostName;

	private NodeIdentity()
	{
	}

	/** This node: the property, else the host name. Never null, never contains {@code #}. */
	public static String node()
	{
		final String configured = System.getProperty(NodeIdentity.PROPERTY_NODE);
		final String name = configured != null && !configured.trim().isEmpty() ? configured.trim() : NodeIdentity.host();
		return name.replace('#', '_');
	}

	/** This incarnation of this node, as written in {@code leaseOwner}. */
	public static String owner()
	{
		final String owner = NodeIdentity.node() + "#" + NodeIdentity.STARTED;
		return owner.length() <= NodeIdentity.MAX_OWNER ? owner : owner.substring(owner.length() - NodeIdentity.MAX_OWNER);
	}

	/** The node part of an owner text ({@code host#1790000000000} gives {@code host}); null stays null. */
	public static String nodeOf(final String pOwner)
	{
		if (pOwner == null)
		{
			return null;
		}
		final int hash = pOwner.lastIndexOf('#');
		return hash < 0 ? pOwner : pOwner.substring(0, hash);
	}

	/** How long a lease lasts after a renewal. */
	public static long leaseMillis()
	{
		return Math.min(3600, BackgroundWork.intProperty(NodeIdentity.PROPERTY_LEASE_SECONDS, 1800, 60)) * 1000L;
	}

	/** The instant a lease taken or renewed at {@code pNow} runs out. */
	public static Date expiry(final Date pNow)
	{
		return new Date(pNow.getTime() + NodeIdentity.leaseMillis());
	}

	/**
	 * Whether a run found RUNNING at the start of node {@code pThisNode} can no longer be running: nobody claimed it (no owner,
	 * or no expiry: written before leases, or by a path that takes none), its lease has run out, or its owner is a previous
	 * incarnation of this very node. A run whose lease is alive and held by another node is left alone.
	 */
	public static boolean isAbandoned(final String pOwner, final Date pExpiresAt, final String pThisNode, final Date pNow)
	{
		if (pOwner == null || pOwner.trim().isEmpty() || pExpiresAt == null || pExpiresAt.before(pNow))
		{
			return true;
		}
		return pThisNode != null && pThisNode.equals(NodeIdentity.nodeOf(pOwner));
	}

	private static String host()
	{
		String name = NodeIdentity.hostName;
		if (name == null)
		{
			try
			{
				name = InetAddress.getLocalHost().getHostName();
			}
			catch (final Exception unknown)
			{
				name = System.getenv("HOSTNAME");
			}
			NodeIdentity.hostName = name = name == null || name.trim().isEmpty() ? "unknown-host" : name.trim();
		}
		return name;
	}
}
