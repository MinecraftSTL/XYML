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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Owns instance-settings status, persistence, reload, and read-only recovery controls.
@NotNullByDefault
final class InstanceGameSettingsFooterControls {
    /// Store providing optional backup-and-overwrite recovery.
    private final InstanceGameSettingsStore store;

    /// Saves the complete editor state.
    private final Runnable saveAction;

    /// Reloads the complete editor state.
    private final Runnable reloadAction;

    /// Obtains explicit user consent before destructive recovery.
    private final BooleanSupplier overwriteConfirmation;

    /// Unframed footer component.
    private final JPanel component = new JPanel(
            new MigLayout("insets 10 20 14 20, fillx", "[grow,fill][]8[]8[]", "[]"));

    /// Concise persistence and validation state.
    private final JLabel statusLabel = new JLabel();

    /// Backup-and-overwrite command visible only for recoverable read-only files.
    private final JButton forceOverwriteButton = new JButton(i18n("settings.file.force_write"));

    /// Reload command.
    private final JButton reloadButton = new JButton(i18n("button.refresh"));

    /// Save command.
    private final JButton saveButton = new JButton(i18n("button.save"));

    /// Current asynchronous recovery executor, or null when no recovery is running.
    private @Nullable TaskExecutor forceOverwriteExecutor;

    /// Listener registration for the current asynchronous recovery executor.
    private @Nullable Subscription forceOverwriteSubscription;

    /// Last availability values supplied by the owning editor.
    private boolean writable;
    private boolean interactive;

    /// Creates production footer controls with a native destructive-action confirmation.
    ///
    /// @param store backing instance settings store
    /// @param saveAction validated save command
    /// @param reloadAction durable reload command
    InstanceGameSettingsFooterControls(
            InstanceGameSettingsStore store,
            Runnable saveAction,
            Runnable reloadAction) {
        this(store, saveAction, reloadAction, null);
    }

    /// Creates footer controls with an optional deterministic confirmation for focused tests.
    ///
    /// @param store backing instance settings store
    /// @param saveAction validated save command
    /// @param reloadAction durable reload command
    /// @param overwriteConfirmation confirmation supplier, or `null` for the native dialog
    InstanceGameSettingsFooterControls(
            InstanceGameSettingsStore store,
            Runnable saveAction,
            Runnable reloadAction,
            @Nullable BooleanSupplier overwriteConfirmation) {
        this.store = Objects.requireNonNull(store, "store");
        this.saveAction = Objects.requireNonNull(saveAction, "saveAction");
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
        this.overwriteConfirmation = overwriteConfirmation == null
                ? this::showOverwriteConfirmation
                : overwriteConfirmation;
        configureComponents();
    }

    /// Returns the unframed footer component.
    ///
    /// @return configured footer
    JPanel component() {
        return component;
    }

    /// Replaces the concise footer status.
    ///
    /// @param text localized status text
    void setStatus(String text) {
        statusLabel.setText(Objects.requireNonNull(text, "text"));
    }

    /// Updates command availability for the current durable snapshot.
    ///
    /// @param writable whether ordinary persistence is available
    /// @param interactive whether the owning editor accepts interaction
    void updateAvailability(boolean writable, boolean interactive) {
        this.writable = writable;
        this.interactive = interactive;
        boolean busy = forceOverwriteExecutor != null;
        saveButton.setEnabled(writable && interactive && !busy);
        reloadButton.setEnabled(interactive && !busy);
        boolean recoverable = !writable && store.canForceOverwrite();
        forceOverwriteButton.setVisible(recoverable);
        forceOverwriteButton.setEnabled(recoverable && interactive && !busy);
    }

    /// Configures stable identities, icons, layout, and command listeners.
    private void configureComponents() {
        component.setOpaque(false);
        statusLabel.setName("instanceGameSettingsStatus");
        forceOverwriteButton.setName("instanceGameSettingsForceOverwrite");
        reloadButton.setName("instanceGameSettingsReload");
        reloadButton.setIcon(new FlatSVGIcon("assets/swing/icons/refresh.svg", 18, 18));
        saveButton.setName("instanceGameSettingsSave");
        forceOverwriteButton.addActionListener(event -> forceOverwrite());
        reloadButton.addActionListener(event -> reloadAction.run());
        saveButton.addActionListener(event -> saveAction.run());
        component.add(statusLabel, "growx");
        component.add(forceOverwriteButton);
        component.add(reloadButton);
        component.add(saveButton);
    }

    /// Performs confirmed backup-and-overwrite recovery and reloads the resulting writable snapshot.
    private void forceOverwrite() {
        EdtDispatcher.requireEventDispatchThread();
        if (forceOverwriteExecutor != null) {
            return;
        }
        if (!overwriteConfirmation.getAsBoolean()) {
            return;
        }
        try {
            Task<@Nullable Void> task = store.forceOverwriteTask(Schedulers.io());
            TaskExecutor executor = task.executor();
            forceOverwriteExecutor = executor;
            forceOverwriteSubscription = executor.subscribeTaskListener(new TaskListener() {
                /// Delivers recovery completion to the Swing event-dispatch thread.
                @Override
                public void onStop(boolean successful, TaskExecutor completedExecutor) {
                    SwingUiDispatcher.INSTANCE.dispatchOrRun(
                            () -> completeForceOverwrite(completedExecutor, successful));
                }
            });
            updateAvailability(writable, interactive);
            executor.start();
        } catch (RuntimeException exception) {
            if (forceOverwriteSubscription != null) {
                forceOverwriteSubscription.unsubscribe();
                forceOverwriteSubscription = null;
            }
            if (forceOverwriteExecutor != null) {
                forceOverwriteExecutor.cancel();
                forceOverwriteExecutor = null;
            }
            updateAvailability(writable, interactive);
            setStatus(i18n(
                    "swing.instance_settings.reload_failed",
                    Objects.requireNonNullElse(
                            exception.getMessage(),
                            i18n("swing.instance_settings.unavailable"))));
        }
    }

    /// Completes one asynchronous recovery task on the event-dispatch thread.
    ///
    /// @param executor executor that reached its terminal state
    /// @param successful whether the complete recovery task succeeded
    private void completeForceOverwrite(TaskExecutor executor, boolean successful) {
        EdtDispatcher.requireEventDispatchThread();
        if (forceOverwriteExecutor != executor) {
            return;
        }
        if (forceOverwriteSubscription != null) {
            forceOverwriteSubscription.unsubscribe();
            forceOverwriteSubscription = null;
        }
        forceOverwriteExecutor = null;
        if (successful) {
            reloadAction.run();
            setStatus(i18n("message.success"));
        } else if (executor.isCancelled()) {
            setStatus(i18n("message.cancelled"));
        } else {
            Throwable failure = executor.getFailure();
            setStatus(i18n(
                    "swing.instance_settings.reload_failed",
                    Objects.requireNonNullElse(
                            failure == null ? null : failure.getMessage(),
                            i18n("swing.instance_settings.unavailable"))));
        }
        updateAvailability(writable, interactive);
    }

    /// Cancels recovery and removes its listener during panel disposal.
    void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (forceOverwriteSubscription != null) {
            forceOverwriteSubscription.unsubscribe();
            forceOverwriteSubscription = null;
        }
        if (forceOverwriteExecutor != null) {
            forceOverwriteExecutor.cancel();
            forceOverwriteExecutor = null;
        }
    }

    /// Shows the localized destructive-action warning used by other Swing storage recovery flows.
    ///
    /// @return whether the user explicitly chose backup and overwrite
    private boolean showOverwriteConfirmation() {
        Object[] options = {i18n("settings.file.force_write"), i18n("button.cancel")};
        return JOptionPane.showOptionDialog(
                forceOverwriteButton,
                i18n("settings.game.instance_settings.unsupported")
                        + "\n\n"
                        + i18n("settings.file.force_write.confirm"),
                i18n("settings.file.force_write"),
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[1]) == 0;
    }
}
