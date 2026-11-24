# Documents connector and DAMA: read an asset, write metadata and tags back

> **Closed by default (decision of 2026-11-12).** Reading and annotating DAMA assets is part of the `documents` function: it runs only when `documents` is in the JVM property `-Debx.connectors.preview` (docs/OPERATIONS.md, "Preview functions").

Status, 2026-11-03: **implemented and tested offline (`DamaDocumentsTest`); run on the FT 6.3 RC (A4): metadata are kept by DAMA, tags are not, so tags now go to a list field of the record (§3).** The design questions of
the first version of this note were answered with the proposed defaults (§7).

The `documents` connector type ([DOCUMENTS.md](DOCUMENTS.md)) can read a document stored in the EBX Digital Asset
Manager add-on (DAMA). It can also write what it extracts back to the asset itself, as DAMA **metadata** and **tags**,
besides the record fields it already fills through a mapping.

## 1. The DAMA API used

Sources: `ebx-addons.jar` of the FT 6.3 RC (javap) and the add-on Javadoc of
`TIB_ebx-addon_6.3.0.6-RC4/lib/ebx-addons-javadoc.zip` (its footer says 6.2.2; the signatures match the RC jar).
Everything goes through one reflective class, `core/ReflectiveDamaAssets`, so the module still loads and runs without the
add-on. A DAMA input then fails with "the EBX Digital Asset Manager add-on (DAMA) is not installed".

| Need | API | Notes |
|---|---|---|
| UUID held by a record | `getAttachment()` of the media value | `MediaTypeBean` on EBX 6.3, the add-on's `MediaType` before it: both have the method, called by reflection |
| Drive manager | `DriveFactoryProvider.getDriveFactory().getDriveManager(new DriveContext(table, mediaFieldPath, session))` | Bound to a media field and a session. So a UUID alone needs a table and a field too |
| Asset | `AssetManager(repository).getDigitalAsset(uuid)`, then `DriveManager.getDigitalAsset(key)` | Read again through the field's drive: an asset of another drive is refused |
| Content | `DriveManager.getMediaContent(asset)` → `getContentLength()`, `getInputStream()` | For S3, Azure Blob, Google Drive or SharePoint drives, DAMA fetches the file itself, outside this module's egress rules |
| Write metadata | `setMetaData(List<MetaData>)`, `DriveManager.update(asset)` → `OperationExecutionStatus` | `update` does not say what it kept. The module **reads the asset back** and fails, naming what is missing |
| Tags | none | `update` ignores `setTags` (RC run of 2026-11-03; in the add-on, `UpdateDigitalAssetProcedure` writes label, description, state, version, size and metadata only). Only DAMA's REST `edit` tags an asset, and it replaces all tags, creates unknown ones and writes DAMA's tables with all privileges. So tags go to a list field of the record (§3) |
| Tag vocabulary | the add-on's dataset (`APIConstants.CONFIGURATION.DAM_HOME` / `DAM_DATASET_NAME`, both `ebx-addon-dama`), tables of `paths.DrivePaths`: `Classification/TagCloudByDAC` → `TagByTagCloud` → `Tag` | No public API. The field's component id comes from `DriveContext.getDigitalAssetComponentId()` |
| Metadata types | same dataset, `ReferenceData/MetaDataType` (`Code`, `IsManually`) | No public API |

## 2. Reading a DAMA document (`text`, `extract`, `extractAll`)

| Input (default property) | Meaning |
|---|---|
| `dataspace` (`dama.dataspace`), `dataset` (`dama.dataset`), `table` (`dama.table`), `field` (`dama.field`) | The table (`/root/Invoice`) and its media field (`./document`) |
| `record` | The primary key of the record that holds the asset (composite: `a\|b`) |
| `asset` | The asset UUID directly. The table and field are still needed, to reach the drive |

Priority: `content` > `asset` / `record` > `path` > the uploaded document.

All of it runs under the **caller's session**. A scheduled run uses its owner's worker session, as before.

- The dataspace, dataset and table must be visible. A record that is missing or hidden from the user gives the same
  message, "no record". The media field must not be hidden for that record. With `asset` and no record, the field is
  checked against the dataset.
- DAMA applies its own drive rights to the session.
- An asset whose type is not a text document (an image, for example) is refused before it is read, with "no OCR and no
  vision". `max.bytes` is checked against the size DAMA announces, then again while reading.

The item gains `asset` (UUID), `assetLabel`, and, when a record was named, `dataspace`, `dataset`, `table`, `record` and
`field`. A mapping's `keyPredicate` can use `record` to write the attributes into the record that holds the document.

`extractAll` reads a DAMA table when the input `source` is `dama`, or when `source` is not given, a DAMA field is named
and the connector has no `folder`. It returns one item per visible record whose field holds an asset (optional XPath
`filter`, at most `max` / `max.files`). A record that cannot be read goes to `errors` with its key.

## 3. `annotate`: metadata and tags on the asset

`annotate` is a `write` operation (so it needs `allowWrite`). It takes the inputs of `extract`, plus:

| Input (property) | Default | Meaning |
|---|---|---|
| `metadata` (`dama.metadata`) | | `attribute:CODE, ...`. Each attribute must be one of `fields`. Each code must be a DAMA metadata type with `IsManually = true`; otherwise the call is refused before the model is asked |
| `tags` (`dama.tags`) | none | `true` (5 tags), `max:N` (at most N, up to 50), `false` |
| `policy` (`metadata.policy`) | `IF_EMPTY` | `IF_EMPTY` never replaces a value already on the asset (reported in `kept`); `ALWAYS` replaces it |
| `dryRun` | `false` | compute everything, write nothing, propose nothing |
| `tagsField` (`dama.tags.field`) | none | a list field of the record where the tags are added (`./documentTags`); without it the tags are only returned |
| (`dama.tags.vocabulary.max`) | 300 | a larger vocabulary is refused, not cut |

Tags:

- **Where they go:** to `dama.tags.field` (input `tagsField`), a list field (`maxOccurs > 1`, string or foreign key) of the
  record that holds the document, for example `./documentTags`. They are written with the caller's session, which needs
  that field read-write, and the record is read back. DAMA's public API cannot tag an existing asset, and writing
  DAMA's own tables would bypass its permissions (the product owner, 2026-11-03). Without `dama.tags.field`, the tags are only
  returned, with a `tagsNote`. They are not shown in DAMA's editor or search, only in EBX. When DAMA offers a public way,
  the asset can be tagged too and the field becomes an optional copy.
- The vocabulary is the tags of the tag clouds attached to the field's digital asset component. When no tag cloud is
  attached, it is every tag of DAMA. It is sent to the model as a closed list (`<tags>` block, code and label), and the
  model answers each tag with a confidence and a snippet.
- A code that is not in the vocabulary is dropped and returned in `rejectedTags`. **No tag is ever created.** An empty
  vocabulary refuses the call.
- Tags are only added. The field's existing values are kept, none added twice. A record is required (not `asset` alone).
- A snippet not found in the document caps the confidence at 0.5, as for attributes.

Metadata values are written as text: a number in plain notation, a date as the model gives it (`yyyy-MM-dd`).

The answer is the item, plus `written: {metadata, tags}`, `pending` (each with kind, code, value, confidence and
source), `kept`, `tags` (the chosen ones), `rejectedTags`, `overMaxTags` and `proposedTo`. A document with no text calls
no model and writes nothing.

### 3.1 Below the threshold: `dama:` proposals in the conflict manager

A value or tag below `confidence.threshold` is not written (a model's confidence is at most 0.99, so threshold 1 proposes
everything). A tag becomes an ordinary proposal on the record's tags field (`fieldPath` = `dama.tags.field`, proposed value =
the tag code): accepting it **adds** that code to the list, as any proposal on a list field now does. A metadata value
becomes an OPEN row of the **Conflict** table on the record that holds the asset:

| Column | Value |
|---|---|
| `dataspace`, `dataset`, `tablePath`, `recordKey`, `recordLabel` | the record that holds the media field |
| `fieldPath` | `dama:<field>/<asset UUID>/metadata/<CODE>` (the screen shows `document (DAMA metadata): TOTAL`). A `…/tag/<CODE>` path left by an earlier version cannot be accepted: reject it |
| `currentValue` / `proposedValue` | the asset's current value (empty for a tag) / the extracted value or tag code |
| `comment` | `confidence 0.62 < 0.80 - invoice.pdf p.2: "…"`, as for record fields |
| `proposedConnector`, `proposedTrust` | the documents connection |

- **Accept** (`ConflictResolver.acceptOnAsset`) opens the asset again from the record **with the steward's session**.
  That checks the record is visible, the media field is read-write for the steward, and DAMA's rights. The asset must
  still be the one of the proposal: if the record now holds another UUID, accepting is refused ("reject it"). Then it
  writes the value or adds the tag, with the same claim / write / close steps as a record field. A failed DAMA write
  gives the claim back.
- **Reject** writes nothing.
- `FieldProvenance` is per record field: the source of an accepted asset value stays in the conflict row.

Without a record (input `asset` only), the values above the threshold are written and the rest is only returned in
`pending`, with `proposedTo: none`.

## 4. Permissions

- Reading: the dataspace, dataset, table and record visible to the caller; the media field not hidden. DAMA applies
  its drive rights.
- Writing to the asset (`annotate`, and the steward's accept): the media field must be **read-write** for that user on
  that record (on the dataset when only a UUID is given), and DAMA's own rights apply too.
- The tag vocabulary and the metadata types are read from DAMA's dataset with the caller's session. If that session
  cannot see the dataset, `annotate` is refused.

## 5. Limits

- **Tags are on the record, not on the asset**, until DAMA offers a public way to tag an asset (§1).
- **No OCR, no vision.** Images and scanned PDFs give no text. An image asset is refused, and a PDF with no text layer
  gives the existing `note`, with nothing written. Tagging images would need a multimodal model (an image input in
  the LLM connector): a separate topic.
- Size: `max.bytes` (20 MB, ceiling 50 MB), `max.files`, `max.chars`, as in DOCUMENTS.md.
- The tag vocabulary and the metadata types come from the add-on's internal tables, not from its public `ext` API. A
  later DAMA version may move them. They are read in one place (`ReflectiveDamaAssets.vocabulary / metadataTypes`), and
  a missing table gives "DAMA: cannot read the tag vocabulary", not a crash.
- The module only adds tags and sets metadata. It never removes either.

## 6. Tests

- Offline, `DamaDocumentsTest` (in `build-test.sh`), with a stub of DAMA:
  - reading: by record and by UUID, with the caller's session; a missing or hidden record; an image refused; the size
    cap; no session; an upload that never touches DAMA; `extractAll` over a table with one unreadable record;
  - `annotate`, refusals: nothing asked; no DAMA document; an attribute not in `fields`; an unknown or computed metadata
    type; a read-only field; an empty or too large vocabulary;
  - `annotate`, results: the sure value written and the sure tag added; the existing tag kept; a code outside the
    vocabulary rejected and never created; the closed list in the prompt; `dama:` proposals with their comment;
    `IF_EMPTY` / `ALWAYS`; dry run; UUID only; a failed update leaves no proposal; a document without text calls no
    model;
  - proposals: the `dama:` path round trip and its display; accepting writes with the session the asset was opened
    with; a changed UUID is refused.
- Not tested offline: `ReflectiveDamaAssets` itself (it needs a live DAMA), and `ConflictResolver.acceptOnAsset` (it
  needs the Conflict table). RC scenario A4 covers both.

### Still to verify on a live DAMA

1. ~~That `DriveManager.update` keeps metadata and tags.~~ RC, 2026-11-03: it keeps metadata, never tags (see §1). Tags
   now go to `dama.tags.field`.
2. That the drive manager applies DAMA's rights to the session it was built with (try a user without rights on the
   drive).
3. The foreign keys of the tag tables: the vocabulary matches tags by primary key or by code, and clouds by the
   component id that `DriveContext` gives.
4. `DriveContext` with the field path relative to the record (`/document`), as the Javadoc example shows.

## 7. Decisions (defaults validated by the product owner on 2026-11-01)

1. Reading and `annotate` in the same delivery.
2. `annotate` with `asset` only: values above the threshold are written, the rest is returned, not proposed.
3. Writing needs DAMA's rights **and** a read-write media field. Reading needs the field visible, plus DAMA's rights.
4. Vocabulary: the tag clouds of the field's component, or all tags when it has none; ceiling 300.
5. Metadata: only types with `IsManually = true`; default policy `IF_EMPTY`.
6. No EBX 6.3-only linking: reflection everywhere, so the module still runs on 6.2.
7. RC: no data model change. Scenario A4 uses a table and record that the operator names, and writes only with
   `RC_DAMA_WRITE=1`.
8. All drive types are allowed.
9. Reading the tag vocabulary and the metadata types from DAMA's own dataset (§5) is accepted as an exception to the
   "public EBX APIs only" rule (the product owner, 2026-11-02): DAMA's `ext` API has no other way to list them, and without
   them "never create a tag" cannot be guaranteed. Recheck those table paths at each DAMA upgrade.

[Return to the documents connector](DOCUMENTS.md)
