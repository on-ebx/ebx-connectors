package com.on.ebx.connect.core;

import java.util.Collections;

import com.on.ebx.connect.spi.ConnectorException;

/**
 * Proposals on a DAMA asset kept in the Conflict table (documents connector, operation {@code annotate}). The conflict is on the
 * record that holds the asset, and its field path names the asset and what is proposed:
 * {@code dama:<media field>/<asset UUID>/metadata/<CODE>} or {@code dama:<media field>/<asset UUID>/tag/<CODE>}. Accepting it
 * writes the metadata value through DAMA, under the steward's session; the UUID makes sure it is still the same document. A
 * {@code tag} path can only come from an earlier version: accepting it is refused, since DAMA's public API does not keep tags; tags
 * are now proposed on a list field of the record ({@code dama.tags.field}).
 */
public final class DamaProposals
{
	public static final String PREFIX = "dama:";
	public static final String METADATA = "metadata";
	public static final String TAG = "tag";

	/** What a {@code dama:} field path proposes. */
	public static final class Target
	{
		public final String field;
		public final String uuid;
		/** {@link #METADATA} or {@link #TAG}. */
		public final String kind;
		public final String code;

		Target(final String pField, final String pUuid, final String pKind, final String pCode)
		{
			this.field = pField;
			this.uuid = pUuid;
			this.kind = pKind;
			this.code = pCode;
		}
	}

	private DamaProposals()
	{
	}

	public static boolean isAssetPath(final String pFieldPath)
	{
		return pFieldPath != null && pFieldPath.startsWith(PREFIX);
	}

	/** The field path of a proposal; codes and UUIDs never contain {@code /}. */
	public static String path(final String pField, final String pUuid, final String pKind, final String pCode)
	{
		if (pCode == null || pCode.isEmpty() || pCode.contains("/") || pUuid == null || pUuid.contains("/"))
			throw new IllegalArgumentException("a DAMA code or asset UUID cannot contain '/': " + pCode);
		return PREFIX + pField + "/" + pUuid + "/" + pKind + "/" + pCode;
	}

	/** The target of a {@code dama:} field path, or null when it is not one. */
	public static Target parse(final String pFieldPath)
	{
		if (!DamaProposals.isAssetPath(pFieldPath)) return null;
		final String rest = pFieldPath.substring(PREFIX.length());
		final int codeAt = rest.lastIndexOf('/');
		final int kindAt = codeAt <= 0 ? -1 : rest.lastIndexOf('/', codeAt - 1);
		final int uuidAt = kindAt <= 0 ? -1 : rest.lastIndexOf('/', kindAt - 1);
		if (uuidAt <= 0) return null;
		final String kind = rest.substring(kindAt + 1, codeAt);
		if (!METADATA.equals(kind) && !TAG.equals(kind)) return null;
		return new Target(rest.substring(0, uuidAt), rest.substring(uuidAt + 1, kindAt), kind, rest.substring(codeAt + 1));
	}

	/** The steward comment, the same as a record field proposal: {@code confidence 0.62 < 0.80 - invoice.pdf p.2: "..."}. */
	public static String comment(final double pConfidence, final double pThreshold, final String pSource)
	{
		return MappingEngine.proposalComment(pConfidence, pThreshold, pSource);
	}

	/** True when the asset already holds what is proposed (the metadata value, or the tag). */
	public static boolean holds(final DamaAssets.Asset pAsset, final Target pTarget, final String pProposed)
	{
		if (TAG.equals(pTarget.kind)) return pAsset.tags.contains(pTarget.code);
		final String current = pAsset.metadata.get(pTarget.code);
		return (current == null ? "" : current).equals(pProposed == null ? "" : pProposed);
	}

	/** Writes the proposal on the asset, under the session it was opened with. */
	public static void apply(final DamaAssets pAssets, final DamaAssets.Asset pAsset, final Target pTarget, final String pProposed) throws ConnectorException
	{
		if (!pTarget.uuid.equals(pAsset.uuid))
			throw new ConnectorException("the document of this record changed since the proposal (asset " + pTarget.uuid + ", now " + pAsset.uuid + "): reject it");
		// an asset's tag can only be proposed by an earlier version: DAMA's public API does not keep it
		if (TAG.equals(pTarget.kind))
			throw new ConnectorException("DAMA's public API cannot add a tag to an asset: reject this proposal; tags are now kept in a list field of the record (dama.tags.field)");
		pAssets.update(pAsset, Collections.singletonMap(pTarget.code, pProposed));
	}

	/** The field path text a steward reads: {@code document: metadata INVOICE_NO} or {@code document: tag INVOICE}. */
	public static String display(final String pFieldPath)
	{
		final Target target = DamaProposals.parse(pFieldPath);
		if (target == null) return pFieldPath;
		final String field = target.field.startsWith("./") ? target.field.substring(2) : target.field;
		return field + " (DAMA " + target.kind + "): " + target.code;
	}
}
