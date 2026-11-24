package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Session;

/**
 * What the documents connector and the conflict manager need from the EBX Digital Asset Manager add-on (DAMA):
 * open the asset held by a media field, read its content, read the tag vocabulary and the metadata types, write
 * metadata, add tags to a list field of the record that holds the asset, and keep low-confidence proposals as conflicts on
 * that record.
 * <p>
 * Tags go to the record, not to the asset: DAMA's public API cannot tag an existing asset ({@code DriveManager.update}
 * ignores {@code DigitalAsset.setTags}), and writing DAMA's own tables would bypass its permissions.
 * <p>
 * Everything runs under the given session: the record and its media field must be visible to it (read-write
 * to write the asset), and DAMA applies its own drive rights to that session. The production implementation
 * ({@link ReflectiveDamaAssets}) reaches the add-on by reflection, so the module loads and runs without it;
 * tests install a stub with {@link #use}.
 */
public interface DamaAssets
{
	/** Where an asset is: the record (or only the table) that holds the media field, and/or the asset UUID. */
	final class Ref
	{
		public final String dataspace;
		public final String dataset;
		public final String table;
		/** The record's primary key as EBX formats it ({@code a|b}), or null when only the UUID is given. */
		public final String record;
		/** The media field, relative to the record ({@code ./document}). */
		public final String field;
		/** The asset UUID when given directly, or null to take the one the record holds. */
		public final String uuid;

		public Ref(final String pDataspace, final String pDataset, final String pTable, final String pRecord, final String pField, final String pUuid)
		{
			this.dataspace = pDataspace;
			this.dataset = pDataset;
			this.table = pTable;
			this.record = pRecord;
			this.field = pField;
			this.uuid = pUuid;
		}

		public Ref withRecord(final String pRecord)
		{
			return new Ref(this.dataspace, this.dataset, this.table, pRecord, this.field, null);
		}
	}

	/** One asset, opened under a session. {@link #handle} is the implementation's own state (drive manager, record). */
	final class Asset
	{
		public final Ref ref;
		public final String uuid;
		public final String label;
		public final String extension;
		public final String type;
		/** Bytes, or null when DAMA does not say. */
		public final Long size;
		public final String modified;
		public final String recordLabel;
		/** Code to value, in DAMA's order. */
		public final Map<String, String> metadata;
		public final List<String> tags;
		public final Object handle;

		public Asset(final Ref pRef, final String pUuid, final String pLabel, final String pExtension, final String pType, final Long pSize,
			final String pModified, final String pRecordLabel, final Map<String, String> pMetadata, final List<String> pTags, final Object pHandle)
		{
			this.ref = pRef;
			this.uuid = pUuid;
			this.label = pLabel;
			this.extension = pExtension;
			this.type = pType;
			this.size = pSize;
			this.modified = pModified;
			this.recordLabel = pRecordLabel;
			this.metadata = Collections.unmodifiableMap(new LinkedHashMap<String, String>(pMetadata == null ? Collections.<String, String>emptyMap() : pMetadata));
			this.tags = Collections.unmodifiableList(new ArrayList<String>(pTags == null ? Collections.<String>emptyList() : pTags));
			this.handle = pHandle;
		}
	}

	/** One tag of the vocabulary: its code (what is written) and its label (what the model reads). */
	final class Tag
	{
		public final String code;
		public final String label;

		public Tag(final String pCode, final String pLabel)
		{
			this.code = pCode;
			this.label = pLabel;
		}
	}

	/** True when the add-on's API is on the class path. */
	boolean available();

	/**
	 * Opens the asset of {@code pRef} under {@code pSession}: the record (when named) must be visible and its media field not hidden, or
	 * read-write when {@code pForWrite}. With a UUID and no record, the field is checked on the dataset and the asset must belong to the
	 * field's drive.
	 */
	Asset open(Repository pRepository, Session pSession, Ref pRef, boolean pForWrite) throws ConnectorException;

	/** The content, refused above {@code pMaxBytes} (checked on the announced length and again while reading). */
	byte[] content(Asset pAsset, long pMaxBytes) throws ConnectorException;

	/**
	 * The tags an asset of this field may carry: the tags of the tag clouds of the field's digital asset component, or every tag when no
	 * tag cloud is attached to it. Read with the session; never created.
	 */
	List<Tag> vocabulary(Session pSession, Asset pAsset) throws ConnectorException;

	/** The metadata types DAMA knows: code to "may be entered by hand" ({@code IsManually}). */
	Map<String, Boolean> metadataTypes(Session pSession, Asset pAsset) throws ConnectorException;

	/**
	 * Writes {@code pMetadata} (code to value, replacing those codes and keeping the others) under the session the asset was opened with,
	 * then reads the asset again and fails when DAMA did not keep them.
	 */
	void update(Asset pAsset, Map<String, String> pMetadata) throws ConnectorException;

	/**
	 * The values of {@code pField}, a list field of the record that holds the asset, as XML Schema text. Refused when the asset was opened
	 * without a record, when the field is missing, not a list or hidden, or, with {@code pForWrite}, not read-write for the session the
	 * asset was opened with.
	 */
	List<String> recordList(Asset pAsset, String pField, boolean pForWrite) throws ConnectorException;

	/**
	 * Adds {@code pValues} to that list (the values already there are kept, none added twice), under the session the asset was opened
	 * with and with its permissions, then reads the record again and fails when a value is missing.
	 */
	void addToRecordList(Asset pAsset, String pField, List<String> pValues) throws ConnectorException;

	/** The records of {@code pTable} (table, field) visible to the session whose media field holds an asset, at most {@code pMax}. */
	List<Ref> records(Repository pRepository, Session pSession, Ref pTable, String pFilter, int pMax) throws ConnectorException;

	/**
	 * Keeps proposals on the asset as OPEN conflicts on the record that holds it ({@link DamaProposals} field paths).
	 *
	 * @return false when they could not be written; true when written or when there is no record to attach them to
	 */
	boolean propose(Session pSession, Asset pAsset, ConnectorConfig pConnector, List<MappingResult.Conflict> pConflicts);

	/** The implementation in use: the add-on by reflection, or the stub a test installed. */
	static DamaAssets get()
	{
		final DamaAssets installed = Holder.installed;
		return installed != null ? installed : ReflectiveDamaAssets.INSTANCE;
	}

	/** Tests: the implementation to use instead of the add-on (null puts the add-on back). */
	static void use(final DamaAssets pAssets)
	{
		Holder.installed = pAssets;
	}

	/** Holds the stub a test installed. */
	final class Holder
	{
		static volatile DamaAssets installed;

		private Holder()
		{
		}
	}
}
