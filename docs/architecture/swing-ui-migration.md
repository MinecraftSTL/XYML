# Swing UI migration

## Status

Implemented on branch `uniether/swing-ui-rewrite`.

This document records the completed desktop UI architecture and the invariants
that subsequent changes must preserve. The application UI is Swing-only; no
JavaFX implementation, bridge, adapter, dependency, or runtime downloader is
part of the current design.

## Decision

XYML uses a desktop interface built on Java 17, Swing, and Java2D. The visual
foundation uses a pinned pure-Java look and feel and a pure-Java layout
manager. UI libraries, fonts, icons, and application assets are bundled into
the launcher artifact; startup does not download UI runtime components.

The migration deliberately does not use JCEF, Chromium, React, Compose, SWT,
or another platform-native UI runtime. Those options introduce a narrower
native platform matrix than the launcher currently supports.

`XYMLCore` remains UI-toolkit-neutral. It does not expose or depend on
`javafx.*` or `javax.swing.*` types. The Swing implementation belongs in the
`XYML` application module. `XYMLBoot` keeps its small Swing-based error path
and Java 8 bytecode target so an unsupported JVM can still display a useful
upgrade message.

## Product surface

The Swing application retains the operational workflows:

- Downloads and remote search
- Game instances, versions, mods, resource packs, worlds, and schematics
- Accounts and authentication
- Launcher and per-instance settings
- Personalization, themes, backgrounds, and accessibility settings
- Tasks, logs, crash handling, dialogs, and multi-step wizards

There is no independent home page, announcement page, or announcement prompt
system. The instance list is the default main surface. Downloads, settings,
accounts, and other full-content pages are displayed over that surface; closing
the active page reveals the instance list again.

The persistent application toolbar exposes the current account selector, the
current game-instance selector, and the launch action. Account and instance
selection are therefore available without routing through a home page, while
their management pages remain reachable from navigation and selector actions.

The interface is an operational desktop tool. It uses stable navigation,
compact controls, list- and table-oriented content, and restrained framing.
It must not turn data-heavy views into card grids or add marketing content.

## Design system

The initial visual specification uses true white or neutral charcoal page
backgrounds, charcoal or white primary text, a restrained grass-green action
color, and small sky-blue and warm-red semantic accents. Purple, beige, brown,
dark-navy monocultures, decorative gradients, glows, glass effects, and nested
cards are out of scope.

Typography is defined separately for page titles, body text, captions, list
rows, form labels, buttons, navigation, and status surfaces. Control typography
must never rely on Swing defaults accidentally inherited from the host system.

Icons use one bundled SVG family with consistent stroke, fill, optical size,
alignment, and selected/disabled treatment. Text glyphs are not substitutes
for navigation arrows, disclosure controls, or common commands.

### Corner radius

Corner radius is a persisted user setting rather than a hard-coded component
constant. The selected value is expressed as a device-independent design token
and applied consistently to buttons, text inputs, list selections, panels,
dialogs, and scroll surfaces. Controls that must remain circular derive their
radius from their measured size.

Top-level windows retain system decorations. Transparent, custom-shaped windows
are not used because their behavior is inconsistent across Linux, BSD, window
managers, and accessibility tools.

### Theme mode

Brightness preference has four values: `THEME`, `SYSTEM`, `LIGHT`, and `DARK`.
`THEME` follows the selected theme pack, `SYSTEM` follows the operating-system
appearance, and the other two values force the corresponding brightness.
Changing the preference updates all open windows on the Swing event-dispatch
thread while preserving focus, selection, scroll position, and in-progress
form values. Theme-pack and background settings remain the source of the rest
of the user's appearance intent.

### Motion

Motion is limited to page transitions, dialog appearance, selection movement,
expand/collapse state, and theme transitions. The animation engine uses
`javax.swing.Timer` and monotonic elapsed time. It must not animate every row in
a long list or perform blocking work on the event-dispatch thread.

The existing animation-disabled preference remains authoritative. A reduced
motion policy can shorten or remove spatial transitions without hiding state
changes.

## Viewport-driven single-choice lists

Large single-choice lists use a `JList` with `SINGLE_SELECTION` and a reusable
cell renderer. They must not create one `JRadioButton` per item. Selection is
represented by list state and painted by the renderer.

Loading is driven by the measured viewport, not by an arbitrary default page
size or a fixed number of cached pages:

1. The model waits until layout provides an actual viewport height and a
   measured renderer row height.
2. It derives the visible item range from those measurements.
3. It requests the visible range and a predictive range derived from scroll
   direction, scroll velocity, observed load latency, and data-source bounds.
4. It cancels obsolete requests after search, filter, source, or lifecycle
   changes.
5. It pins the selected item and current focus even when they are outside the
   visible range.
6. It reports loading and retry states as real list rows without changing the
   geometry of already rendered items.

Remote providers keep their server-defined pagination contract. The UI maps
provider pages into viewport ranges without exposing page size to the user.
Local providers divide work by an event-dispatch-thread time budget and publish
incremental immutable snapshots.

Cache retention is governed by measured item weight, available memory, current
memory pressure, selection/focus pins, distance from the viewport, and recent
access. It is not expressed as a fixed number of pages. Instrumentation records
load latency, cache hits, evictions, blank-frame incidents, and event-dispatch
thread stalls so policy changes are evidence-based.

## State and threading

JavaFX properties and observable collections are replaced by plain values,
immutable snapshots, explicit commands, and documented change events. Simple
bean state may use `PropertyChangeSupport`; task and collection state uses
domain-specific event types so callers do not depend on a UI toolkit.

Business work never runs on the Swing event-dispatch thread. UI updates pass
through a single documented dispatcher backed by
`SwingUtilities.invokeLater`. Background operations remain cancellable and
must discard stale results before they reach a closed or superseded view.

Images use toolkit-neutral encoded data or `BufferedImage` at the application
boundary. Core color values use a toolkit-neutral immutable representation and
are converted to `java.awt.Color` only inside the Swing module.

## Offline artifact

The universal fat JAR is the dependency-complete launcher artifact. Pure-Java
UI dependencies and resources are merged by Shadow and do not use a first-run
downloader. Platform runtime images can be produced with `jlink` and
`jpackage` in addition to the universal JAR where a matching Java 17
distribution and packaging toolchain exist.

The current application contains no OpenJFX dependency manifest, JavaFX
downloader or module patcher, JFoenix, MonetFX, `fx-gson`,
`simple-png-javafx`, or JavaFX SVG integration.

The offline-artifact verification task checks that the launcher:

- Contains all required UI classes, fonts, icons, and theme resources
- Starts with an empty external dependency directory and unavailable network
- Contains no JavaFX classes or JavaFX-specific runtime metadata
- Contains no UI-runtime download endpoints
- Produces deterministic dependency and license inventories

## Implemented architecture

The completed implementation has these boundaries:

1. Pure-Java UI dependencies and resources are pinned and bundled with the
   application.
2. UI-neutral state, task, scheduler, color, and image contracts keep
   `XYMLCore` independent of Swing.
3. The Swing application shell owns navigation, dialogs, theme tokens, motion,
   viewport-driven lists, task presentation, logs, and crash windows.
4. Account, instance, download, settings, personalization, file-operation, and
   wizard workflows use the Swing presentation directly.
5. New-instance actions route to the downloads workflow; the instance list is
   the default surface before and after an overlaid page is closed.
6. JavaFX sources, dependencies, runtime patching, module-opening flags, and
   UI-runtime downloads are absent.
7. Current presentation and application contracts are direct Swing contracts.
   There is no transitional or legacy UI adapter layer.

## Verification policy

UI changes must preserve the offline and toolkit boundaries above. Run the
strongest applicable subset of:

- `gradlew.bat test checkstyle checkTranslations --no-daemon`
- UI unit tests on the event-dispatch thread
- Fixed-size screenshot tests for light and dark themes
- Keyboard, focus, high-DPI, and reduced-motion checks
- Offline fat-JAR assembly and startup smoke tests

Repository search, dependency inspection, and offline-artifact verification
guard against JavaFX or downloadable UI-runtime regressions. Screenshot checks
and platform-specific `jpackage` installer runs are release-validation evidence
and must be reported only for the environments in which they were actually
executed; this architecture record does not imply unrecorded cross-platform
installer or screenshot results.

## Java source policy

All new or modified Java classes follow the repository contract: every class
uses `@NotNullByDefault`; nullable types are explicit; immutable arrays and
collections use the appropriate JetBrains annotations; and every class, field,
and method has accurate `///` Markdown documentation.
