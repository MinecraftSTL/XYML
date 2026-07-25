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
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.platform.Platform;

import javax.swing.AbstractButton;
import javax.swing.JList;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests local Java runtime rendering, local refresh dispatch, worker snapshot delivery, and panel cleanup.
@NotNullByDefault
public final class JavaManagementPanelTest {
    /// Renders selected runtime metadata and sends the refresh command to the local service.
    @Test
    public void rendersActualRuntimeMetadataAndRefreshesLocalPaths() {
        JavaRuntime java17 = runtime("C:/java/17/bin/java.exe", "17.0.12", "Temurin");
        JavaRuntime java21 = runtime("C:/java/21/bin/java.exe", "21.0.4", "Oracle");
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(snapshot(1L, java17, java21));
        JavaManagementPanel panel = onEventDispatchThread(() -> new JavaManagementPanel(service));

        onEventDispatchThread(() -> {
            JList<?> runtimes = findComponent(panel, "javaManagementRuntimeList", JList.class);
            runtimes.setSelectedValue(java21, true);
            findComponent(panel, "javaManagementRefresh", AbstractButton.class).doClick();

            assertAll(
                    () -> assertEquals(1, service.refreshCalls.get()),
                    () -> assertEquals(java21, panel.selectedRuntime()),
                    () -> assertEquals("21.0.4", findComponent(
                            panel, "javaManagementVersion", JTextField.class).getText()),
                    () -> assertEquals("Oracle", findComponent(
                            panel, "javaManagementVendor", JTextField.class).getText()),
                    () -> assertEquals(java21.getBinary().toString(), findComponent(
                            panel, "javaManagementPath", JTextField.class).getText()));
            panel.close();
        });
    }

    /// Coalesces a worker-published local runtime snapshot onto the EDT and unsubscribes after closure.
    @Test
    public void appliesWorkerSnapshotAndReleasesSubscriptionOnClose() throws InterruptedException {
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                new JavaRuntimeManagementSnapshot(false, 0L, List.of()));
        JavaManagementPanel panel = onEventDispatchThread(() -> new JavaManagementPanel(service));
        JavaRuntime java17 = runtime("C:/java/17/bin/java.exe", "17.0.12", "Temurin");
        JavaRuntimeManagementSnapshot discovered = snapshot(1L, java17);

        publishFromWorker(service, discovered);
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(discovered, panel.displayedSnapshot()),
                    () -> assertEquals(1, findComponent(
                            panel, "javaManagementRuntimeList", JList.class).getModel().getSize()),
                    () -> assertNull(panel.selectedRuntime()));
            panel.close();
        });

        publishFromWorker(service, snapshot(2L));
        EdtDispatcher.executeAndWait(() -> { });
        assertEquals(discovered, onEventDispatchThread(panel::displayedSnapshot));
    }

    /// Publishes one service snapshot on a worker thread and waits for its completion.
    ///
    /// @param service fake service receiving the publication
    /// @param replacement replacement runtime snapshot
    /// @throws InterruptedException if the worker join is interrupted
    private static void publishFromWorker(
            FakeJavaRuntimeManagementService service,
            JavaRuntimeManagementSnapshot replacement) throws InterruptedException {
        Thread worker = new Thread(() -> service.publish(replacement), "java-management-test-publisher");
        worker.start();
        worker.join();
    }

    /// Creates one initialized runtime snapshot with a deterministic revision.
    ///
    /// @param revision snapshot revision
    /// @param runtimes local runtime values
    /// @return initialized immutable local runtime snapshot
    private static JavaRuntimeManagementSnapshot snapshot(long revision, JavaRuntime... runtimes) {
        return new JavaRuntimeManagementSnapshot(true, revision, List.of(runtimes));
    }

    /// Creates one deterministic local Java runtime fixture without touching the real filesystem.
    ///
    /// @param binary Java executable path
    /// @param version Java version text
    /// @param vendor Java vendor text
    /// @return local Java runtime fixture
    private static JavaRuntime runtime(String binary, String version, String vendor) {
        return new JavaRuntime(
                Path.of(binary),
                new JavaInfo(Platform.WINDOWS_X86_64, version, vendor),
                false,
                true);
    }

    /// Finds one named component in a Swing hierarchy.
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

    /// Searches a nested Swing hierarchy without throwing when no component matches.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @param type expected component type
    /// @param <T> component type
    /// @return matching component, or null when absent
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
    /// @param operation operation to execute
    /// @param <T> non-null result type
    /// @return operation result
    private static <T extends Object> T onEventDispatchThread(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(operation.get()));
        return Objects.requireNonNull(result.get(), "EDT operation did not return a result");
    }

    /// Runs an operation synchronously on the event dispatch thread.
    ///
    /// @param operation operation to execute
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(operation);
    }

    /// Thread-safe fake local Java service for presentation tests.
    @NotNullByDefault
    private static final class FakeJavaRuntimeManagementService implements JavaRuntimeManagementService {
        /// Current immutable local runtime snapshot.
        private final AtomicReference<JavaRuntimeManagementSnapshot> current;

        /// Runtime snapshot transition publisher.
        private final ValueChangeSupport<JavaRuntimeManagementSnapshot> changes = new ValueChangeSupport<>(this);

        /// Number of local refresh command invocations.
        private final AtomicInteger refreshCalls = new AtomicInteger();

        /// Creates a fake local Java service with one initial snapshot.
        ///
        /// @param initialSnapshot initial local Java runtime state
        private FakeJavaRuntimeManagementService(JavaRuntimeManagementSnapshot initialSnapshot) {
            current = new AtomicReference<>(Objects.requireNonNull(initialSnapshot, "initialSnapshot"));
        }

        /// Returns the current fake local runtime state.
        ///
        /// @return current immutable runtime snapshot
        @Override
        public JavaRuntimeManagementSnapshot snapshot() {
            return current.get();
        }

        /// Registers one fake runtime snapshot listener.
        ///
        /// @param listener listener receiving runtime transitions
        /// @return independently removable listener subscription
        @Override
        public Subscription subscribe(ValueChangeListener<JavaRuntimeManagementSnapshot> listener) {
            return changes.subscribe(listener);
        }

        /// Records one local runtime refresh request.
        @Override
        public void refreshLocalRuntimes() {
            refreshCalls.incrementAndGet();
        }

        /// Returns a failed completion because chooser registration is outside this focused presentation test.
        ///
        /// @param selectedPath selected Java executable or home path
        /// @return failed local Java registration completion
        @Override
        public CompletionStage<JavaRuntime> addLocalRuntime(Path selectedPath) {
            Objects.requireNonNull(selectedPath, "selectedPath");
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Not used by this test"));
        }

        /// Publishes one replacement local runtime snapshot on the caller thread.
        ///
        /// @param replacement replacement immutable local runtime state
        private void publish(JavaRuntimeManagementSnapshot replacement) {
            JavaRuntimeManagementSnapshot previous = current.getAndSet(Objects.requireNonNull(replacement, "replacement"));
            changes.fireChange(previous, replacement);
        }
    }
}
