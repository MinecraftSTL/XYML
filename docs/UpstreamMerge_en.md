# Upstream Merge Guide

<!-- #BEGIN LANGUAGE_SWITCHER -->
中文 ([简体](UpstreamMerge.md), [繁體](UpstreamMerge_zh_Hant.md)) | **English**
<!-- #END LANGUAGE_SWITCHER -->

This guide is for XYML maintainers who need to review and integrate changes
from HMCL. The Simplified Chinese default document (`UpstreamMerge.md`) is the
content baseline; the English and Traditional Chinese versions must keep the
same sections, commands, paths, commit identifiers, and constraints.

## Scope and Current Divergence

XYML is an independently maintained downstream project that evolved from HMCL.
In the normal setup, `origin` points to the XYML repository and `upstream`
points to the HMCL-dev/HMCL repository. The two trees now differ materially in
architecture and product boundaries. Do not copy upstream files over XYML as
if the projects were still structurally identical.

The following is the divergence snapshot recorded while this guide was
written (2026-09-12). These numbers explain how to record one synchronization;
they are not permanent version values and must be refreshed before each real
integration:

| Item | Snapshot value |
| --- | --- |
| Current `HEAD` | `9e48f059cf02c2ec96a071448356099840a69a9b` |
| `upstream/main` | `df52bc6e81e2e1116c131483dfb9996fdb7b2b10` |
| Common ancestor | `b4549b6f68b99bb5fa6d69f94fffffcee0dc36c1` |
| Commit count for `HEAD...upstream/main` | `528` local-only, `26` upstream-only |
| Incoming range (`git diff HEAD...upstream/main`) | 76 files changed, 1878 insertions(+), 1124 deletions(-) |

The upstream ref may advance after the inspection begins; new upstream commits
were observed while this guide was being prepared. Therefore rerun the
commands below for every actual synchronization and record the latest output.
The three-dot syntax identifies commits and changes from the common ancestor
toward the upstream side. A direct comparison of the two final trees mixes
HMCL-to-XYML renames and independent architecture changes and must not be used
for an overwrite-style merge.

## Pre-Merge Checks

### Check the Worktree and Refs

Before any merge, cherry-pick, or manual port, run these commands in the
intended integration worktree:

~~~bash
git status --short --branch
git worktree list --porcelain
git branch -vv
git remote -v
git show-ref
git rev-parse HEAD
git rev-parse upstream/main
git show -s --format="%H%n%ad%n%s" --date=iso upstream/main
git rev-parse -q --verify MERGE_HEAD
git diff --name-only --diff-filter=U
~~~

Confirm all of the following:

- The worktree is clean and has no `MERGE_HEAD`, rebase, or cherry-pick state.
- The target branch is not checked out with uncommitted changes in another
  linked worktree. If it is occupied, use a separate integration branch and
  worktree.
- The `origin` and `upstream` URLs are expected, and the full
  `upstream/main` SHA is recorded.
- Local `dev`, release branches, and uncommitted user work are not being used
  as an ad hoc merge target.

If a cached upstream ref is required, confirm its source and timestamp:

~~~bash
git show -s --format="%H %ci %s" upstream/main
git reflog show --date=iso upstream/main
~~~

Do not run `git fetch`, `git push`, or modify shared remote refs without
explicit authorization. When using the local cached `upstream/main`, state
that fact and its full SHA in the merge record; this workflow never performs
an implicit fetch or push.

### Calculate the Incoming Range

~~~bash
git merge-base HEAD upstream/main
git rev-list --left-right --count HEAD...upstream/main
git log --cherry-pick --left-right --oneline HEAD...upstream/main
git diff --shortstat HEAD...upstream/main
git diff --name-status --find-renames HEAD...upstream/main
git log --oneline --reverse HEAD..upstream/main
~~~

`--cherry-pick` helps find changes already present in XYML under a different
commit. Even when no duplicate commit is found, read the surrounding code:
local implementations may have moved or changed ownership boundaries. Record
one of `direct review`, `manual adaptation`, `product decision`, or `skip` for
every upstream commit, together with its XYML path and reason.

## Classify Upstream Changes

Classify by behavior first and by file second. The table below lists the
important changes in the recorded divergence; re-check the classification when
the upstream range changes.

| Category | Upstream change | XYML handling focus |
| --- | --- | --- |
| Core logic, safe to evaluate first | Yggdrasil token validation (`9d9fc7838`); CurseForge retry/hash/integrity (`e287f672f`); client JAR sync (`24702dc5a`) | Map to XYMLCore tasks/repos. |
|  | Forge/Cleanroom launch (`513f53bc0`, `0ad180c08`); task/archive/ZIP fixes (`b2d4d3685`, `9c031c5ac`, `e2d3ac847`) | Preserve locks, offline behavior, retry/cancel, and security. |
|  | Missing-parent cleanup (`1a258a255`); mrpack/dependency updates (`1da8c8d6a`, `0f4152406`); `.gitignore` `*.jfr` (`3742be0aa`) | Add or run a targeted test for each behavior. |
| Data model/API | `OptiFineVersion` (`0e3455434`) and `CurseMetaMod` (`1acfc5500`) become records | Check Gson annotations, old JSON/manifests, and constructors. |
|  |  | Confirm field names, getter call sites, reflection, and compatibility; do not replace accessors mechanically. |
| UI requiring manual adaptation | Pack icons, themes, search, loaders, names (`886a25a93`, `450504ced`, `8767cc0e9`, `7331f619c`, `7a8f93164`) | Port to Swing or neutral Core. |
|  | Loader reset, tree cells, icon dialogs, toolbar, progress (`ce51dc4dd`, `b97e7fbd5`, `7f578be08`, `cbc6daf3d`, `ea232b097`) | No JavaFX/JFoenix/MonetFX/OpenJFX, flags, or patchers. |
| Requires a product decision | Upstream removes MultiMC export (`df52bc6e8`) | XYML still publicly supports MultiMC, so keep it by default. |
|  |  | If removal is approved, review Core tasks, exporter factory, UI, I18N, resources, and tests together. |
| Branding and localization | "Hello Minecraft Launcher" -> HMCL (`1428183e3`) | Keep XYML branding, `.xyml`, `xyml.*`, and the application ID; never replace them with HMCL text. |
|  |  | Review keys one by one; sync all three language resources, placeholders, and escaping. |

## File Groups That Cannot Be Accepted Directly

Upstream paths use `HMCL`, `HMCLCore`, and `HMCLBoot`, while local paths use
`XYML`, `XYMLCore`, and `XYMLBoot`. A path in an upstream commit cannot be
applied directly until it has been mapped to the local module, package, and
test. Build the mapping first and port files one at a time. Do not resolve an
entire directory by choosing `ours` or `theirs`.

## XYML-Specific Adaptation Rules

### Namespace, Resources, and Build

- Map `org.jackhuang.hmcl` to `space.minecraftstl.xyml` one occurrence at a
  time. Check Java packages, reflection strings, service-loader descriptors,
  serialized class names, system properties, and resource paths; never use a
  global text replacement.
- Keep `.xyml`, `xyml.*`, the XYML display name, version inference, the
  `dev -> alpha -> beta -> main` release process, and local CI settings. Do
  not replace a complete HMCL workflow, version file, or artifact definition.
- Keep the local `xoyz-nbt`, `xoyz-mcp`, `XYMLL`, `lwjgl-unsafe-agent`, and
  `mesa-loader-windows` libraries, including each library's `SOURCE.md`,
  license, upstream SHA, and identity tests.
- Check service descriptors, manifests, artifact names, system properties, and
  packaged resources for `XYMLTransformerDiscoveryService` and
  `XYMLMultiMCBootstrap`. In particular, verify the still-used
  `HMCLMultiMCBootstrap-1.0.jar` resource name.
- Preserve the Swing-only architecture: Core remains toolkit-neutral and UI
  behavior belongs at the existing `XYML/ui/swing` boundary. Do not
  reintroduce JavaFX/JFoenix/MonetFX/OpenJFX dependencies, runtime downloaders,
  or patchers.

### Local Boundaries That Must Be Preserved

- Keep the Swing-only application and the toolkit-neutral `XYMLCore`; treat
  upstream JavaFX/JFoenix code only as a behavior reference.
- Keep `xoyz-nbt`, `xoyz-mcp`, `XYMLL`, `lwjgl-unsafe-agent`, and
  `mesa-loader-windows`, including each library's `SOURCE.md`, license,
  upstream SHA, and identity tests.
- Check `XYMLTransformerDiscoveryService`, `XYMLMultiMCBootstrap`, service
  descriptors, system properties, artifact names, and the still-used
  `HMCLMultiMCBootstrap-1.0.jar` resource reference.
- Preserve XYML version resolution, the four-channel release flow, offline
  artifact verification, MCP authentication and redaction, the stdio
  boundary, bounded and transactional NBT editing, task-resource ownership,
  and existing directory filters.
- Do not replace build scripts, workflows, Jenkins configuration, version
  properties, or release configuration wholesale. Recheck the Java 17/25/8
  toolchains and the `stableVersion` contract.

### Local Behavior Contracts

Upstream changes must remain compatible with these XYML contracts; do not
delete or weaken them while resolving text conflicts:

- MCP bearer authentication, sensitive-data redaction, and the local stdio
  transport boundary.
- Bounded, strict, and transactional NBT parsing and editing.
- Task-resource locks, ownership, cancellation, retry, and concurrent-update
  rules.
- Crash analysis, diagnostic output, and offline behavior.
- Swing directory, mod, and version filters, including their de-duplication
  and ordering behavior.

### Localization and Data Compatibility

- Merge I18N changes key by key and always update
  `I18N.properties`, `I18N_zh_CN.properties`, and `I18N_zh.properties`
  together.
- Preserve format placeholders, Properties escaping, line breaks, and unique
  keys. Review brand strings separately.
- After a record conversion or field rename, check Gson field names, old
  settings, imported instances/modpacks, and reflective callers. Keep a
  compatibility accessor or migration path when required.
- Check classpath order, service descriptors, manifests, embedded JARs, and
  offline caches so packaged names still match the XYML launch flow.

## Conflict Resolution

1. Create a dedicated integration branch from a clean snapshot and confirm
   that the target branch is not occupied by another worktree:

   ~~~shell
   git switch -c <integration-branch>
   ~~~
2. Select the commits to introduce using the classification above. When
   history must be preserved, run an explicit merge after the review:

   ~~~shell
   git merge --no-ff upstream/main -m "merge(upstream): integrate <range>"
   ~~~

   For incompatible UI or product changes, use a documented manual or
   selective port and list every skipped upstream commit in the record.
3. Resolve each file according to the local module and responsibility
   boundary, rewriting paths, packages, resources, and tests as needed. Do
   not use whole-directory `ours` or `theirs` to hide the divergence.
4. After resolving, search for stale conflicts and names:

   ~~~shell
   rg -n -uu "<<<<<<<|=======|>>>>>>>" .
   rg -n -uu "org\.jackhuang\.hmcl|HMCLCore|HMCLBoot|/HMCL|\\HMCL|JavaFX|javafx|JFoenix|MonetFX|OpenJFX|hmcl\.|\.hmcl" XYML XYMLCore XYMLBoot libraries minecraft
   git diff --check
   git diff --name-only --diff-filter=U
   ~~~

   Then inspect service descriptors, `META-INF`, manifests, resource indexes,
   system properties, artifact names, and generated documents. Do not use one
   global search as a substitute for file-by-file review.
5. If a real merge is in progress and cannot continue, use `git merge --abort`
   only then. Do not use a destructive reset or whole-tree rollback to hide
   an unresolved conflict.

## Verification Gates

### Documentation and Localization

After changing this guide or a `Contributing` document, run from the repository
root:

~~~bat
.\gradlew.bat updateDocuments
~~~

Inspect the language switchers, relative links, titles, and generated
differences in all three guide files and all three `Contributing` files. Do not
manually rewrite generated `LANGUAGE_SWITCHER` output.

### Code and Resources

Run the smallest relevant tests for the introduced change groups, including:

- Authentication tokens, CurseForge retry/hash/integrity, and client JAR sync.
- Archive/ZIP paths, duplicate and parent entries, encodings, and size bounds.
- Missing-parent/circular instances, Forge/Cleanroom launch, and mrpack export.
- Record serialization, old-data round trips, and local-library identity tests.
- Affected Swing UI behavior and layout on the EDT; JavaFX tests are not a
  substitute.

### Repository Checks

~~~bat
.\gradlew.bat checkstyle checkTranslations --no-daemon --parallel --stacktrace
.\gradlew.bat build --no-daemon --parallel
.\gradlew.bat :test --no-daemon --parallel
~~~

If UI, packaging, or native paths changed, also run the applicable offline
artifact, manifest, fat-JAR, and no-JavaFX-dependency checks. Use the repository
Gradle Wrapper, JDK 17/JDK 25 as required by the modules, and local caches.
Report skips caused by network, platform, permissions, or toolchains
separately from code failures. Documentation-only pull requests may not
trigger Java CI because `.github/workflows/gradle.yml` uses
`paths-ignore: '**.md'`; this does not replace local verification.
When UI or build configuration changes, also run `verifyOfflineUiArtifact` or
an equivalent offline-artifact check to confirm that JavaFX/JFoenix entries
were not reintroduced.

### Final Git Verification

~~~bash
git diff --check
git status --short --branch
git show -s --format="%H %P %ci %s" HEAD
git merge-base --is-ancestor upstream/main HEAD
~~~

For a complete merge, confirm that `HEAD` has two parents and that
`upstream/main` is an ancestor of the result. For a selective port, retain a
table mapping the upstream range to every skipped or adapted decision and do
not claim that upstream is an ancestor.

## Final Delivery Checks

The final Git and artifact checks above must pass before delivery.

## Completion Criteria

- The change contains only reviewed upstream commits, required XYML
  adaptations, and corresponding tests or documentation.
- No conflict markers, stale HMCL paths, JavaFX/JFoenix dependencies, or
  incorrect `.hmcl`/`hmcl.*` configuration remain.
- `updateDocuments`, `git diff --check`, localization checks, relevant tests,
  and the strongest feasible full build have been run and recorded.
- The merge or selective-port commits retain upstream SHAs, handling
  conclusions, and skip reasons so the next synchronization can re-evaluate
  them.
- Release branches still follow the `dev -> alpha -> beta -> main`
  `--no-ff` topology. Upstream synchronization does not replace release
promotion and never pushes a remote implicitly.
