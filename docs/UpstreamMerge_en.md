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

For each integration, record the full local `dev`/`HEAD` and selected `upstream/main` SHAs, common ancestor, unique
commit counts, and incoming patch statistics. Record the source and commit time of cached refs; do not describe a cached
ref as the latest remote state.

Rerun the commands below for every integration. Three-dot diff syntax shows incoming changes from the common ancestor to
upstream. Comparing final trees helps identify architecture, API, and product boundaries, but does not replace
incoming-range analysis or authorize whole-file overwrites.

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

Classify by behavior, not filenames alone. For every actual incoming commit, record `direct review`, `manual
adaptation`, `product decision`, or `skip`, together with its upstream SHA, local entrypoint, adaptation/skip rationale,
and test results.

| Category | Review scope | XYML handling focus |
| --- | --- | --- |
| Core logic | Authentication, downloads, integrity, loaders, Archive/ZIP, instances, modpacks | Compare current consumers, resource declarations, error handling, and boundary tests. |
| Data models/APIs | Records, serialization, repository/layout/snapshots, settings, accounts | Check public contracts, field names, schemas, ownership, and compatibility decisions. |
| Manual adaptation | JavaFX controls/state, tasks, navigation, themes, update prompts | Port to local Swing, observable, task, and service entrypoints; never restore JavaFX dependencies. |
| Product decisions | Feature removal, provider capabilities, runtimes, releases, branding | Preserve local features/channels by default; confirm external behavior changes first. |
| Documentation/localization | Document languages, filenames, macros, links, I18N keys | Map documents to local language rules; merge resources by key and synchronize all three languages. |

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

### UI, Observable State, and Thread Migration

- JavaFX controls, properties, bindings, and observable collections are not interchangeable with local APIs. Map
  behavior to `XYML/src/main/java/space/minecraftstl/xyml/ui/swing` and the Core `observable` contracts; preserve
  concrete event payloads, notification order, and subscription disposal.
- Keep background I/O on `Schedulers.io()` and UI continuations on the installed `Schedulers.ui()` executor; verify
  Swing EDT ownership. Do not restore `Schedulers.javafx()`, `FXUtils`, or JavaFX types.
- Check cached pages, navigation, dialog closure, and stale asynchronous results. A deleted upstream UI file may still
  contain portable behavior; a missing local filename is not a reason to skip its entire commit.
- Keep toolkit-neutral Core representations and Swing boundary conversions for themes, images, and colors. See [Swing UI Migration](architecture/swing-ui-migration_en.md).

### Task Execution, Resources, and Lifecycle Migration

- Adapt upstream tasks and cancellation callbacks to local `Task`, `TaskExecutor`, `TaskExecutionRegistry`, and `TaskResource`; resolving signatures or restoring old task dialogs is insufficient.
- Check read/write paths, instances, game directories, caches, and shared resources. Preserve arbitration and cleanup
  APIs; do not hide missing precise declarations behind global resources or label unknown work as resource-free
  orchestration.
- `start()` neither means completion nor guarantees immediate execution. Preserve `WAITING`, running, cancellation
  requests, and terminal states; check retry, progress, logs, subscriptions, and visible cleanup failures.
- Route user-visible tasks through the existing `TaskLaunchController`. Automatic task navigation selects the running
  tab; manual page entry retains the selected tab. Closing the initiating page must not cancel installations already
  owned by the task system.
- Test resource contention/concurrency, cancellation while waiting, exception/interruption cleanup, continued execution
  after page closure, and state/progress publication. Submission or UI closure is not evidence of task success.

### Repository, Installation, and Remote Add-on Entrypoints

- Compare upstream repository, instance, layout, and snapshot APIs with `XYMLGameRepository`, `GameRepository`,
  `GameRepositorySnapshot`, and their consumers. Identical snapshot names do not imply identical fields, ownership, or
  refresh events.
- `GameRepositoryDraft` covers only explicitly enlisted files and participants. Existing install, rename, delete,
  modpack, and repair entrypoints do not automatically gain whole-operation rollback or cross-restart recovery. See
  [Repository draft API](architecture/repository-drafts.md).
- Map game installation to local `game/install` services and task factories, and remote content to existing `addon`
  repositories and Swing download/update entrypoints. Preserve explicit repository, target instance, run directory,
  loader, and resource-type context.
- Check CurseForge/Modrinth singletons, `RemoteAddon.Type`, pagination, recommended/prerelease versions, dependency
  selection, updates, and file-operation failure feedback. Component reuse must not invent provider capabilities or
  discard local sorting, filtering, de-duplication, and parallel-install isolation.

### Settings, Accounts, and Persistence Boundaries

- Both projects separate settings/state and use JSON schemas; this does not establish identical formats or loading
  policies. Compare `SettingsManager`, `GameSettings`, `docs/schemas`, file locations, schema versions, unknown fields,
  and non-writable states.
- Local account metadata and private data are stored separately. Check local/user scopes, account IDs, authentication
  factories, token refresh, and game-profile caches. Never put private fields in ordinary settings, logs, task
  snapshots, or exports.
- Local instance-configuration migration uses `InstanceConfigMigrationPolicy`, owned by settings presets. It is not
  equivalent to upstream `LegacyConfigMigrator`, `LegacyGameSettingsMigrator`, or `MigrationReceipt`; do not directly
  introduce HMCL legacy-file scanning and automatic conversion.
- Before changing persistent formats, public APIs, or external contracts, present standalone conversion,
  application-boundary adaptation, and explicit incompatibility options with their impact and obtain confirmation. Do
  not remove old readers, add migrators, or assume HMCL files are legacy XYML data without that decision.

### Themes, Startup, and Self-Update

- Map theme changes to local `theme` models, theme-pack repositories, and Swing appearance settings. Preserve theme
  references, brightness preferences, backgrounds, and package-reading boundaries; do not restore JavaFX CSS, MonetFX,
  or upstream theme managers.
- Check Swing startup entrypoints, `UpdateApplier`, `UpdateStartupResult`, `UpdateChannel`, and `XYMLDownloadTask`
  separately for startup, error-display, and update changes. Do not copy the upstream JavaFX-to-Swing error fallback
  wholesale into Swing-only startup.
- Preserve offline startup, update-artifact integrity, replacement/restart arguments, XYML download sources, application
  IDs, `.xyml`, and `xyml.*`. Upstream versions, update endpoints, and release channels are not local defaults.

### Local Features and Security Boundaries

- Check existing worlds, resource packs, shader packs, schematics, mods, modpack export, and instance-configuration
  migration entrypoints. Before accepting added, renamed, or deleted pages, check for local equivalents. MultiMC export
  remains a local feature despite its upstream removal.
- Crash analysis, logs, repair tasks, and Java runtime management have local services and UI. Port generic fixes to
  those entrypoints; do not restore upstream pages or assume GUI and MCP repairs share all state and rollback
  boundaries.
- Core/application `mcp`, application `nbt`, `libraries/xoyz-mcp`, and `libraries/xoyz-nbt` are local boundaries.
  Preserve authentication, redaction, deletion confirmation, path restrictions, bounded parsing, and transactional
  writes; their absence upstream is not a reason to delete them.

### Bundled Libraries, Build, and Code Contracts

- Local `settings.gradle.kts` also includes `xoyz-nbt`, `xoyz-mcp`, `XYMLL`, `lwjgl-unsafe-agent`, and
  `mesa-loader-windows`. Use `libraries/README.md` and imported libraries' `SOURCE.md` to review sources, resources,
  licenses, pinned SHAs, and identity tests. Do not revert to upstream dependency coordinates or overwrite local
  changes.
- Application/Core/buildSrc use Java 17. Boot and Minecraft bootstrap production code remain Java 8;
  `lwjgl-unsafe-agent` uses a Java 25 toolchain. Check actual `build.gradle.kts` files, Wrapper, version catalogs, fat
  JARs, and `verifyOfflineUiArtifact`; the build JVM version does not establish target bytecode compatibility.
- Base development on current `dev` and integrate validated work back into `dev` with `--no-ff`; releases follow `dev ->
  alpha -> beta -> main`. Preserve `stableVersion` in `config/project.properties`, version resolution, and release
  tasks. Upstream integration does not authorize a release.
- Ported first-party Java declarations follow current `AGENTS.md`: full license headers, `@NotNullByDefault`, precise
  `@Nullable`, collection-ownership annotations, and `///` documentation. Preserve embedded third-party licenses and
  local style; do not disable quality gates.

### Documentation Default Language and Macro Migration

- Upstream unsuffixed documents are English and Simplified Chinese uses `_zh_Hans.md`. In XYML, unsuffixed documents are
  Simplified Chinese, English uses `_en.md`, and Traditional Chinese uses `_zh_Hant.md`. Map by content language; never
  overwrite a local unsuffixed file with the upstream namesake directly.
- Map relative links, anchors, `BLOCK`, `COPY` sources, and `LANGUAGE_SWITCHER` together, not filenames alone. Check
  `DocumentLocale`, the document tree, and generators in `buildSrc`; retain Simplified-Chinese-first ordering and
  missing-translation fallback.
- Unsuffixed `I18N.properties` remains the English default resource, unlike documentation. Merge resource keys,
  placeholders, and escaping individually. See the [localization
  guide](Localization_en.md#documentation-and-localization-resources).
- After any `docs/**/*.md` change, run `updateDocuments` and inspect every rewritten file, language link, and copied
  block. Do not hand-patch generated output to hide source-macro or language-mapping errors.

### Local Boundaries That Must Be Preserved

- Keep the Swing-only application and the toolkit-neutral `XYMLCore`; treat
  upstream JavaFX/JFoenix code only as a behavior reference.
- Keep `xoyz-nbt`, `xoyz-mcp`, `XYMLL`, `lwjgl-unsafe-agent`, and
  `mesa-loader-windows`, including imported libraries' `SOURCE.md`, licenses,
  upstream SHAs, and identity tests.
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
   git switch -c <integration-branch> dev
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

Add verification for the boundaries actually touched; compilation is not a substitute for behavior tests:

- Task resource waiting/cancellation, cleanup failure, registry publication, automatic/manual navigation,
  and ownership after page closure.
- Repository snapshot isolation, draft conflicts/failure recovery, and operations outside drafts;
  instance/provider/type context for installations and remote content.
- Settings/account schemas, unknown fields, non-writable states, private-data separation, and old-data round trips;
  do not migrate real user data without authorization.
- Theme-pack reading, offline startup, update integrity/replacement arguments, affected local features,
  MCP/NBT behavior, and bundled-library identity checks.

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
