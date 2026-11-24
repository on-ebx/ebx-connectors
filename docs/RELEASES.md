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
- a stable, unique `id` and a lifecycle `status`: `current` for ONE formal release (the latest), `supported` or
  `archived` for the others; `in-progress` or `complete` for a development increment.

The release-notes page shows that status: Current, Supported, Archived, In progress (a complete development
increment is shown as Archived). Until 1.5.0 the attribute was not read: the page showed every formal release as
Current. When a new release becomes the current one, change the previous one to `supported` in the manifest: at the next
start the installer brings the status of the rows it owns to the manifest (`release notes: status of N installed
release(s) brought to the packaged notes` in the kernel log); it rewrites nothing else of an installed row.

Do not infer releases from commit messages or deployment directories. The repository
has annotated tags since `v1.0.0` (2026-10-06). The manifest's formal entries start at
1.4.0: `v1.4.0`, `v1.4.1` and `v1.4.2`, all three tagged on 2026-11-12, then 1.5.0, whose entry was
written on 2026-11-23 before its tag (the first step of "To cut a formal release" below). The tags
`v1.0.0` to `v1.3.1` have no entry in the manifest, and the `kind="development"` entries
stay development increments: six of the seven were written in September, when the
repository had no tag.

## Versions, packages and the check after install (audit C-08, C-09)

Three scripts under `ebx-connectors/tools/` carry the release mechanics. None of them creates or pushes a tag.

- **`version.sh`** gives the version a source tree builds as: the nearest tag without its leading `v`
  (`1.2.0`, or `1.2.0-3-g1a2b3c4` between tags), `0.0.0-<short hash>` in a copy of the repository that holds no tag, with
  `-dirty` appended when tracked files differ from HEAD. `EBX_BUILD_VERSION` (and `EBX_BUILD_COMMIT` for the commit; the older
  `EBX_CONNECTORS_VERSION` still works) overrides it for a build from a copy without `.git`, which is how
  `tools/install.sh` stamps the FastTrack workspace copy.
- **`build-test.sh`** stamps every build with it: `com/on/ebx/connect/version.properties` (`version`, `commit`,
  `builtAt`) in the classes, and `Implementation-Version`, `Build-Commit`, `Built-At` in the war's
  `META-INF/MANIFEST.MF`. `core/ModuleVersion` reads the resource (`unknown` when the classes were not built by the
  script, such as an Eclipse build); the MCP `serverInfo.version` and `GET /ebx-connectors/rest/v1/status`
  (`version`) report it. `ModuleVersionTest` covers the resource present and absent.
- **`package.sh`** asks OSV for the advisories of the shipped jars (`tools/deps_check.sh --require-osv`; an advisory that
  `runtime-libs/DEPS-KNOWN.txt` does not accept refuses the package), runs `build-test.sh`, then writes
  `ebx-connectors/dist/ebx-connectors-<version>.zip` (git-ignored; never commit it): one folder holding
  `ebx-connectors.war`, `THIRD-PARTY-NOTICES.md` and `licenses/`, `OPERATIONS.md`, `UPGRADE-ROLLBACK.md`,
  `RELEASE-NOTES.md`, `DEPENDENCY-CHECK.txt`, `tools/smoke.sh`, `tools/schema_diff.py`, `jars/` (since 1.4.2:
  `on-ebx-agent.jar`, `on-ebx-mapping.jar` and `ebx-connectors-spi.jar`, built from the packed commit) and `SHA256SUMS`,
  plus `ebx-connectors-<version>.zip.sha256` beside the zip. `RELEASE-NOTES.md` is the first-parent git log since the
  previous tag, or since `--since <commit-or-tag>` (with no tag and no `--since` it lists the whole first-parent
  history). It needs `EBX_STANDARD_JAR` (the `ebx.jar` of a standard distribution) for the self-sufficiency check of
  the war. A package is built only from a clean commit: a modified or untracked file is refused, and `--allow-dirty` is
  no longer accepted.
- **`smoke.sh <base-url>`** is the check after an install or an upgrade: read-only, credentials from `EBX_AUTH`
  (and `EBX_AUTH_NOROLE`) in the environment only. See `ebx-connectors/docs/OPERATIONS.md` section 2.4.
- **`schema_diff.py`** compares two `Connectors.xsd` (files or git refs) and lists what would empty or drop data.

**To cut a formal release** (the owner's steps): add the `kind="formal"` entry to the release-notes manifest and
commit it; create the annotated tag `v<version>` on that commit; run `ebx-connectors/tools/package.sh` from a clean
checkout of the tag (the version is then exactly the tag, and the notes run from the previous tag); install it where
asked, run `smoke.sh` with `EXPECT_VERSION=<version>`, and keep the zip and its checksum with the deployment record.
Then publish the zip and its `.sha256` as the GitHub release of the tag
(`gh release create v<version> <zip> <zip.sha256> --verify-tag`): pushing a tag does not create a release. 1.4.2 is the
first version with one, published on 2026-11-20, eight days after its tag; the earlier tags have none.
The in-application release history (`release-notes.xml`, the `Release*` tables) is separate from the build
stamp: it carries the user-facing text, the stamp carries what is installed.

## Validation

Run `./ebx-connectors/build-test.sh`. `ReleaseNotesManifestTest` verifies that the
resource is packaged, parseable without external XML access, uses supported note
categories, has unique entries, and cannot present a development increment as a
formal release. Before publishing a formal release, additionally confirm that its
`sourceTag` exists in `git tag --list`.
