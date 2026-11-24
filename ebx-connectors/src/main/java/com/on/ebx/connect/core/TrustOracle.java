package com.on.ebx.connect.core;

import com.onwbp.adaptation.Adaptation;

/** Who wrote a field of a record, and how much that source is trusted (null: unknown, i.e. manual). */
public interface TrustOracle
{
	/** Trust of a manual entry, or of any value whose provenance is unknown. */
	int MANUAL_TRUST = 100;

	Provenance provenanceOf(Adaptation pRecord, String pFieldPath);

	final class Provenance
	{
		public final String connector;
		public final int trust;

		public Provenance(final String pConnector, final int pTrust)
		{
			this.connector = pConnector;
			this.trust = pTrust;
		}
	}
}
