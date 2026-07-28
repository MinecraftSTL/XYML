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
package space.minecraftstl.xyml.ui.swing.page.settings;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/// Presents one shared restart action for launcher language and April Fools settings.
@NotNullByDefault
final class SettingsRestartPanel extends JPanel implements AutoCloseable {
    /// Localized text for every restart state.
    private final SettingsRestartStrings strings;

    /// Injectable restart lifecycle command.
    private final SettingsRestartCommand restartCommand;

    /// Notifies the owning settings center while restart preparation blocks further edits.
    private final Consumer<Boolean> restartActivity;

    /// Visible restart state explanation.
    private final JLabel statusLabel = new JLabel();

    /// Explicit user action enabled only when persisted values differ from the active process baseline.
    private final JButton restartButton = new JButton();

    /// Launcher language active when this settings surface first observed process state.
    private @Nullable SupportedLocale baselineLanguage;

    /// April Fools suppression active when this settings surface first observed process state.
    private @Nullable Boolean baselineAprilFoolsDisabled;

    /// Whether settings persistence currently permits restart-sensitive edits.
    private boolean available = true;

    /// Whether current controls differ from the process baseline.
    private boolean restartRequired;

    /// Whether a restart command is currently running.
    private boolean restarting;

    /// Whether this control has been detached from its owning settings center.
    private boolean closed;

    /// Creates the shared restart status and action row.
    ///
    /// @param strings localized restart text
    /// @param restartCommand restart lifecycle command
    /// @param restartActivity listener receiving restart-in-progress transitions
    SettingsRestartPanel(
            SettingsRestartStrings strings,
            SettingsRestartCommand restartCommand,
            Consumer<Boolean> restartActivity) {
        super(new MigLayout("insets 4 0 0 0, fillx", "[grow,fill]8[]", "[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.strings = Objects.requireNonNull(strings, "strings");
        this.restartCommand = Objects.requireNonNull(restartCommand, "restartCommand");
        this.restartActivity = Objects.requireNonNull(restartActivity, "restartActivity");

        setOpaque(false);
        setName("settingsRestartPanel");
        statusLabel.setName("settingsRestartStatus");
        restartButton.setName("settingsRestartAction");
        restartButton.setText(strings.actionText());
        restartButton.addActionListener(event -> requestRestart());
        add(statusLabel, "growx");
        add(restartButton);
        updatePresentation();
    }

    /// Establishes the process baseline on first use and tracks later restart-sensitive settings snapshots.
    ///
    /// @param language persisted launcher language
    /// @param aprilFoolsDisabled whether persisted April Fools behavior is disabled
    void updateSettings(SupportedLocale language, boolean aprilFoolsDisabled) {
        EdtDispatcher.requireEventDispatchThread();
        SupportedLocale validatedLanguage = Objects.requireNonNull(language, "language");
        if (baselineLanguage == null || baselineAprilFoolsDisabled == null) {
            baselineLanguage = validatedLanguage;
            baselineAprilFoolsDisabled = aprilFoolsDisabled;
        }
        restartRequired = !Objects.equals(baselineLanguage, validatedLanguage)
                || !Objects.equals(baselineAprilFoolsDisabled, aprilFoolsDisabled);
        updatePresentation();
    }

    /// Enables or disables the restart action according to persistent-settings availability.
    ///
    /// @param available whether restart-sensitive settings may be changed
    void setAvailable(boolean available) {
        EdtDispatcher.requireEventDispatchThread();
        this.available = available;
        updatePresentation();
    }

    /// Returns whether current settings differ from the active process baseline.
    ///
    /// @return whether a restart is required
    boolean isRestartRequired() {
        EdtDispatcher.requireEventDispatchThread();
        return restartRequired;
    }

    /// Disables future interaction and ignores late restart completions.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        closed = true;
        updatePresentation();
    }

    /// Starts the injected restart command and exposes retryable progress on this row.
    private void requestRestart() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || restarting || !available || !restartRequired) {
            return;
        }
        restarting = true;
        restartActivity.accept(true);
        updatePresentation();

        final CompletionStage<@Nullable Void> completion;
        try {
            completion = Objects.requireNonNull(
                    restartCommand.restart(this),
                    "restart command returned null completion");
        } catch (RuntimeException failure) {
            completeRestart(failure);
            return;
        }
        completion.whenComplete((ignored, failure) -> SwingUiDispatcher.INSTANCE.dispatchOrRun(
                () -> completeRestart(failure)));
    }

    /// Restores retry interaction after failure; successful completion normally follows window disposal.
    ///
    /// @param failure restart failure, or null after successful disposal
    private void completeRestart(@Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        restarting = false;
        restartActivity.accept(false);
        if (failure != null) {
            statusLabel.setText(strings.failedText());
            restartButton.setEnabled(available && restartRequired);
        } else {
            updatePresentation();
        }
    }

    /// Synchronizes visible copy and button availability with current restart state.
    private void updatePresentation() {
        EdtDispatcher.requireEventDispatchThread();
        if (restarting) {
            statusLabel.setText(strings.inProgressText());
        } else {
            statusLabel.setText(restartRequired ? strings.requiredText() : strings.promptText());
        }
        restartButton.setEnabled(!closed && available && restartRequired && !restarting);
    }
}
