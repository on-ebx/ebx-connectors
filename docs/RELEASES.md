# EBX Connectors release notes

The release-notes page reads its user-facing content from the classpath resource
`/com/on/ebx/connect/release/release-notes.xml`. Keep the content concise, free of
credentials and internal implementation details, and useful to an EBX user.

## Formal releases and development increments

A Git commit, deployment, acceptance run, or completed implementation pass is a
development increment. It must use `kind="development"` and must not claim a
version tag or release date.

A formal release exists only after the repository has an immutable Git tag. Add a
`kind="formal"` manifest entry with all of the following attributes:

- `version`: a semantic version such as `1.2.0`;
- `sourceTag`: the exact corresponding tag, `v1.2.0`;
- `releasedOn`: the release date in ISO format, such as `2026-09-09`;
- a stable, unique `id` and a lifecycle `status`.

Do not infer releases from commit messages or deployment directories. At the time
this contract was introduced, the EBX Connectors repository had no Git tags, so
the packaged entry is explicitly a development preview.

## Versions, packages and the check after install (audit C-08, C-09)

Three scripts under `ebx-connectors/tools/` carry the release mechanics. None of them creates or pushes a tag.

- **`version.sh`** gives the version a source tree builds as: the nearest tag without its leading `v`
  (`1.2.0`, or `1.2.0-3-g1a2b3c4` between tags), `0.0.0-<short hash>` while the repository has no tag (today), with
  `-dirty` appended when tracked files differ from HEAD. `EBX_BUILD_VERSION` (and `EBX_BUILD_COMMIT` for the commit; the older
  `EBX_CONNECTORS_VERSION` still works) overrides it for a build from a copy without `.git`, which is how
  `tools/install.sh` stamps the FastTrack workspace copy.
- **`build-test.sh`** stamps every build with it: `com/on/ebx/connect/version.properties` (`version`, `commit`,
  `builtAt`) in the classes, and `Implementation-Version`, `Build-Commit`, `Built-At` in the war's
  `META-INF/MANIFEST.MF`. `core/ModuleVersion` reads the resource (`unknown` when the classes were not built by the
  script, such as an Eclipse build); the MCP `serverInfo.version` and `GET /ebx-connectors/rest/v1/status`
  (`version`) report it. `ModuleVersionTest` covers the resource present and absent.
- **`package.sh`** runs `build-test.sh`, then writes `ebx-connectors/dist/ebx-connectors-<version>.zip` (git-ignored; never
  commit it): one folder holding `ebx-connectors.war`, `THIRD-PARTY-NOTICES.md`, `OPERATIONS.md`,
  `UPGRADE-ROLLBACK.md`, `RELEASE-NOTES.md`, `tools/smoke.sh`, `tools/schema_diff.py` and `SHA256SUMS`, plus
  `ebx-connectors-<version>.zip.sha256` beside the zip. `RELEASE-NOTES.md` is the first-parent git log since the
  previous tag, or since `--since <commit-or-tag>` (with no tag and no `--since` it lists the whole first-parent
  history). An uncommitted tree is refused unless `--allow-dirty`.
- **`smoke.sh <base-url>`** is the check after an install or an upgrade: read-only, credentials from `EBX_AUTH`
  (and `EBX_AUTH_NOROLE`) in the environment only. See `ebx-connectors/docs/OPERATIONS.md` section 2.4.
- **`schema_diff.py`** compares two `Connectors.xsd` (files or git refs) and lists what would empty or drop data.

**To cut a formal release** (the owner's steps): add the `kind="formal"` entry to the release-notes manifest and
commit it; create the annotated tag `v<version>` on that commit; run `ebx-connectors/tools/package.sh` from a clean
checkout of the tag (the version is then exactly the tag, and the notes run from the previous tag); install it where
asked, run `smoke.sh` with `EXPECT_VERSION=<version>`, and keep the zip and its checksum with the deployment record.
The in-application release history (`release-notes.xml`, the `Release*` tables) is separate from the build
stamp: it carries the user-facing text, the stamp carries what is installed.

## Validation

Run `./ebx-connectors/build-test.sh`. `ReleaseNotesManifestTest` verifies that the
resource is packaged, parseable without external XML access, uses supported note
categories, has unique entries, and cannot present a development increment as a
formal release. Before publishing a formal release, additionally confirm that its
`sourceTag` exists in `git tag --list`.
