package com.on.ebx.connect.core;

import java.util.Date;

/**
 * Audit C-44: which RUNNING runs a node that starts may declare dead. The decision is a pure function of the lease
 * the row carries; the writing of the lease (ConnectorRepository.saveRunState) and the reading at startup need a
 * repository and are not exercised here.
 */
public final class NodeIdentityTest
{
	private NodeIdentityTest() { }

	public static void main(final String[] pArgs)
	{
		System.clearProperty(NodeIdentity.PROPERTY_NODE);
		System.clearProperty(NodeIdentity.PROPERTY_LEASE_SECONDS);
		final Date now = new Date(1_800_000_000_000L);
		final Date later = new Date(now.getTime() + 60_000L);
		final Date earlier = new Date(now.getTime() - 1L);

		// identity: the property, else the host; the start time is the incarnation
		final String host = NodeIdentity.node();
		check(!host.isEmpty() && host.indexOf('#') < 0, "a node name exists and carries no separator");
		check(NodeIdentity.owner().startsWith(host + "#") && host.equals(NodeIdentity.nodeOf(NodeIdentity.owner())), "the owner is node#start and the node can be read back");
		check(NodeIdentity.owner().equals(NodeIdentity.owner()), "the owner of one incarnation is stable");
		System.setProperty(NodeIdentity.PROPERTY_NODE, "  ebx-a  ");
		check("ebx-a".equals(NodeIdentity.node()) && NodeIdentity.owner().startsWith("ebx-a#"), "the property names the node, trimmed");
		System.setProperty(NodeIdentity.PROPERTY_NODE, "we#ird");
		check(NodeIdentity.node().indexOf('#') < 0 && "we_ird".equals(NodeIdentity.nodeOf(NodeIdentity.owner())), "a separator in the name cannot confuse the owner");
		final StringBuilder longName = new StringBuilder();
		for (int i = 0; i < 400; i++) longName.append('n');
		System.setProperty(NodeIdentity.PROPERTY_NODE, longName.toString());
		check(NodeIdentity.owner().length() <= 160, "the owner fits the leaseOwner field");
		System.setProperty(NodeIdentity.PROPERTY_NODE, "  ");
		check(host.equals(NodeIdentity.node()), "a blank property falls back to the host");
		check(NodeIdentity.nodeOf(null) == null && "x".equals(NodeIdentity.nodeOf("x")) && "a#b".equals(NodeIdentity.nodeOf("a#b#1")), "reading a node back");
		System.clearProperty(NodeIdentity.PROPERTY_NODE);

		// the startup decision
		check(NodeIdentity.isAbandoned(null, null, "A", now), "a run with no lease (before leases existed) is interrupted, as before");
		check(NodeIdentity.isAbandoned("", later, "A", now) && NodeIdentity.isAbandoned("B#1", null, "A", now), "no owner or no expiry: nobody holds it");
		check(NodeIdentity.isAbandoned("B#1", earlier, "A", now), "an expired lease of another node is abandoned");
		check(NodeIdentity.isAbandoned("B#1", now, "A", now) == false, "a lease that runs out exactly now is still held");
		check(!NodeIdentity.isAbandoned("B#1", later, "A", now), "a live lease held by another node is left alone");
		check(NodeIdentity.isAbandoned("A#1", later, "A", now), "a live lease of an earlier incarnation of this node is dead with it");
		check(NodeIdentity.isAbandoned("A#2", later, "A", now) && NodeIdentity.isAbandoned("A#2", earlier, "A", now), "whatever its expiry");
		check(!NodeIdentity.isAbandoned("AB#1", later, "A", now) && !NodeIdentity.isAbandoned("A#1", later, "AB", now), "node names are compared whole");
		check(!NodeIdentity.isAbandoned("B#1", later, null, now), "an unknown own node claims nothing");

		// the lease length
		check(NodeIdentity.leaseMillis() == 1_800_000L, "default lease: 30 minutes");
		System.setProperty(NodeIdentity.PROPERTY_LEASE_SECONDS, "120");
		check(NodeIdentity.leaseMillis() == 120_000L && NodeIdentity.expiry(now).getTime() == now.getTime() + 120_000L, "the property sets it");
		System.setProperty(NodeIdentity.PROPERTY_LEASE_SECONDS, "5");
		check(NodeIdentity.leaseMillis() == 1_800_000L, "below the minimum of 60 s: the default");
		System.setProperty(NodeIdentity.PROPERTY_LEASE_SECONDS, "9999999");
		check(NodeIdentity.leaseMillis() == 3_600_000L, "capped at an hour, the longest lease the table documents");
		System.setProperty(NodeIdentity.PROPERTY_LEASE_SECONDS, "soon");
		check(NodeIdentity.leaseMillis() == 1_800_000L, "not a number: the default");
		System.clearProperty(NodeIdentity.PROPERTY_LEASE_SECONDS);
		System.out.println("PASS: a node that starts interrupts only the runs nobody holds, whose lease ran out, or that its earlier incarnation held");
	}

	private static void check(final boolean pPass, final String pMessage) { if (!pPass) throw new AssertionError(pMessage); }
}
