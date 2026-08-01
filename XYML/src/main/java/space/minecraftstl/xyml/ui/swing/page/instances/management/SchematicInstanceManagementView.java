/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.page.schematics.DefaultSchematicBrowserModel;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserInteractions;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserPanel;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserStrings;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.CardLayout;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/// Hosts a lazily resolved schematic browser for one stable game-instance identifier.
///
/// Construction occurs on the EDT and immediately presents a loading card. Schematic-root
/// resolution always runs through the injected asynchronous executor. That executor must not run
/// submitted blocking work inline on the EDT. A successful resolution returns to the EDT to create
/// the browser, while a failed resolution presents a retryable localized state.
/// Closing from any thread synchronously cancels the active resolution, prevents late publication,
/// and releases a constructed browser exactly once.
@NotNullByDefault
public final class SchematicInstanceManagementView extends JPanel implements InstanceManagementView {
    /// Card shown while the schematic root is being resolved.
    private static final String LOADING_CARD = "loading";

    /// Card shown after schematic-root resolution fails.
    private static final String FAILURE_CARD = "failure";

    /// Card containing the resolved schematic browser.
    private static final String BROWSER_CARD = "browser";

    /// Stable repository instance identifier represented by this view.
    private final GameInstanceID instanceId;

    /// Potentially blocking schematic-root resolver.
    private final SchematicDirectoryResolver directoryResolver;

    /// Caller-owned asynchronous executor used only for schematic-root resolution and browser I/O.
    private final Executor executor;

    /// Localized outer management-shell text.
    private final SchematicInstanceManagementStrings strings;

    /// Localized browser text transferred to the resolved browser.
    private final SchematicBrowserStrings browserStrings;

    /// Explicit dialog and desktop boundary transferred unchanged to the resolved browser.
    private final SchematicBrowserInteractions browserInteractions;

    /// Coordinator-owned command returning to the instance list.
    private final Runnable returnCommand;

    /// Lock protecting resolution generation and active-resolution identity.
    private final Object resolutionLock = new Object();

    /// Stable host switching among loading, failure, and browser content.
    private final JPanel contentCards = new JPanel(new CardLayout());

    /// Command returning to the instance list rather than a schematic parent directory.
    private final JButton returnButton = new JButton();

    /// Command retrying only failed schematic-root resolution.
    private final JButton retryButton = new JButton();

    /// Centered initial resolution state.
    private final JLabel loadingLabel = stateLabel("schematicInstanceLoading");

    /// Centered resolution failure state.
    private final JLabel failureLabel = stateLabel("schematicInstanceFailure");

    /// Prevents late resolutions and repeated cleanup starts.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Shared terminal result allowing concurrent close callers to await the first cleanup.
    private final CompletableFuture<@Nullable Void> closeCompletion = new CompletableFuture<>();

    /// Current resolution generation guarded by [#resolutionLock].
    private long resolutionGeneration;

    /// Active schematic-root resolution guarded by [#resolutionLock].
    private @Nullable CompletableFuture<Path> activeResolution;

    /// Resolved browser owned by this view and accessed only on the EDT.
    private @Nullable SchematicBrowserPanel browserPanel;

    /// Whether EDT-owned components and browser resources have been released.
    private boolean resourcesClosed;

    /// Creates and starts one asynchronous schematic instance-management view on the EDT.
    ///
    /// @param instanceId stable repository instance identifier
    /// @param directoryResolver potentially blocking schematic-root resolver
    /// @param executor caller-owned asynchronous executor that never runs blocking work inline on EDT
    /// @param strings localized outer-shell text
    /// @param browserStrings localized browser text
    /// @param browserInteractions explicit browser dialog and desktop boundary
    /// @param returnCommand coordinator command returning to the instance list
    public SchematicInstanceManagementView(
            GameInstanceID instanceId,
            SchematicDirectoryResolver directoryResolver,
            Executor executor,
            SchematicInstanceManagementStrings strings,
            SchematicBrowserStrings browserStrings,
            SchematicBrowserInteractions browserInteractions,
            Runnable returnCommand) {
        this(
                instanceId,
                directoryResolver,
                executor,
                strings,
                browserStrings,
                browserInteractions,
                returnCommand,
                true);
    }

    /// Creates and starts an embeddable schematic management view on the EDT.
    ///
    /// @param instanceId stable repository instance identifier
    /// @param directoryResolver potentially blocking schematic-root resolver
    /// @param executor caller-owned asynchronous executor that never runs blocking work inline on EDT
    /// @param strings localized outer-shell text
    /// @param browserStrings localized browser text
    /// @param browserInteractions explicit browser dialog and desktop boundary
    /// @param returnCommand coordinator command returning to the instance list
    /// @param showReturnToolbar whether this view owns the top-level return toolbar
    SchematicInstanceManagementView(
            GameInstanceID instanceId,
            SchematicDirectoryResolver directoryResolver,
            Executor executor,
            SchematicInstanceManagementStrings strings,
            SchematicBrowserStrings browserStrings,
            SchematicBrowserInteractions browserInteractions,
            Runnable returnCommand,
            boolean showReturnToolbar) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                showReturnToolbar ? "[]12[grow,fill]" : "[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.directoryResolver = Objects.requireNonNull(directoryResolver, "directoryResolver");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.browserStrings = Objects.requireNonNull(browserStrings, "browserStrings");
        this.browserInteractions = Objects.requireNonNull(browserInteractions, "browserInteractions");
        this.returnCommand = Objects.requireNonNull(returnCommand, "returnCommand");

        setName("schematicInstanceManagement");
        setOpaque(false);
        contentCards.setOpaque(false);
        configureComponents(showReturnToolbar);
        startResolution();
    }

    /// Returns the stable repository identifier represented by this view.
    ///
    /// @return stable instance identifier
    @Override
    public GameInstanceID instanceId() {
        return instanceId;
    }

    /// Returns this management root for coordinator hosting on the EDT.
    ///
    /// @return this view component
    @Override
    public JComponent component() {
        EdtDispatcher.requireEventDispatchThread();
        return this;
    }

    /// Cancels active resolution and synchronously releases every EDT-owned resource exactly once.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            awaitExistingClose();
            return;
        }

        @Nullable CompletableFuture<Path> resolution;
        synchronized (resolutionLock) {
            resolutionGeneration++;
            resolution = activeResolution;
            activeResolution = null;
        }
        if (resolution != null) {
            resolution.cancel(true);
        }

        @Nullable Throwable failure = null;
        try {
            EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
        } catch (RuntimeException | Error closingFailure) {
            failure = closingFailure;
        }
        if (failure == null) {
            closeCompletion.complete(null);
        } else {
            closeCompletion.completeExceptionally(failure);
            rethrowUnchecked(failure);
        }
    }

    /// Builds stable toolbar and lifecycle cards before any background work can complete.
    /// Builds stable lifecycle cards and an optional top-level return toolbar.
    ///
    /// @param showReturnToolbar whether this view owns the top-level return toolbar
    private void configureComponents(boolean showReturnToolbar) {
        EdtDispatcher.requireEventDispatchThread();
        if (showReturnToolbar) {
            JPanel toolbar = new JPanel(new MigLayout("insets 0, fillx", "[][grow,fill]", "[40!]"));
            toolbar.setOpaque(false);

            returnButton.setName("schematicInstanceReturn");
            returnButton.setText(strings.returnAction());
            returnButton.setToolTipText(strings.returnTooltip());
            returnButton.setIcon(new FlatSVGIcon("assets/swing/icons/arrow-back.svg", 18, 18));
            returnButton.addActionListener(event -> {
                if (!closed.get()) {
                    returnCommand.run();
                }
            });
            toolbar.add(returnButton, "h 40!");
            add(toolbar, "growx");
        }

        loadingLabel.setText(strings.loadingText());
        contentCards.add(loadingLabel, LOADING_CARD);

        JPanel failurePanel = new JPanel(new MigLayout(
                "insets 24, fill, wrap 1",
                "[grow,center]",
                "[grow,center]12[]"));
        failurePanel.setOpaque(false);
        failurePanel.add(failureLabel, "growx");
        retryButton.setName("schematicInstanceRetry");
        retryButton.setText(strings.retryAction());
        retryButton.setEnabled(false);
        retryButton.addActionListener(event -> {
            if (!closed.get()) {
                startResolution();
            }
        });
        failurePanel.add(retryButton, "h 40!");
        contentCards.add(failurePanel, FAILURE_CARD);
        add(contentCards, "grow");

        showCard(LOADING_CARD);
    }

    /// Cancels an earlier attempt, shows loading, and schedules one fresh root resolution.
    private void startResolution() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }

        @Nullable CompletableFuture<Path> previous;
        long generation;
        synchronized (resolutionLock) {
            if (closed.get()) {
                return;
            }
            generation = ++resolutionGeneration;
            previous = activeResolution;
            activeResolution = null;
        }
        if (previous != null) {
            previous.cancel(true);
        }

        loadingLabel.setText(strings.loadingText());
        retryButton.setEnabled(false);
        showCard(LOADING_CARD);

        CompletableFuture<Path> resolution;
        try {
            resolution = CompletableFuture.supplyAsync(this::resolveDirectory, executor);
        } catch (RuntimeException | Error schedulingFailure) {
            showResolutionFailure(generation, schedulingFailure);
            return;
        }

        synchronized (resolutionLock) {
            if (closed.get() || resolutionGeneration != generation) {
                resolution.cancel(true);
                return;
            }
            activeResolution = resolution;
        }
        resolution.whenComplete((@Nullable Path directory, @Nullable Throwable failure) ->
                EdtDispatcher.execute(
                        () -> finishResolution(generation, resolution, directory, failure)));
    }

    /// Resolves and normalizes the schematic root on the injected executor.
    ///
    /// @return normalized absolute schematic root
    private Path resolveDirectory() {
        try {
            return Objects.requireNonNull(
                    directoryResolver.resolve(instanceId),
                    "schematic directory resolver returned null")
                    .toAbsolutePath()
                    .normalize();
        } catch (java.io.IOException failure) {
            throw new CompletionException(failure);
        }
    }

    /// Applies one current successful or failed resolution on the EDT.
    ///
    /// @param generation resolution ownership generation
    /// @param resolution exact future completing this callback
    /// @param directory resolved directory, or null after failure
    /// @param failure completion failure, or null after success
    private void finishResolution(
            long generation,
            CompletableFuture<Path> resolution,
            @Nullable Path directory,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        synchronized (resolutionLock) {
            if (closed.get()
                    || resolutionGeneration != generation
                    || activeResolution != resolution) {
                return;
            }
            activeResolution = null;
        }

        @Nullable Throwable resolvedFailure = unwrapFailure(failure);
        if (resolvedFailure != null) {
            if (!(resolvedFailure instanceof CancellationException)) {
                showFailure(resolvedFailure);
            }
            return;
        }
        if (directory == null) {
            showFailure(new IllegalStateException("Schematic directory resolution completed without a path"));
            return;
        }
        createBrowser(directory);
    }

    /// Shows a failure that occurred before a resolution future could be registered.
    ///
    /// @param generation failed resolution generation
    /// @param failure scheduling or immediate resolution failure
    private void showResolutionFailure(long generation, Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        synchronized (resolutionLock) {
            if (closed.get() || resolutionGeneration != generation) {
                return;
            }
            activeResolution = null;
        }
        showFailure(failure);
    }

    /// Creates and mounts the browser transactionally after root resolution.
    ///
    /// @param directory normalized schematic root
    private void createBrowser(Path directory) {
        EdtDispatcher.requireEventDispatchThread();
        DefaultSchematicBrowserModel model = new DefaultSchematicBrowserModel(directory, executor);
        @Nullable SchematicBrowserPanel created = null;
        try {
            created = new SchematicBrowserPanel(model, browserStrings, browserInteractions);
            created.setName("schematicInstanceBrowser");
            contentCards.add(created, BROWSER_CARD);
            browserPanel = created;
            showCard(BROWSER_CARD);
        } catch (RuntimeException | Error creationFailure) {
            @Nullable Throwable cleanupFailure = creationFailure;
            if (created == null) {
                cleanupFailure = attemptCleanup(cleanupFailure, model::close);
            } else {
                cleanupFailure = attemptCleanup(cleanupFailure, created::close);
                contentCards.remove(created);
            }
            browserPanel = null;
            showFailure(Objects.requireNonNull(cleanupFailure));
        }
    }

    /// Presents one localized retryable root-resolution or browser-construction failure.
    ///
    /// @param failure failure whose message may add diagnostic detail
    private void showFailure(Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        @Nullable String message = failure.getMessage();
        String text = message == null || message.isBlank()
                ? strings.failureTitle()
                : strings.failureTitle() + ": " + message;
        failureLabel.setText(text);
        failureLabel.setToolTipText(text);
        retryButton.setEnabled(true);
        showCard(FAILURE_CARD);
    }

    /// Shows one stable lifecycle card and refreshes its layout.
    ///
    /// @param card stable card identifier
    private void showCard(String card) {
        ((CardLayout) contentCards.getLayout()).show(contentCards, card);
        contentCards.revalidate();
        contentCards.repaint();
    }

    /// Releases browser and component state on the EDT while attempting every cleanup step.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        if (resourcesClosed) {
            return;
        }
        resourcesClosed = true;

        @Nullable SchematicBrowserPanel currentBrowser = browserPanel;
        browserPanel = null;
        @Nullable Throwable failure = null;
        if (currentBrowser != null) {
            failure = attemptCleanup(failure, currentBrowser::close);
            failure = attemptCleanup(failure, () -> contentCards.remove(currentBrowser));
        }
        failure = attemptCleanup(failure, () -> returnButton.setEnabled(false));
        failure = attemptCleanup(failure, () -> retryButton.setEnabled(false));
        failure = attemptCleanup(failure, contentCards::removeAll);
        rethrowUncheckedIfPresent(failure);
    }

    /// Awaits first-caller cleanup without deadlocking an EDT reentrant close.
    private void awaitExistingClose() {
        if (SwingUtilities.isEventDispatchThread() && !closeCompletion.isDone()) {
            return;
        }
        try {
            closeCompletion.join();
        } catch (CompletionException failure) {
            rethrowUnchecked(Objects.requireNonNull(failure.getCause(), "close failure had no cause"));
        }
    }

    /// Unwraps completion wrappers while preserving cancellation identity.
    ///
    /// @param failure completion failure, or null
    /// @return underlying failure, or null
    private static @Nullable Throwable unwrapFailure(@Nullable Throwable failure) {
        @Nullable Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /// Attempts one cleanup step and retains the first failure identity.
    ///
    /// @param current earlier cleanup failure, or null
    /// @param cleanup cleanup action
    /// @return first failure with later failures suppressed, or null
    private static @Nullable Throwable attemptCleanup(
            @Nullable Throwable current,
            Runnable cleanup) {
        try {
            cleanup.run();
            return current;
        } catch (RuntimeException | Error failure) {
            if (current == null) {
                return failure;
            }
            if (current != failure) {
                current.addSuppressed(failure);
            }
            return current;
        }
    }

    /// Rethrows an optional unchecked cleanup failure.
    ///
    /// @param failure cleanup failure, or null
    private static void rethrowUncheckedIfPresent(@Nullable Throwable failure) {
        if (failure != null) {
            rethrowUnchecked(failure);
        }
    }

    /// Rethrows one unchecked lifecycle failure without changing its identity.
    ///
    /// @param failure lifecycle failure
    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected checked schematic-management failure", failure);
    }

    /// Creates a centered lifecycle label with a stable component name.
    ///
    /// @param name stable component name
    /// @return centered label
    private static JLabel stateLabel(String name) {
        JLabel label = new JLabel("", SwingConstants.CENTER);
        label.setName(name);
        return label;
    }

    /// Validates one required non-blank text value.
    ///
    /// @param value source value
    /// @param name parameter name
    /// @return validated value
    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
