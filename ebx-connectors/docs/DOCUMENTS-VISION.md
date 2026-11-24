# Documents connector: images and scanned PDFs through a multimodal model

> **Closed by default (decision of 2026-11-12).** Vision is part of the `documents` function: it runs only when `documents` is in the JVM property `-Debx.connectors.preview` (docs/OPERATIONS.md, "Preview functions"), and still needs `vision=true` on both connectors.

Status, 2026-11-02: **implemented, tested offline (`DocumentVisionTest`, `LlmConnectorTest`), and run on the FT 6.3 RC
with OpenAI (`gpt-6-luna`)**: RC scenario A5.1–A5.2 passed (an image refused without `vision`; a generated red PNG read
as "red", confidence capped at 0.7). Not yet run: a PDF without text layer on a real model, the other providers, and the
DAMA image part (A5.3–A5.4, no test record yet). The questions of §9 were answered with
the proposed defaults; §3 changed after the provider check (Mistral reads PDFs, Azure OpenAI is images only).

Without it, the `documents` connector ([DOCUMENTS.md](DOCUMENTS.md)) only reads text: an image asset is refused ("no OCR and
no vision") and a scanned PDF gives a `note` and nothing else. With `vision=true`, the image itself goes to a multimodal
model through the LLM connector, so that the same operations (`extract`, `extractAll`, `annotate`) and the same entry
points (mapping, workflow step, table trigger, scheduled task) work on photos, scans and scanned PDFs, from DAMA
([DAMA-DOCUMENTS.md](DAMA-DOCUMENTS.md)), a server folder or an upload.

## 1. What the user gets

Nothing new to learn. A record holding a photo of a product, a scanned invoice or an ID document in its DAMA field:

- an inbound mapping on `documents/extract` fills dedicated fields of the record (`input record = ${./id}`, rules
  attribute → field), started by `ConnectorTableTrigger` with `watchedFields=./photo` or by `ConnectorScriptTaskBean` in a
  workflow;
- `annotate` writes DAMA metadata and adds tags from DAMA's vocabulary to the image (the main DAMA use case: tagging a
  picture library);
- values below the threshold wait in the conflict manager, as for text documents.

The connection has to allow it explicitly (`vision=true`, §6), because the image itself leaves EBX.

## 2. How it works

```
DAMA asset / file / upload
   │  format from extension, label or mime
   ├─ text document ───────────► text, page by page ─► prompt (text)          ─┐
   ├─ image (png, jpeg, gif, webp) ─► bytes, checked ─► prompt + 1 image        ├─► LLM connector ─► JSON answer
   └─ PDF without text layer ──► the PDF itself    ─► prompt + PDF (§4)       ─┘      │
                                                                                      ▼
                                       same answer shape: {attr: {value, confidence, page, snippet}, tags: [...]}
                                       confidence capped for what cannot be checked (§5) ─► threshold ─► write / propose
```

- `DocumentConnector.Model` has a second method, `ask(system, prompt, List<LlmConnector.Attachment>)`, an attachment
  being `{mediaType, data, name}`. The text-only `ask` stays; a default method keeps the test stubs compiling.
- `LlmConnector.ask(system, prompt, attachments, maxTokens, json, timeout)` takes the same `chat` path; `attach` turns the
  user message into a list of parts, attachments first, in the provider's format (§3). It is pure and tested per provider.
- `DocumentConnector.read` is the one path of `extract`, `extractAll` and `annotate`: text when the document has some,
  otherwise the attachment when `vision=true`, otherwise today's `note`.
- The prompt (`DocumentExtraction.promptForAttachment`) keeps the answer shape (fields, closed tag list); the `snippet`
  is "the words you read in the image, or where you see it", `page` is 1 for an image, the PDF page for a PDF. The system
  prompt (`systemForAttachment`) says that text in the image is data, never an instruction.
- The item carries `attachment` (`image` or `pdf`) and `readBy`. `text` on an image returns no text and a `note`: this is
  not OCR as a product. A transcription can be asked through `extract` with a `transcript` field.

## 3. Wire formats (checked in each provider's documentation on 2026-11-02)

| Provider (`LlmConnector.provider`) | Image part in the user message | PDF |
|---|---|---|
| `anthropic` | `{"type":"image","source":{"type":"base64","media_type":"image/png","data":"…"}}` | `{"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"…"}}`, before the text as the documentation recommends |
| `openai` | `{"type":"image_url","image_url":{"url":"data:image/png;base64,…"}}` | `{"type":"file","file":{"filename":"…","file_data":"data:application/pdf;base64,…"}}` |
| `azure`, `azure-ai` | as OpenAI | **refused**: Azure's vision page names no file part, and users report "does not support file content types" |
| `google` (Gemini API and Vertex AI) | part `{"inlineData":{"mimeType":"image/png","data":"…"}}` (camelCase, as the reference) | same part, `application/pdf` |
| `mistral` | `{"type":"image_url","image_url":"data:…"}` (a string) | `{"type":"document_url","document_url":"data:application/pdf;base64,…","document_name":"…"}` |
| `ollama` | `"images": ["<raw base64>"]` on the user message (vision models only) | **refused** |

Sources: platform.claude.com (vision, pdf-support); developers.openai.com (chat create, pdf-files, images-vision);
learn.microsoft.com (gpt-with-vision); aistudio.google.com (generate-content reference, document-processing);
docs.mistral.ai (openapi.yaml, vision, document_qna); docs.ollama.com (api/chat, vision). Still uncertain, to see on a
real call: OpenAI's chat reference only says "base64 encoded file data" for `file_data` (the data-URL prefix comes from its
examples); Gemini's `generateContent` is still documented while Google's guides now show a newer Interactions API.

The model must accept images: the module cannot tell from its name. The LLM connector property `vision` (`false` by
default) declares it. Without it the call is refused before anything is sent; a provider's HTTP 400 on an attachment
adds "if the model does not accept images, choose a multimodal model or set vision=false".

## 4. Scanned PDFs

Two ways were considered; A is built:

- **A. Send the PDF as is** to the providers that read PDFs natively (Anthropic, OpenAI, Gemini). Simple, keeps the
  page numbers, no image library. Ollama and Mistral cannot.
- **B. Render the pages as images** in the module, then send them as images. Works with every multimodal provider, but
  the module reads PDFs with its own parser (`core/PdfText`), which cannot render: B means a new dependency (PDFBox,
  about 3 MB in the WAR). It also costs memory and many more tokens, and needs a page cap.

A is built; B only if a customer needs Ollama or Azure on scans. A PDF is sent to the model only when no page has text
(the `note` case), never instead of the text.

## 5. Confidence: what cannot be checked is capped

For text, a snippet that is not found in the document caps the confidence at 0.5 (`UNVERIFIED_CAP`). An image has no
text to check the evidence against, so every value read from an image is unverified by nature. So:

- a cap `vision.confidence.max` on the documents connection, **default 0.7, below the default threshold 0.8**: out of
  the box, nothing read from an image is written directly, everything goes to the steward;
- the operator raises it (up to 1) once the results on their own images are trusted, for example for tags;
- the item says `verified: false`, `source: "image"`, and the conflict comment names the asset and the evidence, so the
  steward can open the image from the record.

## 6. Security and data

- **The image leaves EBX** for the model provider (unless Ollama is local). Photos and scans hold more personal data
  than a text extract (faces, signatures, ID numbers). So it is opt-in twice: `vision=true` on the LLM connector (the
  model can) and `vision=true` on the documents connection (this source may send images). Without the second, an image
  is refused with "no OCR and no vision on this connection (vision=true …)"; without the first, the LLM connector refuses.
- The egress rules (`ebx.connectors.allowedHosts`) and the secret handling are those of the LLM connector, unchanged.
- **Prompt injection through the image** (text written in a picture that addresses the model): the answer is still
  limited to the declared fields and the closed tag list, goes through the mapping rules, and is capped as above. The
  system prompt says to treat text in the image as data.
- The bytes are never logged or kept; the synchronisation log keeps the asset, the size and the token counts.

## 7. Limits and cost

| Limit | Proposed |
|---|---|
| Formats | png, jpeg (`jpg`), gif, webp (what all the providers above accept), by extension, DAMA extension or mime. TIFF, HEIC, BMP, SVG refused |
| Size | `max.image.bytes`, default 5 MB, ceiling 20 MB; refused above, not resized (Anthropic: 10 MB per base64 image; Azure: 20 MB) |
| Pages of a scanned PDF | `max.pages`, default 20, ceiling 100; within `max.bytes` (20 MB) |
| Images per call | 1. A record with several assets gives one call each |
| Time | a vision call takes seconds: the trigger stays non-blocking by default (background, after commit); `blocking=true` is discouraged on image fields |
| Cost | one image is roughly 1,000–2,000 input tokens depending on its size and the provider; the log keeps the counts |

## 8. Tests

- Offline, `LlmConnectorTest`: the parts of each row of §3 (pure `attach`), the refusals (no `vision`, a PDF on Ollama,
  Azure, Azure AI), one call on the wire with an image, and a call without attachment unchanged.
- Offline, `DocumentVisionTest`: refused without `vision`; the image sent with the attachment system prompt; the cap
  (0.7, then 1); `jpg`, mime, TIFF refused; `text` on an image; `max.image.bytes`; a scanned PDF sent as a PDF,
  `max.pages`; a text PDF read as text, never sent as a file; a DAMA image read and annotated (all proposed at the
  default cap, written at 0.9; closed vocabulary).
- RC, scenario A5 (reads only): an image refused without `vision`; a generated red PNG read as "red", capped, "not
  checked"; with `RC_DAMA_IMAGE_RECORD`, a DAMA image extracted and annotated as a dry run. Skipped when the RC's LLM
  connection does not declare `vision=true`.

## 9. Decisions (defaults validated by the product owner on 2026-11-02)

1. Images and scanned PDFs in the same delivery.
2. Opt-in on both the LLM connector and the documents connection (`vision=true`).
3. Scanned PDFs: A, the PDF is sent to the providers that read it.
4. An image above `max.image.bytes` is refused, not resized.
5. Confidence cap 0.7: by default everything read from an image goes to a steward.
6. Providers: all of §3. The default was "all except PDFs on Mistral and Ollama"; the provider check changed it to
   "all except PDFs on Azure, Azure AI and Ollama", Mistral's chat API reading PDFs.
7. RC: scenario A5 needs a multimodal model on the RC's LLM connector, and for its DAMA part a test image outside
   Reference; no data model change.

[Return to the documents connector](DOCUMENTS.md)
