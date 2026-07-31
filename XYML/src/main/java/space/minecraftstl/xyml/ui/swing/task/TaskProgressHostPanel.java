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
package space.minecraftstl.xyml.ui.swing.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.task.presentation.TaskPresentationModel;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/// Hosts at most one [TaskProgressPanel] while accepting binding changes from any thread.
///
/// The host must be constructed on the Swing event dispatch thread. Binding, clearing, and closing are
/// thread-safe: each request advances a revision so an older queued EDT operation cannot replace newer state.
/// Models are compared by identity because two presentation objects that compare equal may still own distinct
/// subscriptions and task lifecycles. Replacing or clearing a model closes only its presentation panel; this host
/// never requests cancellation from the represented task.
@NotNullByDefault
public final class TaskProgressHostPanel extends JPanel implements AutoCloseable {
    /// Lock guarding lifecycle state, the requested model, and the request revision.
    private final Object stateLock = new Object();

    /// Serializes panel construction and mounting with clear and close barriers.
    private final Object publicationLock = new Object();

    /// Localized labels supplied to each installed task panel.
    private final TaskProgressStrings strings;

    /// Optional shared animator supplied to each installed task panel.
    private final @Nullable SwingAnimator animator;

    /// Explicit duration supplied to each installed task panel for determinate progress transitions.
    private final Duration progressAnimationDuration;

    /// Most recently requested model, or null after clearing and closing; guarded by [#stateLock].
    private @Nullable TaskPresentationModel requestedModel;

    /// Monotonic request revision used to reject superseded EDT operations; guarded by [#stateLock].
    private long revision;

    /// Whether this host no longer accepts binding changes; guarded by [#stateLock].
    private boolean closed;

    /// Panel currently installed in this host, accessed only on the Swing event dispatch thread.
    private @Nullable TaskProgressPanel installedPanel;

    /// Model represented by [#installedPanel], guarded by [#stateLock].
    private @Nullable TaskPresentationModel installedModel;

    /// Whether the installed panel has not been synchronously gated by clear or close; guarded by [#stateLock].
    private boolean installedPanelUsable;

    /// Creates an empty task progress host on the Swing event dispatch thread.
    ///
    /// A null animator or zero duration applies determinate progress directly. Timing is supplied explicitly so
    /// this host does not invent an animation duration.
    ///
    /// @param strings localized control and lifecycle text
    /// @param animator optional shared Swing animator
    /// @param progressAnimationDuration non-negative duration for determinate progress transitions
    public TaskProgressHostPanel(
            TaskProgressStrings strings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        setOpaque(false);

        this.strings = Objects.requireNonNull(strings, "strings");
        this.animator = animator;
        this.progressAnimationDuration = Objects.requireNonNull(
                progressAnimationDuration, "progressAnimationDuration");
        if (progressAnimationDuration.isNegative()) {
            throw new IllegalArgumentException("progressAnimationDuration must not be negative");
        }
    }

    /// Requests that this host present one model, replacing any previously bound presentation panel.
    ///
    /// Calls are accepted from any thread. Binding the same model instance again has no effect. Calls made after
    /// this host is closed are ignored.
    ///
    /// @param model the toolkit-neutral task presentation model to display
    public void bind(TaskPresentationModel model) {
        Objects.requireNonNull(model, "model");

        long bindingRevision;
        synchronized (publicationLock) {
            synchronized (stateLock) {
                if (closed || requestedModel == model) {
                    return;
                }

                requestedModel = model;
                bindingRevision = ++revision;
            }

            SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> installOnEventDispatchThread(model, bindingRevision));
        }
    }

    /// Clears the current logical binding and closes its presentation panel without cancelling the task.
    ///
    /// Calls are accepted from any thread. Clearing an already empty or closed host has no effect.
    public void clear() {
        @Nullable TaskProgressPanel panelToClose;
        synchronized (publicationLock) {
            synchronized (stateLock) {
                if (closed || requestedModel == null) {
                    return;
                }

                requestedModel = null;
                revision++;
                panelToClose = installedPanel;
                installedPanelUsable = false;
            }

            final @Nullable TaskProgressPanel expectedPanel = panelToClose;
            closeChildAndDispatch(
                    panelToClose,
                    () -> clearOnEventDispatchThread(expectedPanel));
        }
    }

    /// Returns the model most recently accepted by [#bind(TaskPresentationModel)].
    ///
    /// The result reflects logical state immediately, even when the corresponding EDT installation is still
    /// queued. It is empty after [#clear()] or [#close()].
    ///
    /// @return the currently requested presentation model
    public Optional<TaskPresentationModel> boundModel() {
        synchronized (stateLock) {
            return Optional.ofNullable(requestedModel);
        }
    }

    /// Permanently closes this host and releases its installed presentation panel without cancelling the task.
    ///
    /// Closing synchronously marks the host closed and advances its revision before EDT cleanup is dispatched, so
    /// an older queued bind cannot install after this method returns. Repeated calls have no effect.
    @Override
    public void close() {
        long closingRevision;
        @Nullable TaskProgressPanel panelToClose;
        synchronized (publicationLock) {
            synchronized (stateLock) {
                if (closed) {
                    return;
                }

                closed = true;
                requestedModel = null;
                closingRevision = ++revision;
                panelToClose = installedPanel;
                installedPanelUsable = false;
            }
        }

        closeChildAndDispatch(
                panelToClose,
                () -> closeOnEventDispatchThread(closingRevision));
    }

    /// Constructs and installs a task panel when the binding request is still current.
    ///
    /// @param model the model captured by the binding request
    /// @param bindingRevision the revision captured by the binding request
    private void installOnEventDispatchThread(TaskPresentationModel model, long bindingRevision) {
        EdtDispatcher.requireEventDispatchThread();

        synchronized (publicationLock) {
            synchronized (stateLock) {
                if (!isCurrentBinding(model, bindingRevision)) {
                    return;
                }
            }

            final TaskProgressPanel replacement;
            try {
                replacement = new TaskProgressPanel(
                        model,
                        strings,
                        animator,
                        progressAnimationDuration);
            } catch (RuntimeException | Error constructionFailure) {
                rollbackFailedBinding(model, bindingRevision);
                throw constructionFailure;
            }
            @Nullable TaskProgressPanel previous;
            synchronized (stateLock) {
                if (!isCurrentBinding(model, bindingRevision)) {
                    replacement.close();
                    return;
                }

                previous = installedPanel;
                installedPanel = replacement;
                installedModel = model;
                installedPanelUsable = true;
                removeAll();
                add(replacement, BorderLayout.CENTER);
                revalidate();
                repaint();
            }

            closePresentationPanel(previous);
        }
    }

    /// Synchronously gates an installed child before dispatching its host removal.
    ///
    /// Both operations are attempted so a child cleanup failure cannot leave its component permanently mounted.
    ///
    /// @param panel installed child to close, or null when no child has mounted
    /// @param removal host removal operation to dispatch
    private static void closeChildAndDispatch(
            @Nullable TaskProgressPanel panel,
            Runnable removal) {
        @Nullable Throwable closingFailure = null;
        try {
            closePresentationPanel(panel);
        } catch (RuntimeException | Error childFailure) {
            closingFailure = childFailure;
        }
        try {
            SwingUiDispatcher.INSTANCE.dispatchOrRun(removal);
        } catch (RuntimeException | Error removalFailure) {
            closingFailure = closingFailure == null
                    ? removalFailure
                    : combineUncheckedFailures(closingFailure, removalFailure);
        }
        if (closingFailure != null) {
            throw propagate(closingFailure);
        }
    }

    /// Clears a failed logical binding only when no newer bind, clear, or close request superseded it.
    ///
    /// @param model model whose panel construction failed
    /// @param bindingRevision revision captured by the failed bind
    private void rollbackFailedBinding(TaskPresentationModel model, long bindingRevision) {
        synchronized (stateLock) {
            if (isCurrentBinding(model, bindingRevision)) {
                requestedModel = installedPanelUsable ? installedModel : null;
                revision++;
            }
        }
    }

    /// Returns whether a captured bind still represents the host's requested state.
    ///
    /// The caller must hold [#stateLock].
    ///
    /// @param model the model captured by the binding request
    /// @param bindingRevision the revision captured by the binding request
    /// @return `true` when the captured bind may be installed
    private boolean isCurrentBinding(TaskPresentationModel model, long bindingRevision) {
        return !closed && revision == bindingRevision && requestedModel == model;
    }

    /// Removes the installed presentation panel when a clear request is still current.
    ///
    /// @param expectedPanel panel installed when clear was requested, or null when no panel had mounted
    private void clearOnEventDispatchThread(@Nullable TaskProgressPanel expectedPanel) {
        EdtDispatcher.requireEventDispatchThread();

        @Nullable TaskProgressPanel previous;
        synchronized (stateLock) {
            if (installedPanel != expectedPanel) {
                return;
            }

            previous = installedPanel;
            installedPanel = null;
            installedModel = null;
            installedPanelUsable = false;
        }

        removePresentationPanel(previous);
    }

    /// Removes the installed presentation panel when this close request remains current.
    ///
    /// @param closingRevision the revision captured by the close request
    private void closeOnEventDispatchThread(long closingRevision) {
        EdtDispatcher.requireEventDispatchThread();

        @Nullable TaskProgressPanel previous;
        synchronized (stateLock) {
            if (!closed || revision != closingRevision) {
                return;
            }

            previous = installedPanel;
            installedPanel = null;
            installedModel = null;
            installedPanelUsable = false;
        }

        removePresentationPanel(previous);
    }

    /// Attempts child cleanup and Swing removal independently before propagating any failure.
    ///
    /// @param panel panel to close and remove, or null when no panel was installed
    private void removePresentationPanel(@Nullable TaskProgressPanel panel) {
        @Nullable Throwable removalFailure = null;
        try {
            closePresentationPanel(panel);
        } catch (RuntimeException | Error closingFailure) {
            removalFailure = closingFailure;
        }
        try {
            removeAll();
            revalidate();
            repaint();
        } catch (RuntimeException | Error componentFailure) {
            removalFailure = removalFailure == null
                    ? componentFailure
                    : combineUncheckedFailures(removalFailure, componentFailure);
        }
        if (removalFailure != null) {
            throw propagate(removalFailure);
        }
    }

    /// Closes one presentation panel when present, without forwarding task cancellation.
    ///
    /// @param panel the panel to close, or null when no panel is installed
    private static void closePresentationPanel(@Nullable TaskProgressPanel panel) {
        if (panel != null) {
            panel.close();
        }
    }

    /// Combines unchecked failures while preventing a runtime exception from hiding an [Error].
    ///
    /// @param primary first failure
    /// @param secondary later failure
    /// @return retained primary failure with suppressed context, or the later error
    private static Throwable combineUncheckedFailures(Throwable primary, Throwable secondary) {
        if (primary == secondary) {
            return primary;
        }
        if (!(primary instanceof Error) && secondary instanceof Error) {
            secondary.addSuppressed(primary);
            return secondary;
        }
        primary.addSuppressed(secondary);
        return primary;
    }

    /// Returns or throws one unchecked failure without erasing an [Error].
    ///
    /// @param failure unchecked failure to propagate
    /// @return runtime exception when the failure is not an error
    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        return (RuntimeException) failure;
    }
}
