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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Font;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/// Provides the real rename, duplicate, and delete lifecycle controls for one managed instance.
///
/// Input and irreversible-action confirmations use native dialogs on the EDT. Every repository and
/// filesystem mutation runs on the caller-owned executor. After a successful mutation, the panel
/// reconciles the selected instance on the EDT and calls the owner return command because its original
/// management view has become stale.
@NotNullByDefault
public final class InstanceLifecyclePanel extends JPanel implements AutoCloseable {
    /// Stable source instance identifier represented by this management view.
    private final GameInstanceID instanceId;

    /// Background repository mutation boundary.
    private final InstanceLifecycleService service;

    /// Caller-owned executor for blocking repository and filesystem work.
    private final Executor executor;

    /// Immutable visible text for labels, controls, and dialogs.
    private final InstanceLifecycleStrings strings;

    /// Native dialog boundary.
    private final InstanceLifecycleInteractions interactions;

    /// Coordinator command that returns from the now-stale management view after a mutation.
    private final Runnable mutationCompletedCommand;

    /// Visible current source identifier.
    private final JLabel instanceIdValue = new JLabel();

    /// Starts a name-change request.
    private final JButton renameButton = new JButton();

    /// Starts a duplicate request with an optional world copy.
    private final JButton duplicateButton = new JButton();

    /// Starts a confirmed destructive deletion.
    private final JButton deleteButton = new JButton();

    /// Concise operation feedback retained until the panel is disposed.
    private final JLabel statusLabel = new JLabel();

    /// Gates future control changes and late background completions after close begins.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Serializes mutually exclusive lifecycle operations.
    private final AtomicBoolean operationPending = new AtomicBoolean();

    /// Creates a production lifecycle page for one XYML-managed repository instance.
    ///
    /// @param repository repository containing the instance
    /// @param instanceId stable non-blank managed instance identifier
    /// @param executor caller-owned executor for blocking file operations
    /// @param mutationCompletedCommand return-to-list command after a successful mutation
    public InstanceLifecyclePanel(
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            Executor executor,
            Runnable mutationCompletedCommand) {
        this(
                instanceId,
                new RepositoryInstanceLifecycleService(Objects.requireNonNull(repository, "repository")),
                executor,
                InstanceLifecycleStrings.localized(),
                new SwingInstanceLifecycleInteractions(InstanceLifecycleStrings.localized()),
                mutationCompletedCommand);
    }

    /// Creates a lifecycle page with injectable services and native interactions for deterministic tests.
    ///
    /// @param instanceId stable non-blank managed instance identifier
    /// @param service lifecycle repository boundary
    /// @param executor caller-owned executor for blocking file operations
    /// @param strings immutable visible text
    /// @param interactions native dialog boundary
    /// @param mutationCompletedCommand return-to-list command after a successful mutation
    InstanceLifecyclePanel(
            GameInstanceID instanceId,
            InstanceLifecycleService service,
            Executor executor,
            InstanceLifecycleStrings strings,
            InstanceLifecycleInteractions interactions,
            Runnable mutationCompletedCommand) {
        super(new MigLayout(
                "insets 20, fillx, wrap 2",
                "[160!][grow,fill]",
                "[]16[]16[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.service = Objects.requireNonNull(service, "service");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.mutationCompletedCommand = Objects.requireNonNull(
                mutationCompletedCommand,
                "mutationCompletedCommand");
        configureComponents();
    }

    /// Returns the visible tab title.
    ///
    /// @return non-blank lifecycle page title
    public String title() {
        return strings.title();
    }

    /// Releases controls and ignores late executor completions exactly once.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EdtDispatcher.executeAndWait(() -> {
            operationPending.set(false);
            setControlsEnabled(false);
            removeAll();
        });
    }

    /// Builds the stable identity row, lifecycle commands, and compact status text.
    private void configureComponents() {
        setName("instanceLifecyclePage");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());

        JLabel title = new JLabel(strings.title());
        title.setName("instanceLifecycleTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20.0F));
        add(title, "span 2, growx");

        JLabel instanceNameLabel = new JLabel(strings.instanceNameLabel());
        instanceNameLabel.setName("instanceLifecycleNameLabel");
        add(instanceNameLabel, "aligny center");
        instanceIdValue.setName("instanceLifecycleName");
        instanceIdValue.setText(instanceId.id());
        add(instanceIdValue, "growx");

        JPanel actions = new JPanel(new MigLayout(
                "insets 0, gap 8",
                "[][][]",
                "[40!]"));
        actions.setName("instanceLifecycleActions");
        actions.setOpaque(false);
        configureAction(renameButton, "instanceLifecycleRename", strings.renameAction(), this::requestRename);
        configureAction(duplicateButton, "instanceLifecycleDuplicate", strings.duplicateAction(), this::requestDuplicate);
        configureAction(deleteButton, "instanceLifecycleDelete", strings.deleteAction(), this::requestDelete);
        actions.add(renameButton, "h 40!");
        actions.add(duplicateButton, "h 40!");
        actions.add(deleteButton, "h 40!");
        add(actions, "span 2, right");

        statusLabel.setName("instanceLifecycleStatus");
        add(statusLabel, "span 2, growx");
        updateActionState();
    }

    /// Configures one clear command button with accessible action text.
    ///
    /// @param button target button
    /// @param name stable component name
    /// @param text visible and accessible action text
    /// @param action EDT command implementation
    private static void configureAction(JButton button, String name, String text, Runnable action) {
        JButton target = Objects.requireNonNull(button, "button");
        String actionName = requireNonBlank(name, "name");
        String actionText = requireNonBlank(text, "text");
        target.setName(actionName);
        target.setText(actionText);
        target.setToolTipText(actionText);
        target.getAccessibleContext().setAccessibleName(actionText);
        target.addActionListener(event -> Objects.requireNonNull(action, "action").run());
    }

    /// Opens the native rename prompt and schedules a validated rename only after confirmation.
    private void requestRename() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isInteractive()) {
            return;
        }
        @Nullable String rawDestination = interactions.requestRename(this, instanceId);
        @Nullable GameInstanceID destination = normalizeDestination(rawDestination, MutationKind.RENAME);
        if (destination == null) {
            return;
        }
        submitMutation(MutationKind.RENAME, destination, false);
    }

    /// Opens the native duplicate confirmation and schedules copying only after confirmation.
    private void requestDuplicate() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isInteractive()) {
            return;
        }
        @Nullable InstanceLifecycleDuplicateRequest request = interactions.requestDuplicate(this, instanceId);
        if (request == null) {
            return;
        }
        @Nullable GameInstanceID destination = normalizeDestination(request.destinationId(), MutationKind.DUPLICATE);
        if (destination == null) {
            return;
        }
        submitMutation(MutationKind.DUPLICATE, destination, request.copySaves());
    }

    /// Confirms a destructive deletion before scheduling its filesystem work.
    private void requestDelete() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isInteractive() || !interactions.confirmDelete(this, instanceId)) {
            return;
        }
        submitMutation(MutationKind.DELETE, null, false);
    }

    /// Normalizes and syntactically validates one native-dialog destination before scheduling work.
    ///
    /// @param rawDestination dialog input, or `null` after cancellation
    /// @param kind requested rename or duplicate operation
    /// @return normalized valid destination, or `null` when cancelled or invalid
    private @Nullable GameInstanceID normalizeDestination(@Nullable String rawDestination, MutationKind kind) {
        MutationKind requestedKind = Objects.requireNonNull(kind, "kind");
        if (rawDestination == null) {
            return null;
        }
        String destination = rawDestination.trim();
        if (destination.equals(instanceId.id()) || !service.isValidDestinationId(destination)) {
            showFailure(failureTitle(requestedKind), failureStatus(requestedKind));
            return null;
        }
        return new GameInstanceID(destination);
    }

    /// Starts one mutually exclusive background mutation and applies its result on the EDT.
    ///
    /// @param kind requested mutation kind
    /// @param destinationId target destination, or `null` for deletion
    /// @param copySaves whether duplication should include worlds
    private void submitMutation(
            MutationKind kind,
            @Nullable GameInstanceID destinationId,
            boolean copySaves) {
        EdtDispatcher.requireEventDispatchThread();
        MutationKind requestedKind = Objects.requireNonNull(kind, "kind");
        if (!operationPending.compareAndSet(false, true) || closed.get()) {
            return;
        }
        updateActionState();
        statusLabel.setText(strings.workingStatus());
        try {
            executor.execute(() -> runMutationOnExecutor(requestedKind, destinationId, copySaves));
        } catch (RuntimeException failure) {
            completeMutation(requestedKind, destinationId, failure);
        } catch (Error failure) {
            completeMutation(requestedKind, destinationId, failure);
            throw failure;
        }
    }

    /// Executes one repository operation away from the Swing event-dispatch thread.
    ///
    /// @param kind requested mutation kind
    /// @param destinationId target destination, or `null` for deletion
    /// @param copySaves whether duplication should include worlds
    private void runMutationOnExecutor(
            MutationKind kind,
            @Nullable GameInstanceID destinationId,
            boolean copySaves) {
        try {
            requireBackgroundThread();
            switch (kind) {
                case RENAME -> service.rename(instanceId, requireDestination(destinationId));
                case DUPLICATE -> service.duplicate(instanceId, requireDestination(destinationId), copySaves);
                case DELETE -> service.delete(instanceId);
            }
            EdtDispatcher.execute(() -> completeMutation(kind, destinationId, null));
        } catch (Exception | Error failure) {
            EdtDispatcher.execute(() -> completeMutation(kind, destinationId, failure));
        }
    }

    /// Applies one mutation completion, selecting any newly created instance before returning to the list.
    ///
    /// @param kind completed mutation kind
    /// @param destinationId target destination, or `null` for deletion
    /// @param failure mutation failure, or `null` after success
    private void completeMutation(
            MutationKind kind,
            @Nullable GameInstanceID destinationId,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        operationPending.set(false);
        if (closed.get()) {
            return;
        }
        if (failure != null) {
            statusLabel.setText(failureStatus(kind));
            updateActionState();
            showFailure(failureTitle(kind), failureDetail(failure));
            return;
        }
        try {
            service.reconcileSelection(kind == MutationKind.DELETE ? null : requireDestination(destinationId));
            statusLabel.setText(strings.successStatus());
            mutationCompletedCommand.run();
        } catch (RuntimeException completionFailure) {
            statusLabel.setText(failureStatus(kind));
            updateActionState();
            showFailure(failureTitle(kind), failureDetail(completionFailure));
        }
    }

    /// Resolves the correct localized failure title for one mutation type.
    ///
    /// @param kind failed mutation kind
    /// @return non-blank visible failure title
    private String failureTitle(MutationKind kind) {
        return switch (kind) {
            case RENAME -> strings.renameAction();
            case DUPLICATE -> strings.duplicateAction();
            case DELETE -> strings.deleteAction();
        };
    }

    /// Resolves the correct localized fallback status for one failed mutation type.
    ///
    /// @param kind failed mutation kind
    /// @return non-blank visible failure status
    private String failureStatus(MutationKind kind) {
        return switch (kind) {
            case RENAME -> strings.renameFailure();
            case DUPLICATE -> strings.duplicateFailure();
            case DELETE -> strings.deleteFailure();
        };
    }

    /// Displays one native failure without allowing a blank diagnostic detail.
    ///
    /// @param title visible failure title
    /// @param detail non-blank failure detail
    private void showFailure(String title, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        interactions.showFailure(this, requireNonBlank(title, "title"), requireNonBlank(detail, "detail"));
    }

    /// Extracts a stable diagnostic without exposing a null or empty exception message.
    ///
    /// @param failure terminal exception
    /// @return non-blank human-readable failure detail
    private static String failureDetail(Throwable failure) {
        Throwable source = Objects.requireNonNull(failure, "failure");
        @Nullable String message = source.getMessage();
        return message == null || message.isBlank() ? source.getClass().getSimpleName() : message;
    }

    /// Returns whether dialogs and mutations may begin from the current panel lifecycle state.
    ///
    /// @return whether the panel is open and idle
    private boolean isInteractive() {
        return !closed.get() && !operationPending.get();
    }

    /// Synchronizes all lifecycle command availability with the panel's close and mutation state.
    private void updateActionState() {
        EdtDispatcher.requireEventDispatchThread();
        setControlsEnabled(isInteractive());
    }

    /// Applies one uniform enabled state to all actionable lifecycle controls.
    ///
    /// @param enabled whether lifecycle operations may begin
    private void setControlsEnabled(boolean enabled) {
        renameButton.setEnabled(enabled);
        duplicateButton.setEnabled(enabled);
        deleteButton.setEnabled(enabled);
    }

    /// Requires the actual file mutation path to execute outside the EDT.
    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Instance lifecycle filesystem work must not run on the EDT");
        }
    }

    /// Returns a non-null destination required by rename and duplication execution paths.
    ///
    /// @param destinationId target destination, or `null` for a malformed operation
    /// @return non-blank target destination
    private static GameInstanceID requireDestination(@Nullable GameInstanceID destinationId) {
        if (destinationId == null) {
            throw new IllegalStateException("A destination is required for this instance lifecycle operation");
        }
        return destinationId;
    }

    /// Validates a required text value before it reaches a component or repository boundary.
    ///
    /// @param value source text
    /// @param name parameter name
    /// @return validated text
    private static String requireNonBlank(String value, String name) {
        String candidate = Objects.requireNonNull(value, name);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return candidate;
    }

    /// Distinguishes lifecycle mutations that have different arguments and localized failure states.
    @NotNullByDefault
    private enum MutationKind {
        /// Renames the currently managed source instance.
        RENAME,

        /// Duplicates the currently managed source instance.
        DUPLICATE,

        /// Removes the currently managed source instance.
        DELETE
    }
}
