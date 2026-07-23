# Swing UI migration

## Status

Accepted for implementation on branch `uniether/swing-ui-rewrite`.

This document is the implementation contract for replacing the JavaFX user
interface. Each migration commit must keep the project buildable and must not
weaken the final acceptance gates described below.

## Decision

XYML will replace JavaFX with a desktop interface built on Java 17, Swing, and
Java2D. The visual foundation will use a pinned pure-Java look and feel and a
pure-Java layout manager. UI libraries, fonts, icons, and application assets
will be bundled into the launcher artifact; the launcher must never download
UI runtime components on startup.

The migration deliberately does not use JCEF, Chromium, React, Compose, SWT,
or another platform-native UI runtime. Those options introduce a narrower
native platform matrix than the launcher currently supports.

`XYMLCore` must remain UI-toolkit-neutral. It may not expose or depend on
`javafx.*` or `javax.swing.*` types. The Swing implementation belongs in the
`XYML` application module. `XYMLBoot` keeps its small Swing-based error path
and Java 8 bytecode target so an unsupported JVM can still display a useful
upgrade message.

## Product surface

The replacement retains the existing information architecture and workflows:

- Home and game launch
- Downloads and remote search
- Game instances, mods, resource packs, worlds, and schematics
- Accounts and authentication
- Launcher and per-instance settings
- Personalization, themes, backgrounds, and accessibility settings
- Tasks, logs, crash handling, dialogs, and multi-step wizards

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

Theme mode has three values: follow system, light, and dark. Changing the mode
updates all open windows on the Swing event-dispatch thread while preserving
focus, selection, scroll position, and in-progress form values. Existing theme
pack and background settings remain the source of user intent.

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

The universal fat JAR remains the compatibility artifact for the existing OS
and CPU matrix. Pure-Java UI dependencies are merged by Shadow and may not use
a first-run downloader. Platform runtime images can be published in addition
to the universal JAR where a matching Java 17 distribution and build runner
exist.

The migration removes the OpenJFX dependency manifest, JavaFX downloader and
module patcher, JFoenix, MonetFX, `fx-gson`, `simple-png-javafx`, and JavaFX SVG
integration after the last legacy screen has been replaced.

An artifact verification task must prove that the final launcher:

- Contains all required UI classes, fonts, icons, and theme resources
- Starts with an empty external dependency directory and unavailable network
- Contains no JavaFX classes or JavaFX-specific runtime metadata
- Contains no UI-runtime download endpoints
- Produces deterministic dependency and license inventories

## Migration sequence

1. Pin and bundle the pure-Java UI dependencies.
2. Introduce UI-neutral state, task, scheduler, color, and image contracts.
3. Move `XYMLCore` consumers to the neutral contracts while JavaFX adapters
   temporarily preserve the legacy interface.
4. Implement the Swing application shell, navigation, dialogs, theme tokens,
   motion engine, and viewport-driven choice list.
5. Migrate the home, account summary, instance selection, launch command, and
   task progress as the first complete vertical workflow.
6. Migrate downloads and long instance-content lists.
7. Migrate settings, personalization, accounts, file operations, and wizards.
8. Migrate logs, crash windows, image handling, and skin preview.
9. Remove all JavaFX sources, adapters, dependencies, runtime patching, and JVM
   module-opening flags.
10. Add offline artifact verification and the cross-platform smoke-test matrix.

After the Swing downloads page gained vanilla installation, both Home and
Instances route their new-instance action internally to `DOWNLOADS`. Startup no
longer supplies a legacy add-instance workflow command; only account creation
and launch remain transitional command boundaries.

## Commit and verification policy

Each commit must compile independently and have a single migration purpose.
Before a commit is accepted, run the strongest applicable subset of:

- `gradlew.bat test checkstyle checkTranslations --no-daemon`
- UI unit tests on the event-dispatch thread
- Fixed-size screenshot tests for light and dark themes
- Keyboard, focus, high-DPI, and reduced-motion checks
- Offline fat-JAR assembly and startup smoke tests

The final migration is not complete until repository search and `jdeps` both
show no JavaFX dependency, all existing workflows have a Swing implementation,
and the offline artifact starts without downloading UI components.

## Java source policy

All new or modified Java classes follow the repository contract: every class
uses `@NotNullByDefault`; nullable types are explicit; immutable arrays and
collections use the appropriate JetBrains annotations; and every class, field,
and method has accurate `///` Markdown documentation.
