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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.PortablePath;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JList;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Tests Swing game-directory selection and protected local management actions through the toolkit-neutral service.
@NotNullByDefault
public final class GameDirectoryManagementPanelTest {
    /// Selects a real service entry and adds a prepared relative directory without running path preparation on the EDT.
    @Test
    public void selectsCurrentDirectoryAndAddsRelativeEntry() throws InterruptedException {
        GameDirectoryManagementEntry current = entry("Current", ".minecraft", true);
        GameDirectoryManagementEntry home = entry("Home", "C:/Users/test/.minecraft", false);
        FakeGameDirectoryManagementService service = new FakeGameDirectoryManagementService(current, home);
        WorkerExecutor executor = new WorkerExecutor();
        GameDirectoryManagementPanel panel = onEventDispatchThread(() -> new GameDirectoryManagementPanel(
                service,
                new FakeInteraction(),
                executor));

        onEventDispatchThread(() -> {
            JList<?> list = findDirectoryList(panel);
            list.setSelectedValue(home, true);
            AbstractButton addButton = findComponent(panel, "gameDirectoryManagementAdd", AbstractButton.class);
            assertEquals(i18n("game_directory.new"), addButton.getText());
            addButton.doClick();
            findComponent(panel, "gameDirectoryManagementName", JTextField.class).setText("Development");
            findComponent(panel, "gameDirectoryManagementPath", JTextField.class).setText("instances/development");
            findComponent(panel, "gameDirectoryManagementRelativePath", JCheckBox.class).setSelected(true);
            findComponent(panel, "gameDirectoryManagementSave", AbstractButton.class).doClick();
        });
        executor.awaitLatest();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(home.id(), service.selectedId.get()),
                    () -> assertEquals(1, service.addCalls.get()),
                    () -> assertEquals("Development", service.lastEdit.displayName()),
                    () -> assertFalse(service.lastEdit.path().isAbsolute()),
                    () -> assertEquals("instances/development", service.lastEdit.path().getPath()),
                    () -> assertEquals(3, panel.displayedSnapshot().entries().size()));
            panel.close();
        });
    }

    /// Updates an existing entry and retries removal only after explicit read-only overwrite consent.
    @Test
    public void editsAndRemovesWithReadOnlyRecovery() throws InterruptedException {
        GameDirectoryManagementEntry current = entry("Current", ".minecraft", true);
        GameDirectoryManagementEntry target = entry("Modpack", "packs/modpack", false);
        FakeGameDirectoryManagementService service = new FakeGameDirectoryManagementService(current, target);
        service.removeRequiresOverwrite = true;
        WorkerExecutor executor = new WorkerExecutor();
        FakeInteraction interaction = new FakeInteraction();
        GameDirectoryManagementPanel panel = onEventDispatchThread(() -> new GameDirectoryManagementPanel(
                service,
                interaction,
                executor));

        onEventDispatchThread(() -> {
            JList<?> list = findDirectoryList(panel);
            list.setSelectedValue(target, true);
            findComponent(panel, "gameDirectoryManagementEdit", AbstractButton.class).doClick();
            findComponent(panel, "gameDirectoryManagementName", JTextField.class).setText("Edited Modpack");
            findComponent(panel, "gameDirectoryManagementPath", JTextField.class).setText("edited/modpack");
            findComponent(panel, "gameDirectoryManagementSave", AbstractButton.class).doClick();
        });
        executor.awaitLatest();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(1, service.updateCalls.get()),
                    () -> assertEquals("Edited Modpack", service.lastEdit.displayName()),
                    () -> assertTrue(service.lastEdit.path().getPath().contains("edited")));
            findComponent(panel, "gameDirectoryManagementRemove", AbstractButton.class).doClick();
            assertAll(
                    () -> assertEquals(List.of(false, true), service.removeOverwriteFlags),
                    () -> assertEquals(1, interaction.overwriteConfirmations.get()),
                    () -> assertEquals(1, panel.displayedSnapshot().entries().size()));
            panel.close();
        });
    }

    /// Creates one deterministic rendered game-directory entry.
    ///
    /// @param name visible directory name
    /// @param path persisted path
    /// @param selected whether the directory is selected
    /// @return constructed entry
    private static GameDirectoryManagementEntry entry(String name, String path, boolean selected) {
        return new GameDirectoryManagementEntry(
                GameDirectoryID.generate(),
                name,
                PortablePath.of(path),
                selected);
    }

    /// Finds one named component in a nested Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @param type expected component type
    /// @param <T> component type
    /// @return matching component
    private static <T extends Component> T findComponent(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName()) && type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                @Nullable T nested = findOptionalComponent(container, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        throw new IllegalArgumentException("Missing component: " + name);
    }

    /// Finds the specifically named game-directory list without an unchecked generic class cast.
    ///
    /// @param root hierarchy root
    /// @return management directory list
    private static JList<?> findDirectoryList(Container root) {
        for (Component child : root.getComponents()) {
            if ("gameDirectoryManagementList".equals(child.getName()) && child instanceof JList<?> list) {
                return list;
            }
            if (child instanceof Container container) {
                @Nullable JList<?> nested = findOptionalDirectoryList(container);
                if (nested != null) {
                    return nested;
                }
            }
        }
        throw new IllegalArgumentException("Missing component: gameDirectoryManagementList");
    }

    /// Searches a nested hierarchy for the game-directory list.
    ///
    /// @param root hierarchy root
    /// @return matching list, or `null`
    private static @Nullable JList<?> findOptionalDirectoryList(Container root) {
        for (Component child : root.getComponents()) {
            if ("gameDirectoryManagementList".equals(child.getName()) && child instanceof JList<?> list) {
                return list;
            }
            if (child instanceof Container container) {
                @Nullable JList<?> nested = findOptionalDirectoryList(container);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Searches a nested hierarchy without throwing for an absent component.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @param type expected component type
    /// @param <T> component type
    /// @return matching component, or `null`
    private static <T extends Component> @Nullable T findOptionalComponent(
            Container root,
            String name,
            Class<T> type) {
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName()) && type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                @Nullable T nested = findOptionalComponent(container, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Runs a value-producing operation synchronously on the event dispatch thread.
    ///
    /// @param operation non-null operation
    /// @param <T> result type
    /// @return produced result
    private static <T extends Object> T onEventDispatchThread(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(Objects.requireNonNull(operation, "operation").get()));
        return Objects.requireNonNull(result.get(), "EDT operation did not return a result");
    }

    /// Runs one operation synchronously on the event dispatch thread.
    ///
    /// @param operation operation to execute
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(Objects.requireNonNull(operation, "operation"));
    }

    /// Executes each path preparation on a dedicated worker and permits deterministic test waiting.
    @NotNullByDefault
    private static final class WorkerExecutor implements Executor {
        /// Latest worker spawned for a background preparation action, or `null` before the first command.
        private @Nullable Thread latestWorker;

        /// Starts one operation on a background worker.
        ///
        /// @param command operation that must not run on the EDT
        @Override
        public void execute(Runnable command) {
            Thread worker = new Thread(Objects.requireNonNull(command, "command"), "game-directory-management-test-worker");
            latestWorker = worker;
            worker.start();
        }

        /// Waits for the last started background operation.
        ///
        /// @throws InterruptedException when the test thread is interrupted
        private void awaitLatest() throws InterruptedException {
            @Nullable Thread worker = latestWorker;
            if (worker == null) {
                throw new IllegalStateException("No background operation was submitted");
            }
            worker.join();
        }
    }

    /// In-memory game-directory service used to verify page commands without global launcher state.
    @NotNullByDefault
    private static final class FakeGameDirectoryManagementService implements GameDirectoryManagementService {
        /// Thread-safe transition publisher used by the panel subscription.
        private final ValueChangeSupport<GameDirectoryManagementSnapshot> changes = new ValueChangeSupport<>(this);

        /// Mutable effective entries represented by snapshots.
        private final List<GameDirectoryManagementEntry> entries = new ArrayList<>();

        /// Latest rendered snapshot.
        private GameDirectoryManagementSnapshot currentSnapshot;

        /// Current selected directory identifier.
        private final AtomicReference<GameDirectoryID> selectedId;

        /// Number of added directories.
        private final AtomicInteger addCalls = new AtomicInteger();

        /// Number of updated directories.
        private final AtomicInteger updateCalls = new AtomicInteger();

        /// Read-only-recovery flags passed to removal attempts.
        private final List<Boolean> removeOverwriteFlags = new ArrayList<>();

        /// Last add or update payload.
        private GameDirectoryManagementEdit lastEdit;

        /// Whether the first removal should request read-only overwrite confirmation.
        private boolean removeRequiresOverwrite;

        /// Monotonic fake snapshot revision.
        private long revision;

        /// Creates an initialized service with deterministic entries.
        ///
        /// @param initialEntries initial effective directory entries
        private FakeGameDirectoryManagementService(GameDirectoryManagementEntry... initialEntries) {
            entries.addAll(List.of(initialEntries));
            @Nullable GameDirectoryManagementEntry selected = entries.stream()
                    .filter(GameDirectoryManagementEntry::selected)
                    .findFirst()
                    .orElse(null);
            selectedId = new AtomicReference<>(Objects.requireNonNull(selected, "initial entries need a selected directory").id());
            currentSnapshot = snapshotFromEntries();
            lastEdit = new GameDirectoryManagementEdit("Initial", PortablePath.of(".minecraft"));
        }

        /// Returns current fake immutable directory state.
        ///
        /// @return current snapshot
        @Override
        public GameDirectoryManagementSnapshot snapshot() {
            return currentSnapshot;
        }

        /// Registers one snapshot listener.
        ///
        /// @param listener state listener
        /// @return removable registration
        @Override
        public Subscription subscribe(ValueChangeListener<GameDirectoryManagementSnapshot> listener) {
            return changes.subscribe(Objects.requireNonNull(listener, "listener"));
        }

        /// Selects a directory and publishes selection state.
        ///
        /// @param id selected stable ID
        @Override
        public void select(GameDirectoryID id) {
            selectedId.set(Objects.requireNonNull(id, "id"));
            publishSnapshot();
        }

        /// Adds one fake effective entry.
        ///
        /// @param edit prepared entry values
        /// @param allowReadOnlyOverwrite ignored fake recovery flag
        @Override
        public void add(GameDirectoryManagementEdit edit, boolean allowReadOnlyOverwrite) {
            lastEdit = Objects.requireNonNull(edit, "edit");
            addCalls.incrementAndGet();
            entries.add(new GameDirectoryManagementEntry(
                    GameDirectoryID.generate(),
                    edit.displayName(),
                    edit.path(),
                    false));
            publishSnapshot();
        }

        /// Updates one fake effective entry.
        ///
        /// @param id stable entry identifier
        /// @param edit prepared entry values
        /// @param allowReadOnlyOverwrite ignored fake recovery flag
        @Override
        public void update(GameDirectoryID id, GameDirectoryManagementEdit edit, boolean allowReadOnlyOverwrite) {
            GameDirectoryID targetId = Objects.requireNonNull(id, "id");
            lastEdit = Objects.requireNonNull(edit, "edit");
            updateCalls.incrementAndGet();
            for (int index = 0; index < entries.size(); index++) {
                GameDirectoryManagementEntry entry = entries.get(index);
                if (entry.id().equals(targetId)) {
                    entries.set(index, new GameDirectoryManagementEntry(
                            entry.id(),
                            edit.displayName(),
                            edit.path(),
                            entry.selected()));
                    publishSnapshot();
                    return;
                }
            }
            throw new IllegalArgumentException("Unknown fake directory: " + id);
        }

        /// Requests overwrite recovery once and then removes the matched fake entry.
        ///
        /// @param id stable entry identifier
        /// @param allowReadOnlyOverwrite current recovery consent
        @Override
        public void remove(GameDirectoryID id, boolean allowReadOnlyOverwrite) {
            removeOverwriteFlags.add(allowReadOnlyOverwrite);
            if (removeRequiresOverwrite && !allowReadOnlyOverwrite) {
                throw new GameDirectoryStorageOverwriteRequiredException();
            }
            entries.removeIf(entry -> entry.id().equals(Objects.requireNonNull(id, "id")));
            publishSnapshot();
        }

        /// No-op because the fake service owns no external subscriptions.
        @Override
        public void close() {
        }

        /// Publishes a newly materialized immutable snapshot.
        private void publishSnapshot() {
            GameDirectoryManagementSnapshot previous = currentSnapshot;
            currentSnapshot = snapshotFromEntries();
            changes.fireChange(previous, currentSnapshot);
        }

        /// Materializes rendered entries with exactly one current selection.
        ///
        /// @return immutable fake snapshot
        private GameDirectoryManagementSnapshot snapshotFromEntries() {
            List<GameDirectoryManagementEntry> rendered = entries.stream()
                    .map(entry -> new GameDirectoryManagementEntry(
                            entry.id(),
                            entry.displayName(),
                            entry.path(),
                            entry.id().equals(selectedId.get())))
                    .toList();
            return new GameDirectoryManagementSnapshot(++revision, rendered);
        }
    }

    /// Headless interaction implementation that accepts confirmations and captures no external UI state.
    @NotNullByDefault
    private static final class FakeInteraction implements GameDirectoryManagementInteraction {
        /// Number of accepted read-only recovery confirmations.
        private final AtomicInteger overwriteConfirmations = new AtomicInteger();

        /// Cancels native chooser requests in this focused presentation test.
        ///
        /// @param owner chooser parent
        /// @param initialDirectory ignored suggested path, or `null`
        /// @return always `null`
        @Override
        public @Nullable Path chooseDirectory(Component owner, @Nullable Path initialDirectory) {
            return null;
        }

        /// Accepts read-only recovery and records the confirmation.
        ///
        /// @param owner confirmation parent
        /// @return always `true`
        @Override
        public boolean confirmReadOnlyOverwrite(Component owner) {
            overwriteConfirmations.incrementAndGet();
            return true;
        }

        /// Accepts fake entry removal.
        ///
        /// @param owner confirmation parent
        /// @param entry target entry
        /// @return always `true`
        @Override
        public boolean confirmRemoval(Component owner, GameDirectoryManagementEntry entry) {
            return true;
        }

        /// Fails the test when the panel unexpectedly reports a terminal error.
        ///
        /// @param owner dialog parent
        /// @param detail unexpected failure detail
        @Override
        public void showFailure(Component owner, String detail) {
            throw new AssertionError("Unexpected management failure: " + detail);
        }
    }
}
