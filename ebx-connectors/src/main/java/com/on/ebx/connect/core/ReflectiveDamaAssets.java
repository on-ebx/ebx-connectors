package com.on.ebx.connect.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorException;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.Request;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.ValueContextForUpdate;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.SessionPermissions;

/**
 * {@link DamaAssets} on the DAMA add-on, by reflection so that the module loads without it (the pattern of Layout Designer's
 * {@code Media}). Public API of the add-on ({@code ext}): {@code AssetManager.getDigitalAsset(uuid)}, {@code DriveContext(table,
 * mediaPath, session)}, {@code DriveFactoryProvider.getDriveFactory().getDriveManager(context)}, {@code DriveManager.getMediaContent /
 * getDigitalAsset / update}, {@code DigitalAsset.get/setMetaData, getTags}, {@code MetaData(code, value)}. {@code update} ignores
 * {@code setTags}: tags are added to a list field of the record instead ({@link #addToRecordList}). The tag vocabulary and the metadata types have no public API: they are read from the add-on's own dataset
 * ({@code APIConstants.CONFIGURATION.DAM_HOME / DAM_DATASET_NAME}, tables of {@code paths.DrivePaths}), with the caller's session.
 */
final class ReflectiveDamaAssets implements DamaAssets
{
	static final ReflectiveDamaAssets INSTANCE = new ReflectiveDamaAssets();

	private static final String EXT = "com.orchestranetworks.addon.dama.ext.";
	private static final String PATHS = "com.orchestranetworks.addon.dama.paths.DrivePaths$";

	/** What an opened asset keeps: the drive manager and context of its field, the asset bean, the record, the session. */
	private static final class Handle
	{
		final Object driveManager;
		final Object driveContext;
		final Object asset;
		final Adaptation record;
		final Repository repository;
		final Session session;

		Handle(final Object pDriveManager, final Object pDriveContext, final Object pAsset, final Adaptation pRecord, final Repository pRepository,
			final Session pSession)
		{
			this.driveManager = pDriveManager;
			this.driveContext = pDriveContext;
			this.asset = pAsset;
			this.record = pRecord;
			this.repository = pRepository;
			this.session = pSession;
		}
	}

	private ReflectiveDamaAssets()
	{
	}

	@Override
	public boolean available()
	{
		try
		{
			Class.forName(EXT + "AssetManager");
			Class.forName(EXT + "factory.DriveFactoryProvider");
			return true;
		}
		catch (final ClassNotFoundException | LinkageError ex)
		{
			return false;
		}
	}

	private void requireAddOn() throws ConnectorException
	{
		if (!this.available()) throw new ConnectorException("the EBX Digital Asset Manager add-on (DAMA) is not installed");
	}

	// ------------------------------------------------------------------ open

	@Override
	public Asset open(final Repository pRepository, final Session pSession, final Ref pRef, final boolean pForWrite) throws ConnectorException
	{
		this.requireAddOn();
		if (pSession == null) throw new ConnectorException("reading a DAMA document needs a user session");
		final SessionPermissions permissions = pSession.getPermissions();
		final AdaptationTable table = ReflectiveDamaAssets.table(pRepository, permissions, pRef);
		final SchemaNode field = ReflectiveDamaAssets.field(table, pRef);
		Adaptation record = null;
		String uuid = pRef.uuid;
		if (pRef.record != null)
		{
			try
			{
				record = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pRef.record));
			}
			catch (final RuntimeException ex)
			{
				record = null;
			}
			// a record the user cannot see is reported as missing, not as forbidden
			if (record == null || permissions.getAdaptationAccessPermission(record).isHidden())
				throw new ConnectorException("no record " + pRef.record + " in " + pRef.table);
			ReflectiveDamaAssets.requireField(permissions.getNodeAccessPermission(field, record), pRef, pForWrite);
			final String held = ReflectiveDamaAssets.attachment(record.get(field));
			if (held == null) throw new ConnectorException("record " + pRef.record + " has no document in " + pRef.field);
			if (uuid != null && !uuid.equals(held)) throw new ConnectorException("record " + pRef.record + " holds another asset than " + uuid);
			uuid = held;
		}
		else
		{
			if (uuid == null) throw new ConnectorException("no asset: give a record or an asset UUID");
			ReflectiveDamaAssets.requireField(permissions.getNodeAccessPermission(field, table.getContainerAdaptation()), pRef, pForWrite);
		}
		try
		{
			final Class<?> contextClass = Class.forName(EXT + "bean.DriveContext");
			final Object context = contextClass.getConstructor(AdaptationTable.class, Path.class, Session.class).newInstance(table, field.getPathInAdaptation(), pSession);
			final Class<?> factoryClass = Class.forName(EXT + "factory.DriveFactory");
			final Object factory = Class.forName(EXT + "factory.DriveFactoryProvider").getMethod("getDriveFactory").invoke(null);
			final Object driveManager = factoryClass.getMethod("getDriveManager", contextClass).invoke(factory, context);
			if (driveManager == null) throw new ConnectorException(pRef.field + " of " + pRef.table + " is not a DAMA media field (no drive)");
			final Class<?> managerClass = Class.forName(EXT + "AssetManager");
			final Object manager = managerClass.getConstructor(Repository.class).newInstance(pRepository);
			final Object found = managerClass.getMethod("getDigitalAsset", String.class).invoke(manager, uuid);
			if (found == null) throw new ConnectorException("no DAMA asset " + uuid);
			// read again through the field's drive: an asset of another drive is not reachable from this field
			final Object key = found.getClass().getMethod("getKey").invoke(found);
			final Object asset = Class.forName(EXT + "drivemanager.DriveManager").getMethod("getDigitalAsset", Class.forName(EXT + "bean.DigitalAssetKey")).invoke(driveManager, key);
			if (asset == null) throw new ConnectorException("asset " + uuid + " is not in the drive of " + pRef.field);
			final Handle handle = new Handle(driveManager, context, asset, record, pRepository, pSession);
			return ReflectiveDamaAssets.describe(pRef.withRecord(pRef.record), uuid, asset, record, pSession, handle);
		}
		catch (final ConnectorException ex)
		{
			throw ex;
		}
		catch (final Exception | LinkageError ex)
		{
			throw new ConnectorException("DAMA: " + ReflectiveDamaAssets.why(ex));
		}
	}

	private static AdaptationTable table(final Repository pRepository, final SessionPermissions pPermissions, final Ref pRef) throws ConnectorException
	{
		if (pRef.dataspace == null || pRef.dataset == null || pRef.table == null || pRef.field == null)
			throw new ConnectorException("a DAMA document needs dataspace, dataset, table and field (inputs, or properties dama.dataspace, dama.dataset, dama.table, dama.field)");
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(pRef.dataspace));
		if (home == null || pPermissions.getHomeAccessPermission(home).isHidden()) throw new ConnectorException("no dataspace " + pRef.dataspace);
		final Adaptation dataset = home.findAdaptationOrNull(AdaptationName.forName(pRef.dataset));
		if (dataset == null || pPermissions.getAdaptationAccessPermission(dataset).isHidden()) throw new ConnectorException("no dataset " + pRef.dataset + " in " + pRef.dataspace);
		final SchemaNode node;
		try
		{
			node = dataset.getSchemaNode().getNode(Path.parse(pRef.table));
		}
		catch (final RuntimeException ex)
		{
			throw new ConnectorException("table " + pRef.table + " is not a table path such as /root/Product");
		}
		if (node == null || !node.isTableNode() || pPermissions.getNodeAccessPermission(node, dataset).isHidden())
			throw new ConnectorException("no table " + pRef.table + " in " + pRef.dataset);
		return dataset.getTable(node.getPathInSchema());
	}

	private static SchemaNode field(final AdaptationTable pTable, final Ref pRef) throws ConnectorException
	{
		SchemaNode node = null;
		try
		{
			node = pTable.getTableOccurrenceRootNode().getNode(Path.parse(pRef.field.startsWith("./") || pRef.field.startsWith("/") ? pRef.field : "./" + pRef.field));
		}
		catch (final RuntimeException ex)
		{
			node = null;
		}
		if (node == null) throw new ConnectorException("no field " + pRef.field + " in " + pRef.table);
		return node;
	}

	private static void requireField(final AccessPermission pPermission, final Ref pRef, final boolean pForWrite) throws ConnectorException
	{
		if (pPermission.isHidden()) throw new ConnectorException("no field " + pRef.field + " in " + pRef.table);
		if (pForWrite && !pPermission.isReadWrite())
			throw new ConnectorException("changing the document's metadata or tags needs the right to change " + pRef.field + " of this record");
	}

	/** The UUID of a media value ({@code MediaTypeBean.getAttachment()}, on EBX 6.3 and on the add-on's legacy bean alike). */
	static String attachment(final Object pValue)
	{
		if (pValue == null) return null;
		try
		{
			final Object uuid = pValue.getClass().getMethod("getAttachment").invoke(pValue);
			return uuid == null || String.valueOf(uuid).trim().isEmpty() ? null : String.valueOf(uuid).trim();
		}
		catch (final Exception ex)
		{
			return null;
		}
	}

	private static Asset describe(final Ref pRef, final String pUuid, final Object pAsset, final Adaptation pRecord, final Session pSession, final Handle pHandle)
		throws Exception
	{
		final Locale locale = pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale();
		String label = null;
		final Object message = ReflectiveDamaAssets.call(pAsset, "getLabel");
		if (message != null)
		{
			try
			{
				label = message instanceof com.onwbp.base.text.UserMessage ? ((com.onwbp.base.text.UserMessage) message).formatMessage(locale) : String.valueOf(message);
			}
			catch (final Exception ex)
			{
				label = null;
			}
		}
		if (label == null || label.trim().isEmpty()) label = (String) ReflectiveDamaAssets.call(pAsset, "getPhysicalName");
		final Object size = ReflectiveDamaAssets.call(pAsset, "getFileSize");
		final Object updated = ReflectiveDamaAssets.call(pAsset, "getLastUpdatedDate");
		return new Asset(pRef, pUuid, label, (String) ReflectiveDamaAssets.call(pAsset, "getExtension"), (String) ReflectiveDamaAssets.call(pAsset, "getType"),
			size instanceof BigDecimal ? Long.valueOf(((BigDecimal) size).longValue()) : null,
			updated instanceof Date ? ((Date) updated).toInstant().toString() : null,
			pRecord == null ? null : pRecord.getLabel(locale), ReflectiveDamaAssets.metadataOf(pAsset), ReflectiveDamaAssets.tagsOf(pAsset), pHandle);
	}

	private static Map<String, String> metadataOf(final Object pAsset) throws Exception
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		final Object list = ReflectiveDamaAssets.call(pAsset, "getMetaData");
		if (list instanceof List)
		{
			for (final Object entry : (List<?>) list)
			{
				final Object code = ReflectiveDamaAssets.call(entry, "getCode");
				if (code != null) out.put(String.valueOf(code), (String) ReflectiveDamaAssets.call(entry, "getValue"));
			}
		}
		return out;
	}

	private static List<String> tagsOf(final Object pAsset) throws Exception
	{
		final List<String> out = new ArrayList<String>();
		final Object list = ReflectiveDamaAssets.call(pAsset, "getTags");
		if (list instanceof List)
		{
			for (final Object tag : (List<?>) list)
			{
				final Object code = ReflectiveDamaAssets.call(tag, "getCode");
				if (code != null) out.add(String.valueOf(code));
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ content

	@Override
	public byte[] content(final Asset pAsset, final long pMaxBytes) throws ConnectorException
	{
		final Handle handle = ReflectiveDamaAssets.handle(pAsset);
		if (pAsset.size != null && pAsset.size.longValue() > pMaxBytes)
			throw new ConnectorException(pAsset.label + " is " + pAsset.size + " bytes, above max.bytes " + pMaxBytes);
		Object media = null;
		try
		{
			final Class<?> mediaClass = Class.forName(EXT + "resource.MediaContent");
			media = Class.forName(EXT + "drivemanager.DriveManager").getMethod("getMediaContent", Class.forName(EXT + "bean.DigitalAsset")).invoke(handle.driveManager, handle.asset);
			if (media == null) throw new ConnectorException("DAMA gives no content for " + pAsset.label);
			final long length = ((Number) mediaClass.getMethod("getContentLength").invoke(media)).longValue();
			if (length > pMaxBytes) throw new ConnectorException(pAsset.label + " is " + length + " bytes, above max.bytes " + pMaxBytes);
			final InputStream in = (InputStream) mediaClass.getMethod("getInputStream").invoke(media);
			if (in == null) throw new ConnectorException("DAMA gives no content for " + pAsset.label);
			try
			{
				final ByteArrayOutputStream out = new ByteArrayOutputStream();
				final byte[] buffer = new byte[8192];
				int read;
				while ((read = in.read(buffer)) > 0)
				{
					out.write(buffer, 0, read);
					// the announced length may be missing or wrong: the cap holds on what is actually read
					if (out.size() > pMaxBytes) throw new ConnectorException(pAsset.label + " is above max.bytes " + pMaxBytes);
				}
				return out.toByteArray();
			}
			finally
			{
				in.close();
			}
		}
		catch (final ConnectorException ex)
		{
			throw ex;
		}
		catch (final Exception | LinkageError ex)
		{
			throw new ConnectorException("DAMA: cannot read " + pAsset.label + ": " + ReflectiveDamaAssets.why(ex));
		}
		finally
		{
			if (media instanceof java.io.Closeable)
			{
				try
				{
					((java.io.Closeable) media).close();
				}
				catch (final IOException | RuntimeException ex)
				{
					// already read or failed: nothing more to release
				}
			}
		}
	}

	// ------------------------------------------------------------------ vocabulary and metadata types

	@Override
	public List<Tag> vocabulary(final Session pSession, final Asset pAsset) throws ConnectorException
	{
		final Handle handle = ReflectiveDamaAssets.handle(pAsset);
		try
		{
			final Adaptation dam = ReflectiveDamaAssets.damDataset(handle.repository, pSession);
			final String component = (String) ReflectiveDamaAssets.call(handle.driveContext, "getDigitalAssetComponentId");
			final Set<String> clouds = new HashSet<String>();
			for (final Adaptation row : ReflectiveDamaAssets.rows(dam, "_Classification_TagCloudByDAC", pSession))
			{
				if (component != null && component.equals(row.getString(ReflectiveDamaAssets.path("_Classification_TagCloudByDAC", "_FKDigitalAssetComponent"))))
					clouds.add(row.getString(ReflectiveDamaAssets.path("_Classification_TagCloudByDAC", "_FKTagCloud")));
			}
			Set<String> allowed = null;
			if (!clouds.isEmpty())
			{
				allowed = new HashSet<String>();
				for (final Adaptation row : ReflectiveDamaAssets.rows(dam, "_Classification_TagByTagCloud", pSession))
				{
					if (clouds.contains(row.getString(ReflectiveDamaAssets.path("_Classification_TagByTagCloud", "_FKTagcloud"))))
						allowed.add(row.getString(ReflectiveDamaAssets.path("_Classification_TagByTagCloud", "_FKTag")));
				}
			}
			final Locale locale = pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale();
			final List<Tag> out = new ArrayList<Tag>();
			final Path code = ReflectiveDamaAssets.path("_Classification_Tag", "_Code");
			for (final Adaptation row : ReflectiveDamaAssets.rows(dam, "_Classification_Tag", pSession))
			{
				final String value = row.getString(code);
				if (value == null) continue;
				if (allowed != null && !allowed.contains(row.getOccurrencePrimaryKey().format()) && !allowed.contains(value)) continue;
				out.add(new Tag(value, row.getLabel(locale)));
			}
			return out;
		}
		catch (final ConnectorException ex)
		{
			throw ex;
		}
		catch (final Exception | LinkageError ex)
		{
			throw new ConnectorException("DAMA: cannot read the tag vocabulary: " + ReflectiveDamaAssets.why(ex));
		}
	}

	@Override
	public Map<String, Boolean> metadataTypes(final Session pSession, final Asset pAsset) throws ConnectorException
	{
		final Handle handle = ReflectiveDamaAssets.handle(pAsset);
		try
		{
			final Adaptation dam = ReflectiveDamaAssets.damDataset(handle.repository, pSession);
			final Path code = ReflectiveDamaAssets.path("_ReferenceData_MetaDataType", "_Code");
			final Path manual = ReflectiveDamaAssets.path("_ReferenceData_MetaDataType", "_IsManually");
			final Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
			for (final Adaptation row : ReflectiveDamaAssets.rows(dam, "_ReferenceData_MetaDataType", pSession))
			{
				final String value = row.getString(code);
				if (value != null) out.put(value, Boolean.valueOf(Boolean.TRUE.equals(row.get(manual))));
			}
			return out;
		}
		catch (final ConnectorException ex)
		{
			throw ex;
		}
		catch (final Exception | LinkageError ex)
		{
			throw new ConnectorException("DAMA: cannot read the metadata types: " + ReflectiveDamaAssets.why(ex));
		}
	}

	/** The add-on's own dataset, as the add-on names it ({@code APIConstants.CONFIGURATION}), visible to the session. */
	private static Adaptation damDataset(final Repository pRepository, final Session pSession) throws Exception
	{
		final Class<?> configuration = Class.forName("com.orchestranetworks.addon.dama.common.APIConstants$CONFIGURATION");
		final HomeKey homeKey = (HomeKey) configuration.getField("DAM_HOME").get(null);
		final Object reference = configuration.getField("DAM_DATASET_NAME").get(null);
		final String name = (String) reference.getClass().getMethod("getStringName").invoke(reference);
		final AdaptationHome home = pRepository.lookupHome(homeKey);
		final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(name));
		if (dataset == null || pSession.getPermissions().getAdaptationAccessPermission(dataset).isHidden())
			throw new ConnectorException("the DAMA configuration dataset (" + name + ") is not readable with this session");
		return dataset;
	}

	/** Every row of a DAMA table visible to the session (they are configuration tables: tens to a few thousand rows). */
	private static List<Adaptation> rows(final Adaptation pDataset, final String pTable, final Session pSession) throws Exception
	{
		final Path tablePath = (Path) Class.forName(PATHS + pTable).getMethod("getPathInSchema").invoke(null);
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final Request request = pDataset.getTable(tablePath).createRequest();
		request.setSession(pSession);
		final RequestResult result = request.execute();
		try
		{
			for (Adaptation row = result.nextAdaptation(); row != null; row = result.nextAdaptation()) out.add(row);
		}
		finally
		{
			result.close();
		}
		return out;
	}

	private static Path path(final String pTable, final String pField) throws Exception
	{
		return (Path) Class.forName(PATHS + pTable).getField(pField).get(null);
	}

	// ------------------------------------------------------------------ update

	@Override
	public void update(final Asset pAsset, final Map<String, String> pMetadata) throws ConnectorException
	{
		if (pMetadata.isEmpty()) return;
		final Handle handle = ReflectiveDamaAssets.handle(pAsset);
		try
		{
			final Class<?> manager = Class.forName(EXT + "drivemanager.DriveManager");
			final Object key = ReflectiveDamaAssets.call(handle.asset, "getKey");
			// the asset as it is now, so that a change made since it was opened is not overwritten
			final Class<?> keyClass = Class.forName(EXT + "bean.DigitalAssetKey");
			final Class<?> assetClass = Class.forName(EXT + "bean.DigitalAsset");
			final Object asset = manager.getMethod("getDigitalAsset", keyClass).invoke(handle.driveManager, key);
			if (asset == null) throw new ConnectorException("asset " + pAsset.uuid + " no longer exists");
			if (!pMetadata.isEmpty())
			{
				final Class<?> metaDataClass = Class.forName(EXT + "bean.MetaData");
				final List<Object> metadata = new ArrayList<Object>();
				final Set<String> done = new HashSet<String>();
				final Object current = ReflectiveDamaAssets.call(asset, "getMetaData");
				if (current instanceof List)
				{
					for (final Object entry : (List<?>) current)
					{
						final String code = (String) ReflectiveDamaAssets.call(entry, "getCode");
						if (code != null && pMetadata.containsKey(code))
						{
							metaDataClass.getMethod("setValue", String.class).invoke(entry, pMetadata.get(code));
							done.add(code);
						}
						metadata.add(entry);
					}
				}
				for (final Map.Entry<String, String> entry : pMetadata.entrySet())
				{
					if (!done.contains(entry.getKey())) metadata.add(metaDataClass.getConstructor(String.class, String.class).newInstance(entry.getKey(), entry.getValue()));
				}
				assetClass.getMethod("setMetaData", List.class).invoke(asset, metadata);
			}
			final Object status = manager.getMethod("update", assetClass).invoke(handle.driveManager, asset);
			if (status != null && Boolean.TRUE.equals(status.getClass().getMethod("hasFailed").invoke(status)))
			{
				final Object failure = status.getClass().getMethod("getException").invoke(status);
				throw new ConnectorException("DAMA refused the update of " + pAsset.label + ": " + (failure instanceof Throwable ? ReflectiveDamaAssets.why((Throwable) failure) : "no reason given"));
			}
			// update() says nothing about what it kept: read the asset again and say what is missing
			final Object written = manager.getMethod("getDigitalAsset", keyClass).invoke(handle.driveManager, key);
			final Map<String, String> metadata = written == null ? new LinkedHashMap<String, String>() : ReflectiveDamaAssets.metadataOf(written);
			final List<String> lost = new ArrayList<String>();
			for (final Map.Entry<String, String> entry : pMetadata.entrySet())
			{
				if (!String.valueOf(entry.getValue()).equals(String.valueOf(metadata.get(entry.getKey())))) lost.add("metadata " + entry.getKey());
			}
			if (!lost.isEmpty()) throw new ConnectorException("DAMA accepted the update of " + pAsset.label + " but did not keep " + lost);
		}
		catch (final ConnectorException ex)
		{
			throw ex;
		}
		catch (final Exception | LinkageError ex)
		{
			throw new ConnectorException("DAMA: cannot update " + pAsset.label + ": " + ReflectiveDamaAssets.why(ex));
		}
	}

	// ------------------------------------------------------------------ tags in a list field of the record

	@Override
	public List<String> recordList(final Asset pAsset, final String pField, final boolean pForWrite) throws ConnectorException
	{
		final Handle handle = ReflectiveDamaAssets.handle(pAsset);
		if (handle.record == null) throw new ConnectorException("tags are kept in " + pField + " of the record that holds the document: give a record, not only an asset");
		final Adaptation record = ReflectiveDamaAssets.fresh(handle.record, pAsset);
		final SchemaNode node = ReflectiveDamaAssets.listField(record, pField, handle.session, pForWrite);
		return ReflectiveDamaAssets.values(record, node);
	}

	@Override
	public void addToRecordList(final Asset pAsset, final String pField, final List<String> pValues) throws ConnectorException
	{
		if (pValues.isEmpty()) return;
		final Handle handle = ReflectiveDamaAssets.handle(pAsset);
		if (handle.record == null) throw new ConnectorException("tags are kept in " + pField + " of the record that holds the document: give a record, not only an asset");
		final Adaptation record = ReflectiveDamaAssets.fresh(handle.record, pAsset);
		final SchemaNode node = ReflectiveDamaAssets.listField(record, pField, handle.session, true);
		final ProgrammaticService service = ProgrammaticService.createForSession(handle.session, record.getHome());
		service.setSessionTrackingInfo("Connector documents: tags of " + pAsset.label);
		// the caller's session and permissions: no privilege is raised
		final ProcedureResult result = service.execute(pContext ->
		{
			final Adaptation now = record.getContainerTable().lookupAdaptationByPrimaryKey(record.getOccurrencePrimaryKey());
			final List<Object> list = new ArrayList<Object>();
			final List<String> present = new ArrayList<String>();
			final Object current = now == null ? null : now.get(node);
			if (current instanceof List)
			{
				for (final Object item : (List<?>) current)
				{
					list.add(item);
					present.add(node.formatToXsString(item));
				}
			}
			for (final String value : pValues)
			{
				if (!present.contains(value))
				{
					list.add(node.parseXsString(value));
					present.add(value);
				}
			}
			final ValueContextForUpdate update = pContext.getContext(now.getAdaptationName());
			update.setValue(list, node.getPathInAdaptation());
			pContext.doModifyContent(now, update);
		});
		if (result.hasFailed()) throw new ConnectorException("cannot add the tags to " + pField + " of record " + pAsset.ref.record + ": " + result.getExceptionFullMessage(Locale.ENGLISH));
		final List<String> kept = ReflectiveDamaAssets.values(ReflectiveDamaAssets.fresh(record, pAsset), node);
		final List<String> lost = new ArrayList<String>();
		for (final String value : pValues) if (!kept.contains(value)) lost.add(value);
		if (!lost.isEmpty()) throw new ConnectorException("record " + pAsset.ref.record + " did not keep " + lost + " in " + pField);
	}

	private static Adaptation fresh(final Adaptation pRecord, final Asset pAsset) throws ConnectorException
	{
		final Adaptation now = pRecord.getContainerTable().lookupAdaptationByPrimaryKey(pRecord.getOccurrencePrimaryKey());
		if (now == null) throw new ConnectorException("record " + pAsset.ref.record + " no longer exists");
		return now;
	}

	private static SchemaNode listField(final Adaptation pRecord, final String pField, final Session pSession, final boolean pForWrite) throws ConnectorException
	{
		SchemaNode node;
		try
		{
			node = pRecord.getSchemaNode().getNode(Path.parse(pField.startsWith("./") || pField.startsWith("/") ? pField : "./" + pField));
		}
		catch (final RuntimeException ex)
		{
			node = null;
		}
		final AccessPermission permission = node == null ? null : pSession.getPermissions().getNodeAccessPermission(node, pRecord);
		if (node == null || permission.isHidden()) throw new ConnectorException("no field " + pField + " in " + pRecord.getContainerTable().getTablePath().format());
		if (node.getMaxOccurs() <= 1) throw new ConnectorException(pField + " must be a list field (maxOccurs > 1) to keep the document's tags");
		if (pForWrite && !permission.isReadWrite()) throw new ConnectorException("adding the document's tags needs the right to change " + pField + " of this record");
		return node;
	}

	private static List<String> values(final Adaptation pRecord, final SchemaNode pNode)
	{
		final List<String> out = new ArrayList<String>();
		final Object current = pRecord.get(pNode);
		if (current instanceof List)
		{
			for (final Object item : (List<?>) current) if (item != null) out.add(pNode.formatToXsString(item));
		}
		return out;
	}

	// ------------------------------------------------------------------ records and proposals

	@Override
	public List<Ref> records(final Repository pRepository, final Session pSession, final Ref pTable, final String pFilter, final int pMax) throws ConnectorException
	{
		this.requireAddOn();
		if (pSession == null) throw new ConnectorException("reading DAMA documents needs a user session");
		final SessionPermissions permissions = pSession.getPermissions();
		final AdaptationTable table = ReflectiveDamaAssets.table(pRepository, permissions, pTable);
		final SchemaNode field = ReflectiveDamaAssets.field(table, pTable);
		final List<Ref> out = new ArrayList<Ref>();
		final Request request = table.createRequest();
		request.setSession(pSession);
		if (pFilter != null) request.setXPathFilter(pFilter);
		final RequestResult result;
		try
		{
			result = request.execute();
		}
		catch (final RuntimeException ex)
		{
			throw new ConnectorException("filter: " + ex.getMessage());
		}
		try
		{
			for (Adaptation row = result.nextAdaptation(); row != null && out.size() < pMax; row = result.nextAdaptation())
			{
				if (permissions.getNodeAccessPermission(field, row).isHidden() || ReflectiveDamaAssets.attachment(row.get(field)) == null) continue;
				out.add(pTable.withRecord(row.getOccurrencePrimaryKey().format()));
			}
		}
		finally
		{
			result.close();
		}
		return out;
	}

	@Override
	public boolean propose(final Session pSession, final Asset pAsset, final ConnectorConfig pConnector, final List<MappingResult.Conflict> pConflicts)
	{
		final Handle handle = (Handle) pAsset.handle;
		if (handle == null || handle.record == null || pConflicts.isEmpty()) return true;
		return ConnectorService.get().repository(handle.repository).logConflicts(pSession, handle.record, null, pConnector, pConflicts);
	}

	// ------------------------------------------------------------------ reflection

	private static Handle handle(final Asset pAsset) throws ConnectorException
	{
		if (!(pAsset.handle instanceof Handle)) throw new ConnectorException("asset " + pAsset.uuid + " was not opened through DAMA");
		return (Handle) pAsset.handle;
	}

	private static Object call(final Object pTarget, final String pMethod) throws Exception
	{
		if (pTarget == null) return null;
		final Method method = pTarget.getClass().getMethod(pMethod);
		return method.invoke(pTarget);
	}

	private static String why(final Throwable pFailure)
	{
		Throwable cause = pFailure;
		while (cause instanceof InvocationTargetException && cause.getCause() != null) cause = cause.getCause();
		final String message = cause.getMessage();
		return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}
}
