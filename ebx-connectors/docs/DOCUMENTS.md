# Document feed: attributes from PDF, DOCX and text documents

> **Closed by default (decision of 2026-11-12).** The `documents` connection type runs only when the server administrator adds `documents` to the JVM property `-Debx.connectors.preview` (docs/OPERATIONS.md, "Preview functions"). The seeded "Documents" connection exists only then, and has no language model (`llm.connector` is chosen by a person).

The `documents` connector type turns unstructured documents (invoices, certificates, contracts, supplier
forms) into records. It reads the text of each document, asks the module's LLM connector for the
attributes of the target table, and returns one item per document. A **normal inbound mapping** imports
that item. Each attribute comes with a **confidence** and the **words of the document that state it**.
Values below a threshold are not written: they become **proposals** in the conflict manager. The document,
page and snippet are kept in `FieldProvenance.source`.

```
 document (PDF / DOCX / text)
        │  DocumentText / PdfText: text, page by page (no OCR)
        ▼
 LLM connector (JSON mode): {fields: {name: {value, confidence, page, snippet}}}
        │  DocumentExtraction: snippet checked against the text, page recomputed, confidence capped if not found
        ▼
 item: {document, pages, <attributes>, _confidence, _minConfidence, _sources, _evidence, _proposed}
        │  inbound mapping (rules on the attributes, keyPredicate, createIfMissing...)
        ▼
 MappingEngine: confidence ≥ threshold → write + FieldProvenance.source
                confidence <  threshold → Conflict (OPEN) with the comment "confidence 0.62 < 0.80 - invoice.pdf p.2: "…""
```

## Set it up

1. **An LLM connector** (`llm` type), for example Claude, with a low trust (30 is the seeded default). Its
   egress and secret rules apply to the document text it receives. Set `maxTokens` to 4096 or more, because
   an extraction answer carries one snippet per attribute.
2. **A connector of type `documents`**:

   | Property | Default | Meaning |
   |---|---|---|
   | `llm.connector` | (required for `extract`) | id of the LLM connector |
   | `fields` | | default attributes: `name:description:type`, separated by commas or new lines, or a JSON array of `{name, description, type}` |
   | `confidence.threshold` | `0.8` | below it, a value is proposed, not written; at or above it, written. A model's confidence never goes above 0.99, so `1` sends everything to a steward |
   | `folder` | | absolute server folder to read (see "Server folders") |
   | `max.bytes` | 20 MB (ceiling 50 MB) | largest document read |
   | `max.files` | 100 (ceiling 1000) | documents per `list` / `extractAll` |
   | `max.chars` | 60000 | text sent to the model; a longer document is cut and its item says so in `note` |
   | `language` | | language of the text values the model answers with |
   | `vision` | `false` | send images (png, jpeg, gif, webp) and PDFs without text layer to the model; the LLM connector must declare `vision=true` too. See [DOCUMENTS-VISION.md](DOCUMENTS-VISION.md) |
   | `vision.confidence.max` | `0.7` | highest confidence of a value read from an image or a scanned PDF (nothing can check it): below the threshold, so it is proposed |
   | `max.image.bytes` | 5 MB (ceiling 20 MB) | largest image sent; a larger one is refused, not resized |
   | `max.pages` | 20 (ceiling 100) | most pages of a PDF without text layer sent to the model |

   A document uploaded on the connector row (`sampleData`, as for the sample-file connector) is the
   document of a call that names none. Use it to design the mapping before any folder exists.
3. **An inbound mapping** on operation `extract` (one document) or `extractAll` (a folder, with
   `itemsPath = items`), with one rule per attribute (`invoiceNumber → ./number`...) and the usual
   `keyPredicate` / `createIfMissing`. The rule policy still applies to values above the threshold
   (`IF_EMPTY`, `ALWAYS`, `TRUSTED`). A value below the threshold is proposed whatever the policy.
   `document`, `pages`, `format` and `modified` are plain item fields a rule may also map.

## Operations

| Operation | Kind | Inputs | Returns |
|---|---|---|---|
| `list` | search | `pattern` (`*.pdf`), `max` | `{items: [{name, format, size, modified}], count}` |
| `text` | read | `path`, or `content` (base64) + `name`, or a DAMA document (`record` or `asset`), or none (uploaded document) | `{document, format, pages: [{page, text}], emptyPages, chars}` |
| `extract` | read | as `text`, plus `fields`, `threshold` | one item (below) |
| `extractAll` | search | `pattern`, `max`, `fields`, `threshold` | `{items: [...], errors: [{document, error}], count}`. A document that cannot be read is reported and the others are still extracted |
| `annotate` | write | a DAMA document (`record` or `asset`), `metadata`, `tags`, `tagsField`, `policy`, `dryRun` | the item, plus the metadata written on the asset, the tags added to the record's `dama.tags.field`, and what waits for a steward ([DAMA-DOCUMENTS.md](DAMA-DOCUMENTS.md)) |

An item:

```json
{
  "document": "invoice-42.pdf", "pages": 3, "format": "pdf", "modified": "2026-10-12T08:00:00Z",
  "invoiceNumber": "INV-2026-0042", "total": 1234.5,
  "_confidence": {"invoiceNumber": 0.97, "total": 0.6},
  "_minConfidence": 0.8,
  "_sources": {"invoiceNumber": "invoice-42.pdf p.1: \"Invoice n° INV-2026-0042\"", "total": "invoice-42.pdf p.2: \"Total: 1 234,50 EUR\""},
  "_evidence": {"invoiceNumber": {"page": 1, "snippet": "…", "verified": true, "confidence": 0.97}},
  "_proposed": ["total"]
}
```

## The confidence envelope (read only while `documents` is open)

Since 1.4.2 the envelope is a capability of the `documents` preview function: it is read only while the word `documents` is in `-Debx.connectors.preview`; closed, a document that carries `_minConfidence` is treated as before November
(see [CONTRACT.md](CONTRACT.md), "Reserved names"). The engine rule itself does not look at the connector type. When the document a mapping reads (at its response root) carries a numeric
`_minConfidence`:

- a rule whose **source path** has a `_confidence` below the threshold, or no confidence at all, writes
  nothing. It logs an OPEN conflict with the proposed value, and the conflict's comment says why. This also
  happens on a record being created: the record is created without that field and the proposal waits for
  the steward;
- a rule at or above the threshold writes as usual. The text in `_sources` for that source path is stored
  in the new `FieldProvenance.source` column (2000 characters);
- accepting such a proposal in the conflict manager writes the value. The detection comment (confidence,
  document, page, snippet) becomes the provenance source of the accepted value.

A document without `_minConfidence` behaves exactly as before. `FieldProvenance.source` is an optional
column: a repository whose model predates it keeps working, and the column is simply not written there.

## Why the snippet is checked

A model can quote words that are not in the document. Every snippet is looked up in the extracted text,
ignoring case, accents, quotes and white space:

- found: the page is the page where it was found, not the page the model gave;
- not found: the confidence is capped at **0.5** and the provenance says "snippet not found in the
  document". With the default threshold such a value can never be written without a steward.

Even with its snippet found, a value keeps at most **0.99**: a model's own "1.0" is not a proof. The rule stays
"at or above the threshold writes", and threshold `1` means that every value the model gives waits for a steward.

The document text goes to the model between `<document>` tags, and the system instructions say that it is
data and never instructions. That limits prompt injection from a document but cannot rule it out. That is
one more reason to keep the LLM connector's trust low and the threshold high.

## Server folders

No server folder is read until the operator lists the allowed roots:

```
-Debx.connectors.documents.roots=/data/ebx/inbox;/mnt/supplier-docs
```

`folder` must be absolute and inside one of them; it is checked when the connector is saved and again at
every call. `path` is a file name of the folder, never a path. Hidden files, sub-folders and symbolic
links are skipped by `list` and refused by `path`. Without the property, only uploads (`content`, or the
document uploaded on the connector) are read. The connector opens no network connection itself.

## What is read, what is not

| Format | Read |
|---|---|
| PDF | the text layer of born-digital PDFs: page tree, object streams (PDF 1.5+), FlateDecode, simple fonts (WinAnsi, MacRoman, `/Differences`) and composite fonts with a `/ToUnicode` map. Reading order is the content-stream order |
| DOCX | the main body through the module's bounded DOCX codec (external links, macros and active fields refused). A DOCX has no page numbers: its text is page 1 |
| txt, md, csv | UTF-8 (BOM dropped). A form feed is a page break |
| html | text with scripts, styles and tags dropped |

Not supported:

- **OCR.** A scanned page has no text. `text` lists it in `emptyPages`, and `extract` on a document with no
  text at all does not call the model: its item carries `note`. With `vision=true`, a PDF without any text layer
  and an image are sent to a multimodal model instead ([DOCUMENTS-VISION.md](DOCUMENTS-VISION.md)).
- **Encrypted PDFs** (refused), **LZW / ASCII85 / JBIG2 streams** (skipped), **composite fonts without
  `/ToUnicode`** (their glyph ids are not characters: no text).
- **Layout.** Tables come out as lines of text, in content-stream order.

## Tests

- `DocumentFeedTest`: PDFs generated in the test (WinAnsi, ToUnicode inside an object stream, a scanned
  page, an encrypted file), text, HTML and DOCX pages, verified and invented snippets, the threshold,
  folder confinement (outside root, `../`, symbolic link, hidden file), `extractAll` with one broken file.
- `DocumentVisionTest`: images and PDFs without text layer sent to the model only with `vision=true`, the
  confidence cap, sizes and pages, a text PDF never sent as a file, `annotate` on a DAMA image.
- `DocumentConfidenceTest`: the engine proposes below the threshold, including on a record being created;
  values above it are written with their source; a document without an envelope is unchanged.

Not tested offline, and still to check on a live instance: a real model's answers through `llm.ask`, the
conflict manager showing the comment, and `FieldProvenance.source` after a real import.

Documents stored in DAMA (EBX Digital Asset Manager), and operation `annotate` that writes metadata and tags back to
the asset: [DAMA-DOCUMENTS.md](DAMA-DOCUMENTS.md).

Images and scanned PDFs through a multimodal model (`vision=true`): [DOCUMENTS-VISION.md](DOCUMENTS-VISION.md).

[Return to the connector user guide](USER-GUIDE.html)
