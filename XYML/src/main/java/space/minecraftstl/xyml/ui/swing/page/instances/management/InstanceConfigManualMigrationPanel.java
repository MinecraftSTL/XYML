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
import space.minecraftstl.xyml.game.migration.InstanceConfigMigrationRequest;
import space.minecraftstl.xyml.game.migration.InstanceConfigMigrationResult;
import space.minecraftstl.xyml.game.migration.InstanceConfigMigrationService;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.setting.InstanceConfigMigrationContent;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.page.settings.InstanceConfigMigrationChoice;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Runs explicit configuration migration from another instance into one fixed isolated target.
@NotNullByDefault
public final class InstanceConfigManualMigrationPanel extends JPanel implements AutoCloseable {
    /// Target repository.
    private final XYMLGameRepository targetRepository;

    /// Fixed target instance.
    private final GameInstanceID targetInstance;

    /// Background executor for filesystem work.
    private final Executor executor;

    /// Cross-directory source selector.
    private final JComboBox<InstanceConfigMigrationChoice> sourceBox = new JComboBox<>();

    /// Per-content controls, selected by default.
    private final Map<InstanceConfigMigrationContent, JCheckBox> contentBoxes =
            new EnumMap<>(InstanceConfigMigrationContent.class);

    /// Whether conflicts are replaced; selected by default.
    private final JCheckBox replaceBox = new JCheckBox(i18n("settings.instance_config_migration.manual.replace"), true);

    /// Starts one asynchronous migration.
    private final JButton migrateButton = new JButton(i18n("settings.instance_config_migration.manual.start"));

    /// Displays category results or complete errors.
    private final JLabel statusLabel = new JLabel();

    /// Active task executor, or null while idle.
    private @Nullable TaskExecutor activeExecutor;

    /// Active task listener registration, or null while idle.
    private @Nullable Subscription activeSubscription;

    /// Creates the manual migration surface for one real instance.
    public InstanceConfigManualMigrationPanel(
            XYMLGameRepository targetRepository,
            GameInstanceID targetInstance,
            Executor executor) {
        super(new MigLayout("insets 20, fillx, wrap 2", "[][grow,fill]", "[]10[]10[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.targetRepository = Objects.requireNonNull(targetRepository, "targetRepository");
        this.targetInstance = Objects.requireNonNull(targetInstance, "targetInstance");
        this.executor = Objects.requireNonNull(executor, "executor");
        setOpaque(false);
        configureComponents();
    }

    /// Builds defaults and source choices without blocking on repository refresh.
    private void configureComponents() {
        sourceBox.setName("instanceConfigManualMigrationSource");
        for (InstanceConfigMigrationChoice choice :
                InstanceConfigMigrationChoice.availableChoices(false, targetRepository, targetInstance)) {
            sourceBox.addItem(choice);
        }
        add(new JLabel(i18n("settings.instance_config_migration.source_instance")));
        add(sourceBox, "growx");

        JPanel contents = new JPanel(new MigLayout("insets 0, fillx, wrap 2", "[grow,fill][grow,fill]", "[]"));
        contents.setOpaque(false);
        for (InstanceConfigMigrationContent content : InstanceConfigMigrationContent.values()) {
            JCheckBox box = new JCheckBox(contentName(content), true);
            box.setName("instanceConfigManualMigrationContent" + content.name());
            contentBoxes.put(content, box);
            contents.add(box);
        }
        add(new JLabel(i18n("settings.instance_config_migration.contents")), "aligny top");
        add(contents, "growx");

        replaceBox.setName("instanceConfigManualMigrationReplace");
        add(replaceBox, "span 2");
        migrateButton.setName("instanceConfigManualMigrationStart");
        migrateButton.addActionListener(event -> startMigration());
        add(migrateButton, "split 2");
        add(statusLabel, "growx");
        if (!targetRepository.isInstanceIsolated(targetInstance)) {
            statusLabel.setText(i18n("settings.instance_config_migration.manual.target_not_isolated"));
        }
    }

    /// Validates the fixed target, warns before replacement, and starts a resource-aware migration task.
    private void startMigration() {
        if (activeExecutor != null) {
            return;
        }
        if (!targetRepository.isInstanceIsolated(targetInstance)) {
            statusLabel.setText(i18n("settings.instance_config_migration.manual.target_not_isolated"));
            return;
        }
        @Nullable InstanceConfigMigrationChoice choice =
                (InstanceConfigMigrationChoice) sourceBox.getSelectedItem();
        if (choice == null || choice.repository() == null || !choice.available()) {
            statusLabel.setText(i18n("settings.instance_config_migration.source_required"));
            return;
        }
        EnumSet<InstanceConfigMigrationContent> contents = EnumSet.noneOf(InstanceConfigMigrationContent.class);
        for (Map.Entry<InstanceConfigMigrationContent, JCheckBox> entry : contentBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                contents.add(entry.getKey());
            }
        }
        if (contents.isEmpty()) {
            statusLabel.setText(i18n("settings.instance_config_migration.contents_required"));
            return;
        }
        if (replaceBox.isSelected() && JOptionPane.showConfirmDialog(
                this,
                i18n("settings.instance_config_migration.manual.replace_warning"),
                i18n("settings.instance_config_migration.manual.title"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            statusLabel.setText(i18n("settings.instance_config_migration.manual.cancelled"));
            return;
        }

        XYMLGameRepository sourceRepository = Objects.requireNonNull(choice.repository(), "source repository");
        Path sourceDirectory = sourceRepository.isInstanceIsolated(choice.instanceId())
                ? sourceRepository.getRunDirectory(choice.instanceId())
                : sourceRepository.getSharedRunDirectory(choice.instanceId());
        Path targetDirectory = targetRepository.getRunDirectory(targetInstance);
        InstanceConfigMigrationRequest request;
        try {
            request = new InstanceConfigMigrationRequest(
                    sourceDirectory, targetDirectory, contents, replaceBox.isSelected());
        } catch (IllegalArgumentException failure) {
            statusLabel.setText(failure.getMessage());
            return;
        }
        Task<InstanceConfigMigrationResult> task = Task.supplyAsync(
                        "Migrate instance configuration",
                        executor,
                        () -> InstanceConfigMigrationService.migrate(request))
                .setResources(
                        TaskResource.gameDirectory(request.sourceDirectory()),
                        TaskResource.gameInstance(request.targetDirectory()));
        startTask(task);
    }

    /// Starts one migration and marshals its terminal result to the EDT.
    private void startTask(Task<InstanceConfigMigrationResult> task) {
        TaskExecutor taskExecutor = task.executor();
        activeExecutor = taskExecutor;
        migrateButton.setEnabled(false);
        statusLabel.setText(i18n("settings.instance_config_migration.manual.running"));
        activeSubscription = taskExecutor.subscribeTaskListener(new TaskListener() {
            /// Completes the UI state on the EDT.
            @Override
            public void onStop(boolean successful, TaskExecutor completedExecutor) {
                EdtDispatcher.execute(() -> finishTask(task, completedExecutor, successful));
            }
        });
        taskExecutor.start();
    }

    /// Displays category-level counts or the complete terminal error.
    private void finishTask(
            Task<InstanceConfigMigrationResult> task,
            TaskExecutor completedExecutor,
            boolean successful) {
        if (activeExecutor != completedExecutor) {
            return;
        }
        clearTask();
        if (!successful) {
            @Nullable Throwable failure = completedExecutor.getFailure();
            statusLabel.setText(i18n(
                    "settings.instance_config_migration.manual.failed",
                    failure == null
                            ? i18n("settings.instance_config_migration.manual.unknown_failure")
                            : Objects.toString(failure.getMessage(), failure.toString())));
            return;
        }
        StringBuilder summary = new StringBuilder("<html>");
        for (Map.Entry<InstanceConfigMigrationContent, InstanceConfigMigrationResult.Counts> entry
                : task.getResult().counts().entrySet()) {
            InstanceConfigMigrationResult.Counts counts = entry.getValue();
            if (summary.length() > 6) {
                summary.append("<br>");
            }
            summary.append(contentName(entry.getKey())).append(": ")
                    .append(i18n(
                            "settings.instance_config_migration.manual.result",
                            counts.copied(), counts.replaced(), counts.skipped()));
        }
        summary.append("</html>");
        statusLabel.setText(summary.toString());
    }

    /// Cancels active work when Swing removes this page from its display hierarchy.
    @Override
    public void removeNotify() {
        close();
        super.removeNotify();
    }

    /// Cancels active work and releases its listener.
    @Override
    public void close() {
        @Nullable TaskExecutor taskExecutor = activeExecutor;
        clearTask();
        if (taskExecutor != null) {
            taskExecutor.cancel();
        }
    }

    /// Releases active task presentation state.
    private void clearTask() {
        if (activeSubscription != null) {
            activeSubscription.unsubscribe();
            activeSubscription = null;
        }
        activeExecutor = null;
        migrateButton.setEnabled(true);
    }

    /// Returns the localized label for one fixed migration content category.
    private static String contentName(InstanceConfigMigrationContent content) {
        return i18n("settings.instance_config_migration.content." + content.name().toLowerCase(java.util.Locale.ROOT));
    }
}
