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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.download.java.JavaPackageType;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaDistribution;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaRemoteVersion;
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.ScrollDirection;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.choice.ViewportLoadPlan;
import space.minecraftstl.xyml.util.platform.Platform;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.TransferHandler;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.ui.swing.SwingFileTransferTestSupport.fileTransfer;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Tests Java runtime cards, lifecycle actions, native interaction paths, and close barriers.
@NotNullByDefault
public final class JavaManagementPanelTest {
    /// Temporary filesystem root used by directory reveal tests.
    @TempDir
    private @Nullable Path temporaryDirectory;

    /// Renders runtime metadata, icons, tooltips, and local refresh behavior.
    @Test
    public void rendersActualRuntimeMetadataAndRefreshesLocalPaths() {
        JavaRuntime java17 = runtime("C:/java/17/bin/java.exe", "17.0.12", "Temurin", false);
        JavaRuntime java21 = runtime("C:/java/21/bin/java.exe", "21.0.4", "Oracle", false);
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                snapshot(true, List.of(java17, java21), List.of()));
        JavaManagementPanel panel = onEventDispatchThread(() ->
                new JavaManagementPanel(service, new FakeJavaManagementInteractions()));

        onEventDispatchThread(() -> {
            JList<?> runtimes = findComponent(panel, "javaManagementRuntimeList", JList.class);
            runtimes.setSelectedValue(java21, true);
            AbstractButton refresh = findComponent(panel, "javaManagementRefresh", AbstractButton.class);
            refresh.doClick();

            assertAll(
                    () -> assertEquals(1, service.refreshCalls.get()),
                    () -> assertEquals(java21, panel.selectedRuntime()),
                    () -> assertEquals("21.0.4", findComponent(
                            panel, "javaManagementVersion", JTextField.class).getText()),
                    () -> assertEquals("Oracle", findComponent(
                            panel, "javaManagementVendor", JTextField.class).getText()),
                    () -> assertEquals(java21.getBinary().toString(), findComponent(
                            panel, "javaManagementPath", JTextField.class).getText()),
                    () -> assertEquals(
                            "Oracle JDK 21.0.4 - " + java21.getArchitecture().getDisplayName(),
                            JavaManagementPanel.runtimeDisplayText(java21)),
                    () -> assertInstanceOf(FlatSVGIcon.class, refresh.getIcon()),
                    () -> assertEquals("", refresh.getText()),
                    () -> assertEquals(i18n("button.refresh"), refresh.getToolTipText()));
            panel.close();
        });
    }

    /// Shows scanning and localized empty-runtime feedback across the initial discovery lifecycle.
    @Test
    public void showsScanningThenEmptyRuntimeFeedback() {
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                new JavaRuntimeManagementSnapshot(false, 0L, true, List.of(), List.of()));
        JavaManagementPanel panel = onEventDispatchThread(() ->
                new JavaManagementPanel(service, new FakeJavaManagementInteractions()));

        onEventDispatchThread(() -> assertEquals(
                i18n("message.doing"),
                findComponent(panel, "javaManagementStatus", javax.swing.JLabel.class).getText()));
        service.publish(new JavaRuntimeManagementSnapshot(true, 1L, true, List.of(), List.of()));
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertEquals(
                    i18n("settings.game.java_directory.auto.not_found"),
                    findComponent(panel, "javaManagementStatus", javax.swing.JLabel.class).getText());
            assertFalse(findComponent(panel, "javaManagementDisabled", AbstractButton.class).isEnabled());
            panel.close();
        });
    }

    /// Keeps read-only mutations disabled while still allowing managed runtime uninstall.
    @Test
    public void appliesReadOnlyRulesToActiveAndDisabledRuntimeActions() {
        JavaRuntime unmanaged = runtime("C:/java/local/bin/java.exe", "17", "Local", false);
        JavaRuntime managed = runtime("C:/java/managed/bin/java.exe", "21", "Managed", true);
        DisabledJavaRuntimeEntry valid = DisabledJavaRuntimeEntry.available(
                "C:/java/disabled/bin/java.exe",
                Path.of("C:/java/disabled/bin/java.exe"));
        DisabledJavaRuntimeEntry invalid = DisabledJavaRuntimeEntry.invalid("C:/missing/java.exe");
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                snapshot(false, List.of(unmanaged, managed), List.of(valid, invalid)));
        JavaManagementPanel panel = onEventDispatchThread(() ->
                new JavaManagementPanel(service, new FakeJavaManagementInteractions()));

        onEventDispatchThread(() -> {
            AbstractButton add = findComponent(panel, "javaManagementAdd", AbstractButton.class);
            AbstractButton runtimeAction = findComponent(
                    panel, "javaManagementRuntimeAction", AbstractButton.class);
            JList<?> runtimes = findComponent(panel, "javaManagementRuntimeList", JList.class);
            runtimes.setSelectedValue(unmanaged, true);
            assertFalse(runtimeAction.isEnabled());
            runtimes.setSelectedValue(managed, true);

            assertAll(
                    () -> assertFalse(add.isEnabled()),
                    () -> assertTrue(runtimeAction.isEnabled()),
                    () -> assertEquals("", runtimeAction.getText()),
                    () -> assertEquals(i18n("java.uninstall"), runtimeAction.getToolTipText()));

            findComponent(panel, "javaManagementDisabled", AbstractButton.class).doClick();
            JList<?> disabled = findComponent(panel, "javaManagementDisabledList", JList.class);
            AbstractButton restore = findComponent(
                    panel, "javaManagementDisabledRestore", AbstractButton.class);
            AbstractButton remove = findComponent(
                    panel, "javaManagementDisabledRemove", AbstractButton.class);
            disabled.setSelectedValue(valid, true);
            assertAll(() -> assertFalse(restore.isEnabled()), () -> assertFalse(remove.isEnabled()));
            disabled.setSelectedValue(invalid, true);
            assertAll(() -> assertFalse(restore.isEnabled()), () -> assertFalse(remove.isEnabled()));
            panel.close();
        });
    }

    /// Switches the selected active-runtime mutation between disable and uninstall without duplicate task creation.
    @Test
    public void dispatchesDynamicUnmanagedDisableAndManagedUninstallActions() throws InterruptedException {
        JavaRuntime unmanaged = runtime("C:/java/local/bin/java.exe", "17", "Local", false);
        JavaRuntime managed = runtime("C:/java/managed/bin/java.exe", "21", "Managed", true);
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                snapshot(true, List.of(unmanaged, managed), List.of()));
        FakeJavaManagementInteractions interactions = new FakeJavaManagementInteractions();
        JavaManagementPanel panel = onEventDispatchThread(() -> new JavaManagementPanel(service, interactions));
        AbstractButton runtimeAction = onEventDispatchThread(() ->
                findComponent(panel, "javaManagementRuntimeAction", AbstractButton.class));

        onEventDispatchThread(() -> {
            JList<?> runtimes = findComponent(panel, "javaManagementRuntimeList", JList.class);
            runtimes.setSelectedValue(unmanaged, true);
            assertEquals(i18n("java.disable"), runtimeAction.getToolTipText());
            runtimeAction.doClick();
        });
        awaitCondition(() -> service.disableCalls.get() == 1 && onEventDispatchThread(runtimeAction::isEnabled));

        onEventDispatchThread(() -> {
            JList<?> runtimes = findComponent(panel, "javaManagementRuntimeList", JList.class);
            runtimes.setSelectedValue(managed, true);
            assertEquals(i18n("java.uninstall"), runtimeAction.getToolTipText());
            runtimeAction.doClick();
        });
        awaitCondition(() -> service.uninstallCalls.get() == 1 && onEventDispatchThread(runtimeAction::isEnabled));

        assertAll(
                () -> assertEquals(2, interactions.confirmCalls.get()),
                () -> assertEquals(unmanaged, service.lastDisabledRuntime.get()),
                () -> assertEquals(managed, service.lastUninstalledRuntime.get()));
        onEventDispatchThread(panel::close);
    }

    /// Switches cards and dispatches restore only for valid records and removal only for invalid records.
    @Test
    public void managesDisabledRuntimeRestoreAndInvalidRecordRemoval() throws InterruptedException {
        JavaRuntime restoredRuntime = runtime("C:/java/restored/bin/java.exe", "17", "Restored", false);
        DisabledJavaRuntimeEntry valid = DisabledJavaRuntimeEntry.available(
                restoredRuntime.getBinary().toString(),
                restoredRuntime.getBinary());
        DisabledJavaRuntimeEntry invalid = DisabledJavaRuntimeEntry.invalid("C:/missing/java.exe");
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                snapshot(true, List.of(), List.of(valid, invalid)));
        service.restoreTask = Task.completed(restoredRuntime);
        JavaManagementPanel panel = onEventDispatchThread(() ->
                new JavaManagementPanel(service, new FakeJavaManagementInteractions()));

        onEventDispatchThread(() -> {
            findComponent(panel, "javaManagementDisabled", AbstractButton.class).doClick();
            assertAll(
                    () -> assertFalse(findComponent(
                            panel, "javaManagementMainView", JPanel.class).isVisible()),
                    () -> assertTrue(findComponent(
                            panel, "javaManagementDisabledView", JPanel.class).isVisible()));
            JList<?> disabled = findComponent(panel, "javaManagementDisabledList", JList.class);
            disabled.setSelectedValue(valid, true);
            assertTrue(findComponent(
                    panel, "javaManagementDisabledRestore", AbstractButton.class).isEnabled());
            assertFalse(findComponent(
                    panel, "javaManagementDisabledRemove", AbstractButton.class).isEnabled());
            findComponent(panel, "javaManagementDisabledRestore", AbstractButton.class).doClick();
        });
        awaitCondition(() -> service.restoreCalls.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementDisabledRestore", AbstractButton.class).isEnabled()));

        service.publish(snapshot(true, List.of(restoredRuntime), List.of(invalid)));
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertTrue(findComponent(
                            panel, "javaManagementMainView", JPanel.class).isVisible()),
                    () -> assertEquals(restoredRuntime, panel.selectedRuntime()));
            findComponent(panel, "javaManagementDisabled", AbstractButton.class).doClick();
            JList<?> disabled = findComponent(panel, "javaManagementDisabledList", JList.class);
            disabled.setSelectedValue(invalid, true);
            assertFalse(findComponent(
                    panel, "javaManagementDisabledRestore", AbstractButton.class).isEnabled());
            assertTrue(findComponent(
                    panel, "javaManagementDisabledRemove", AbstractButton.class).isEnabled());
            findComponent(panel, "javaManagementDisabledRemove", AbstractButton.class).doClick();
        });
        awaitCondition(() -> service.removeDisabledCalls.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementDisabledBack", AbstractButton.class).isEnabled()));

        onEventDispatchThread(() -> {
            findComponent(panel, "javaManagementDisabledBack", AbstractButton.class).doClick();
            assertAll(
                    () -> assertTrue(findComponent(
                            panel, "javaManagementMainView", JPanel.class).isVisible()),
                    () -> assertFalse(findComponent(
                            panel, "javaManagementDisabledView", JPanel.class).isVisible()),
                    () -> assertEquals(valid, service.lastRestoredEntry.get()),
                    () -> assertEquals(invalid, service.lastRemovedEntry.get()));
            panel.close();
        });
    }

    /// Defers disabled path probing until the user selects exactly one unchecked row.
    @Test
    public void inspectsDisabledRuntimeOnlyAfterExplicitSelection() throws InterruptedException {
        DisabledJavaRuntimeEntry unchecked = DisabledJavaRuntimeEntry.unchecked("C:/java/lazy/bin/java.exe");
        DisabledJavaRuntimeEntry available = DisabledJavaRuntimeEntry.available(
                unchecked.configuredPath(),
                Path.of(unchecked.configuredPath()));
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                snapshot(true, List.of(), List.of(unchecked)));
        service.inspectTask = Task.completed(available);
        JavaManagementPanel panel = onEventDispatchThread(() ->
                new JavaManagementPanel(service, new FakeJavaManagementInteractions()));

        onEventDispatchThread(() -> {
            assertEquals(0, service.inspectCalls.get());
            findComponent(panel, "javaManagementDisabled", AbstractButton.class).doClick();
            JList<?> disabled = findComponent(panel, "javaManagementDisabledList", JList.class);
            assertAll(
                    () -> assertEquals(0, service.inspectCalls.get()),
                    () -> assertTrue(disabled.isSelectionEmpty()));
            disabled.setSelectedValue(unchecked, true);
        });

        awaitCondition(() -> service.inspectCalls.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementDisabledRestore", AbstractButton.class).isEnabled()));
        onEventDispatchThread(() -> {
            JList<?> disabled = findComponent(panel, "javaManagementDisabledList", JList.class);
            assertAll(
                    () -> assertEquals(available, disabled.getSelectedValue()),
                    () -> assertEquals(unchecked, service.lastInspectedEntry.get()),
                    () -> assertFalse(findComponent(
                            panel, "javaManagementDisabledRemove", AbstractButton.class).isEnabled()));
            panel.close();
        });
    }

    /// Enables exact-record removal when probing succeeded but the subsequent Java restore failed.
    @Test
    public void allowsForceRemovalAfterAvailableRestoreFails() throws InterruptedException {
        DisabledJavaRuntimeEntry available = DisabledJavaRuntimeEntry.available(
                "C:/java/broken/bin/java.exe",
                Path.of("C:/java/broken/bin/java.exe"));
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                snapshot(true, List.of(), List.of(available)));
        service.restoreTask = Task.supplyAsync(() -> {
            throw new IOException("Java metadata probe failed");
        });
        JavaManagementPanel panel = onEventDispatchThread(() ->
                new JavaManagementPanel(service, new FakeJavaManagementInteractions()));

        onEventDispatchThread(() -> {
            findComponent(panel, "javaManagementDisabled", AbstractButton.class).doClick();
            JList<?> disabled = findComponent(panel, "javaManagementDisabledList", JList.class);
            disabled.setSelectedValue(available, true);
            assertFalse(findComponent(
                    panel, "javaManagementDisabledRemove", AbstractButton.class).isEnabled());
            findComponent(panel, "javaManagementDisabledRestore", AbstractButton.class).doClick();
        });

        awaitCondition(() -> service.restoreCalls.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementDisabledRemove", AbstractButton.class).isEnabled()));
        onEventDispatchThread(() ->
                findComponent(panel, "javaManagementDisabledRemove", AbstractButton.class).doClick());
        awaitCondition(() -> service.removeDisabledCalls.get() == 1);

        assertEquals(available, service.lastRemovedEntry.get());
        onEventDispatchThread(panel::close);
    }

    /// Reveals a standard Java home with a release marker and otherwise reveals only the executable parent.
    @Test
    public void revealsJavaHomeWithoutExecutingTheBinary() throws IOException {
        Path root = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
        Path javaHome = Files.createDirectories(root.resolve("jdk"));
        Path bin = Files.createDirectories(javaHome.resolve("bin"));
        Path binary = Files.writeString(bin.resolve("java.exe"), "");
        Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=17");
        JavaRuntime runtime = runtime(binary.toString(), "17", "Test", false);
        FakeJavaManagementInteractions interactions = new FakeJavaManagementInteractions();
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                snapshot(true, List.of(runtime), List.of()));
        JavaManagementPanel panel = onEventDispatchThread(() -> new JavaManagementPanel(service, interactions));

        onEventDispatchThread(() -> {
            findComponent(panel, "javaManagementRuntimeList", JList.class).setSelectedValue(runtime, true);
            findComponent(panel, "javaManagementReveal", AbstractButton.class).doClick();
        });

        Path customDirectory = Files.createDirectories(root.resolve("custom"));
        Path customBinary = Files.writeString(customDirectory.resolve("launcher"), "");
        assertAll(
                () -> assertEquals(javaHome, interactions.revealedDirectory.get()),
                () -> assertEquals(javaHome, JavaManagementPanel.revealDirectoryForBinary(binary)),
                () -> assertEquals(customDirectory, JavaManagementPanel.revealDirectoryForBinary(customBinary)));
        onEventDispatchThread(panel::close);
    }

    /// Ignores a registration result that arrives after the panel closes and releases its snapshot subscription.
    @Test
    public void ignoresLateTaskCallbackAndSnapshotAfterClose() throws InterruptedException {
        JavaRuntime initialRuntime = runtime("C:/java/17/bin/java.exe", "17", "Initial", false);
        JavaRuntime lateRuntime = runtime("C:/java/21/bin/java.exe", "21", "Late", false);
        JavaRuntimeManagementSnapshot initial = snapshot(true, List.of(initialRuntime), List.of());
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(initial);
        CompletableFuture<@Nullable JavaRuntime> lateFuture = new CompletableFuture<>();
        service.addTask = Task.fromCompletableFuture(lateFuture);
        FakeJavaManagementInteractions interactions = new FakeJavaManagementInteractions();
        interactions.chosenPath = Path.of("C:/java/21");
        JavaManagementPanel panel = onEventDispatchThread(() -> new JavaManagementPanel(service, interactions));

        onEventDispatchThread(() -> {
            findComponent(panel, "javaManagementAdd", AbstractButton.class).doClick();
            assertEquals(1, service.addCalls.get());
            panel.close();
        });
        lateFuture.complete(lateRuntime);
        service.publish(snapshot(true, List.of(lateRuntime), List.of()));
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(initial, panel.displayedSnapshot()),
                () -> assertFalse(findComponent(panel, "javaManagementAdd", AbstractButton.class).isEnabled()),
                    () -> assertFalse(findComponent(panel, "javaManagementRefresh", AbstractButton.class).isEnabled())));
    }

    /// Defers every acquisition service call until explicit input and reloads capabilities when the user reopens it.
    @Test
    public void lazilyLoadsUnselectedAcquisitionCardAndReloadsOnReopen() throws InterruptedException {
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                snapshot(true, List.of(), List.of()));
        FakeJavaRuntimeAcquisitionService acquisitionService = new FakeJavaRuntimeAcquisitionService();
        JavaManagementPanel panel = onEventDispatchThread(() -> new JavaManagementPanel(
                service,
                acquisitionService,
                new FakeJavaManagementInteractions()));

        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(0, acquisitionService.loadCalls.get()),
                () -> assertEquals(0, acquisitionService.loadStarts.get()),
                () -> assertEquals(0, acquisitionService.inspectionCalls.get()),
                () -> assertEquals(0, acquisitionService.downloadCalls.get()),
                () -> assertEquals(0, acquisitionService.validationCalls.get()),
                () -> assertEquals(0, acquisitionService.installCalls.get()),
                () -> assertNull(findOptionalComponent(
                        panel,
                        "javaManagementAcquirePanel",
                        JavaRuntimeAcquisitionPanel.class)),
                () -> assertTrue(findComponent(
                        panel,
                        "javaManagementMainView",
                        JPanel.class).isVisible())));

        onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquire", AbstractButton.class).doClick());
        awaitCondition(() -> acquisitionService.loadStarts.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquireView", JPanel.class).isVisible()));

        onEventDispatchThread(() -> {
            loadMojangChoices(panel, 2);
            JList<?> choices = findComponent(panel, "javaManagementAcquireMojangList", JList.class);
            assertAll(
                    () -> assertEquals(1, acquisitionService.loadCalls.get()),
                    () -> assertEquals(2, choices.getModel().getSize()),
                    () -> assertTrue(choices.isSelectionEmpty()),
                    () -> assertFalse(findComponent(
                            panel,
                            "javaManagementAcquireDownload",
                            AbstractButton.class).isEnabled()));
            findComponent(panel, "javaManagementAcquireBack", AbstractButton.class).doClick();
            assertTrue(findComponent(panel, "javaManagementMainView", JPanel.class).isVisible());
            findComponent(panel, "javaManagementAcquire", AbstractButton.class).doClick();
        });
        awaitCondition(() -> acquisitionService.loadStarts.get() == 2 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquireView", JPanel.class).isVisible()));

        onEventDispatchThread(() -> {
            loadMojangChoices(panel, 2);
            JList<?> choices = findComponent(panel, "javaManagementAcquireMojangList", JList.class);
            assertAll(
                    () -> assertEquals(2, acquisitionService.loadCalls.get()),
                    () -> assertEquals(2, choices.getModel().getSize()),
                    () -> assertTrue(choices.isSelectionEmpty()));
            panel.close();
        });
    }

    /// Routes archive choosing, inspection, debounced validation, installation, and deferred runtime selection.
    @Test
    public void installsChosenArchiveAfterDebouncedNameValidation() throws InterruptedException {
        JavaRuntime installedRuntime = runtime(
                "C:/java/managed/archive-runtime/bin/java.exe",
                "21.0.8",
                "Eclipse Adoptium",
                true);
        Path archive = Path.of("runtime.ZIP").toAbsolutePath().normalize();
        LocalJavaArchiveInspection inspection = new LocalJavaArchiveInspection(
                archive,
                "suggested-runtime",
                "suggested-runtime",
                new JavaInfo(Platform.WINDOWS_X86_64, "21.0.8", "Eclipse Adoptium"),
                4096L,
                "0".repeat(64));
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                snapshot(true, List.of(), List.of()));
        FakeJavaRuntimeAcquisitionService acquisitionService = new FakeJavaRuntimeAcquisitionService();
        acquisitionService.archiveInspection.set(inspection);
        acquisitionService.installedRuntime.set(installedRuntime);
        FakeJavaManagementInteractions interactions = new FakeJavaManagementInteractions();
        interactions.chosenArchive = archive;
        JavaManagementPanel panel = onEventDispatchThread(() -> new JavaManagementPanel(
                service,
                acquisitionService,
                interactions));

        onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquire", AbstractButton.class).doClick());
        awaitCondition(() -> acquisitionService.loadStarts.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquireView", JPanel.class).isVisible()));
        onEventDispatchThread(() -> {
            findComponent(panel, "javaManagementAcquireArchiveMode", AbstractButton.class).doClick();
            findComponent(panel, "javaManagementAcquireChooseArchive", AbstractButton.class).doClick();
        });
        awaitCondition(() -> acquisitionService.validationCalls.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquireInstall", AbstractButton.class).isEnabled()));

        acquisitionService.validationCalls.set(0);
        acquisitionService.lastValidatedName.set(null);
        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(archive.toString(), findComponent(
                            panel,
                            "javaManagementAcquireArchivePath",
                            JTextField.class).getText()),
                    () -> assertEquals("21.0.8", findComponent(
                            panel,
                            "javaManagementAcquireArchiveVersion",
                            JTextField.class).getText()),
                    () -> assertEquals("Eclipse Adoptium", findComponent(
                            panel,
                            "javaManagementAcquireArchiveVendor",
                            JTextField.class).getText()),
                    () -> assertEquals(Platform.WINDOWS_X86_64.toString(), findComponent(
                            panel,
                            "javaManagementAcquireArchiveArchitecture",
                            JTextField.class).getText()));
            JTextField installName = findComponent(
                    panel,
                    "javaManagementAcquireInstallName",
                    JTextField.class);
            installName.setText("draft-runtime");
            installName.setText("archive-runtime");
        });
        Thread.sleep(100L);
        assertEquals(0, acquisitionService.validationCalls.get());
        awaitCondition(() -> acquisitionService.validationCalls.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquireInstall", AbstractButton.class).isEnabled()));

        onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquireInstall", AbstractButton.class).doClick());
        awaitCondition(() -> acquisitionService.installCalls.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementMainView", JPanel.class).isVisible()));

        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(1, acquisitionService.inspectionCalls.get()),
                () -> assertEquals(archive, acquisitionService.lastInspectedArchive.get()),
                () -> assertEquals("archive-runtime", acquisitionService.lastValidatedName.get()),
                () -> assertSame(inspection, acquisitionService.lastInstalledInspection.get()),
                () -> assertEquals("archive-runtime", acquisitionService.lastInstalledName.get()),
                () -> assertNull(findOptionalComponent(
                        panel,
                        "javaManagementAcquirePanel",
                        JavaRuntimeAcquisitionPanel.class)),
                () -> assertNull(panel.selectedRuntime())));
        service.publish(snapshot(true, List.of(installedRuntime), List.of()));
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertEquals(installedRuntime, panel.selectedRuntime());
            panel.close();
        });
    }

    /// A dropped runtime archive opens the existing acquisition card and starts its inspection flow.
    @Test
    public void droppedArchiveOpensAcquisitionAndStartsInspection() throws InterruptedException {
        Path archive = Path.of("dropped-runtime.TAR.GZ").toAbsolutePath().normalize();
        LocalJavaArchiveInspection inspection = new LocalJavaArchiveInspection(
                archive,
                "dropped-runtime",
                "dropped-runtime",
                new JavaInfo(Platform.WINDOWS_X86_64, "21", "Test"),
                1024L,
                "0".repeat(64));
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                snapshot(true, List.of(), List.of()));
        FakeJavaRuntimeAcquisitionService acquisitionService = new FakeJavaRuntimeAcquisitionService();
        acquisitionService.archiveInspection.set(inspection);
        JavaManagementPanel panel = onEventDispatchThread(() -> new JavaManagementPanel(
                service,
                acquisitionService,
                new FakeJavaManagementInteractions()));

        onEventDispatchThread(() -> {
            TransferHandler handler = Objects.requireNonNull(panel.getTransferHandler());
            assertTrue(handler.canImport(fileTransfer(panel, List.of(archive))));
            assertFalse(handler.canImport(fileTransfer(panel, List.of(Path.of("runtime.tgz")))));
            assertTrue(handler.importData(fileTransfer(panel, List.of(archive))));
        });
        awaitCondition(() -> acquisitionService.inspectionCalls.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquireView", JPanel.class).isVisible()));

        onEventDispatchThread(() -> {
            assertEquals(archive, acquisitionService.lastInspectedArchive.get());
            assertEquals(archive.toString(), findComponent(
                    panel,
                    "javaManagementAcquireArchivePath",
                    JTextField.class).getText());
            panel.close();
            assertNull(panel.getTransferHandler());
        });
    }

    /// Downloads only an explicitly selected Mojang row, then rebuilds the discarded card on the next open.
    @Test
    public void downloadsSelectedMojangRuntimeAndRebuildsAcquisitionCard() throws InterruptedException {
        JavaRuntime downloadedRuntime = runtime(
                "C:/java/managed/mojang-21/bin/java.exe",
                "21.0.8",
                "Microsoft",
                true);
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                snapshot(true, List.of(), List.of()));
        FakeJavaRuntimeAcquisitionService acquisitionService = new FakeJavaRuntimeAcquisitionService();
        acquisitionService.downloadedRuntime.set(downloadedRuntime);
        JavaManagementPanel panel = onEventDispatchThread(() -> new JavaManagementPanel(
                service,
                acquisitionService,
                new FakeJavaManagementInteractions()));

        onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquire", AbstractButton.class).doClick());
        awaitCondition(() -> acquisitionService.loadStarts.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquireView", JPanel.class).isVisible()));
        onEventDispatchThread(() -> {
            loadMojangChoices(panel, 2);
            findComponent(panel, "javaManagementAcquireMojangList", JList.class).setSelectedIndex(1);
            findComponent(panel, "javaManagementAcquireDownload", AbstractButton.class).doClick();
        });
        awaitCondition(() -> acquisitionService.downloadCalls.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementMainView", JPanel.class).isVisible()));

        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(GameJavaVersion.JAVA_21, acquisitionService.lastDownloadedVersion.get()),
                () -> assertNull(findOptionalComponent(
                        panel,
                        "javaManagementAcquirePanel",
                        JavaRuntimeAcquisitionPanel.class)),
                () -> assertNull(panel.selectedRuntime())));
        service.publish(snapshot(true, List.of(downloadedRuntime), List.of()));
        EdtDispatcher.executeAndWait(() -> { });
        awaitCondition(() -> onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquire", AbstractButton.class).isEnabled()));

        onEventDispatchThread(() -> {
            assertEquals(downloadedRuntime, panel.selectedRuntime());
            findComponent(panel, "javaManagementAcquire", AbstractButton.class).doClick();
        });
        awaitCondition(() -> acquisitionService.loadStarts.get() == 2 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquireView", JPanel.class).isVisible()));
        onEventDispatchThread(() -> {
            loadMojangChoices(panel, 2);
            JList<?> choices = findComponent(panel, "javaManagementAcquireMojangList", JList.class);
            assertAll(
                    () -> assertEquals(2, acquisitionService.loadCalls.get()),
                    () -> assertEquals(2, choices.getModel().getSize()),
                    () -> assertTrue(choices.isSelectionEmpty()));
            panel.close();
        });
    }

    /// Cancels a running acquisition load and rejects its result when the underlying future completes after close.
    @Test
    public void cancelsAcquisitionLoadAndRejectsLateResultAfterClose() throws InterruptedException {
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(
                snapshot(true, List.of(), List.of()));
        FakeJavaRuntimeAcquisitionService acquisitionService = new FakeJavaRuntimeAcquisitionService();
        CompletableFuture<JavaRuntimeAcquisitionSnapshot> lateSnapshot = new CompletableFuture<>();
        acquisitionService.pendingLoad.set(lateSnapshot);
        JavaManagementPanel panel = onEventDispatchThread(() -> new JavaManagementPanel(
                service,
                acquisitionService,
                new FakeJavaManagementInteractions()));

        onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquire", AbstractButton.class).doClick());
        awaitCondition(() -> acquisitionService.loadStarts.get() == 1);
        onEventDispatchThread(panel::close);
        lateSnapshot.complete(acquisitionSnapshot());
        awaitCondition(acquisitionService.loadCancellationObserved::get);
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(1, acquisitionService.loadCalls.get()),
                () -> assertNull(findOptionalComponent(
                        panel,
                        "javaManagementAcquirePanel",
                        JavaRuntimeAcquisitionPanel.class)),
                () -> assertFalse(findComponent(
                        panel,
                        "javaManagementAcquire",
                        AbstractButton.class).isEnabled())));
    }

    /// Runs explicit third-party loading, validated installation, and exceptional-platform link routing end to end.
    @Test
    public void runsDiscoSelectionInstallAndExternalLinkThroughParentBoundaries() throws Exception {
        FakeJavaRuntimeManagementService service = new FakeJavaRuntimeManagementService(snapshot(
                true,
                List.of(),
                List.of()));
        FakeJavaRuntimeAcquisitionService acquisitionService = new FakeJavaRuntimeAcquisitionService();
        FakeDiscoJavaRuntimeAcquisitionService discoService = new FakeDiscoJavaRuntimeAcquisitionService();
        FakeJavaManagementInteractions interactions = new FakeJavaManagementInteractions();
        JavaManagementPanel panel = onEventDispatchThread(() -> new JavaManagementPanel(
                service,
                acquisitionService,
                discoService,
                interactions));

        onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquire", AbstractButton.class).doClick());
        awaitCondition(() -> acquisitionService.loadStarts.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementAcquireView", JPanel.class).isVisible()));

        onEventDispatchThread(() -> {
            findComponent(panel, "javaManagementAcquireDiscoMode", AbstractButton.class).doClick();
            JComboBox<?> distributions = findComponent(
                    panel,
                    "javaManagementAcquireDiscoDistribution",
                    JComboBox.class);
            JComboBox<?> packages = findComponent(
                    panel,
                    "javaManagementAcquireDiscoPackageType",
                    JComboBox.class);
            assertEquals(-1, distributions.getSelectedIndex());
            assertEquals(-1, packages.getSelectedIndex());
            distributions.setSelectedItem(DiscoJavaDistribution.TEMURIN);
            packages.setSelectedItem(JavaPackageType.JDK);
        });

        awaitCondition(() -> discoService.loadCalls.get() == 1 && onEventDispatchThread(() ->
                findComponent(
                        panel,
                        "javaManagementAcquireDiscoVersions",
                        ViewportChoiceList.class).getChoiceModel().getSize() == 1));
        onEventDispatchThread(() -> {
            loadDiscoChoices(panel, 1);
            findComponent(panel, "javaManagementAcquireDiscoVersionList", JList.class).setSelectedIndex(0);
            JTextField name = findComponent(
                    panel,
                    "javaManagementAcquireDiscoInstallName",
                    JTextField.class);
            assertEquals("temurin-21-jdk", name.getText());
            assertTrue(findComponent(
                    panel,
                    "javaManagementAcquireDiscoInstall",
                    AbstractButton.class).isEnabled());
            findComponent(panel, "javaManagementAcquireExternalLink0", AbstractButton.class).doClick();
            findComponent(panel, "javaManagementAcquireDiscoInstall", AbstractButton.class).doClick();
        });

        awaitCondition(() -> discoService.installCalls.get() == 1 && onEventDispatchThread(() ->
                findComponent(panel, "javaManagementMainView", JPanel.class).isVisible()));
        assertAll(
                () -> assertEquals(DiscoJavaDistribution.TEMURIN, discoService.lastDistribution.get()),
                () -> assertEquals(JavaPackageType.JDK, discoService.lastPackageType.get()),
                () -> assertEquals("temurin-21-jdk", discoService.lastInstallName.get()),
                () -> assertEquals(
                        URI.create("https://www.zthread.cn/#product"),
                        interactions.openedExternalUri.get()));
        panel.close();
    }

    /// Waits briefly for an asynchronous task or EDT callback condition.
    ///
    /// @param condition completion condition
    /// @throws InterruptedException when the test thread is interrupted
    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for asynchronous Java-management state");
    }

    /// Creates one immutable runtime snapshot with a deterministic revision.
    ///
    /// @param writable whether persistent Java settings may be mutated
    /// @param runtimes active runtime values
    /// @param disabledRuntimes disabled runtime values
    /// @return initialized immutable runtime snapshot
    private static JavaRuntimeManagementSnapshot snapshot(
            boolean writable,
            List<JavaRuntime> runtimes,
            List<DisabledJavaRuntimeEntry> disabledRuntimes) {
        return new JavaRuntimeManagementSnapshot(true, 1L, writable, runtimes, disabledRuntimes);
    }

    /// Creates one deterministic Java runtime fixture without probing its executable.
    ///
    /// @param binary Java executable path
    /// @param version Java version text
    /// @param vendor Java vendor text
    /// @param managed whether the launcher owns the runtime installation
    /// @return Java runtime fixture
    private static JavaRuntime runtime(String binary, String version, String vendor, boolean managed) {
        return new JavaRuntime(
                Path.of(binary),
                new JavaInfo(Platform.WINDOWS_X86_64, version, vendor),
                managed,
                true);
    }

    /// Creates deterministic built-in acquisition choices with one installed and one downloadable runtime.
    ///
    /// @return immutable acquisition capability snapshot
    private static JavaRuntimeAcquisitionSnapshot acquisitionSnapshot() {
        return new JavaRuntimeAcquisitionSnapshot(
                Platform.WINDOWS_X86_64,
                List.of(
                        new MojangJavaRuntimeOption(GameJavaVersion.JAVA_17, true),
                        new MojangJavaRuntimeOption(GameJavaVersion.JAVA_21, false)));
    }

    /// Materializes the bounded Mojang fixture rows through the viewport-list loading contract.
    ///
    /// @param panel parent Java management panel
    /// @param itemCount exact fixture row count
    private static void loadMojangChoices(JavaManagementPanel panel, int itemCount) {
        ViewportChoiceList<?> choices = findComponent(
                panel,
                "javaManagementAcquireMojangChoices",
                ViewportChoiceList.class);
        IndexRange range = IndexRange.ofLength(0, itemCount);
        choices.getChoiceModel().applyPlan(new ViewportLoadPlan(
                range,
                range,
                Set.of(),
                ScrollDirection.STATIONARY,
                0.0,
                0));
    }

    /// Materializes a bounded third-party version fixture through the viewport-list loading contract.
    ///
    /// @param panel parent Java management panel
    /// @param itemCount exact fixture row count
    private static void loadDiscoChoices(JavaManagementPanel panel, int itemCount) {
        ViewportChoiceList<?> choices = findComponent(
                panel,
                "javaManagementAcquireDiscoVersions",
                ViewportChoiceList.class);
        IndexRange range = IndexRange.ofLength(0, itemCount);
        choices.getChoiceModel().applyPlan(new ViewportLoadPlan(
                range,
                range,
                Set.of(),
                ScrollDirection.STATIONARY,
                0.0,
                0));
    }

    /// Finds one named component in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @param type expected component type
    /// @param <T> component type
    /// @return matching component
    private static <T extends Component> T findComponent(Container root, String name, Class<T> type) {
        @Nullable T component = findOptionalComponent(root, name, type);
        if (component != null) {
            return component;
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

    /// Records chooser, confirmation, and reveal interactions without opening native UI.
    @NotNullByDefault
    private static final class FakeJavaManagementInteractions implements JavaManagementInteractions {
        /// Path returned by the next chooser invocation, or null to simulate cancellation.
        private @Nullable Path chosenPath;

        /// Archive path returned by the next acquisition chooser invocation, or null to simulate cancellation.
        private @Nullable Path chosenArchive;

        /// Whether destructive actions are confirmed.
        private boolean confirmation = true;

        /// Number of confirmation requests.
        private final AtomicInteger confirmCalls = new AtomicInteger();

        /// Most recently revealed directory.
        private final AtomicReference<@Nullable Path> revealedDirectory = new AtomicReference<>();

        /// Most recently opened external Java download destination.
        private final AtomicReference<@Nullable URI> openedExternalUri = new AtomicReference<>();

        /// Returns the configured chooser result.
        ///
        /// @param parent dialog parent component
        /// @return configured path, or null
        @Override
        public @Nullable Path chooseLocalRuntime(Component parent) {
            Objects.requireNonNull(parent, "parent");
            return chosenPath;
        }

        /// Returns the configured local Java archive chooser result.
        ///
        /// @param parent dialog parent component
        /// @return configured archive path, or null
        @Override
        public @Nullable Path chooseLocalJavaArchive(Component parent) {
            Objects.requireNonNull(parent, "parent");
            return chosenArchive;
        }

        /// Records and returns the configured confirmation result.
        ///
        /// @param parent dialog parent component
        /// @param message localized confirmation message
        /// @param title localized dialog title
        /// @return configured confirmation result
        @Override
        public boolean confirm(Component parent, String message, String title) {
            Objects.requireNonNull(parent, "parent");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(title, "title");
            confirmCalls.incrementAndGet();
            return confirmation;
        }

        /// Records one requested reveal directory.
        ///
        /// @param directory existing directory to open
        @Override
        public void revealDirectory(Path directory) {
            revealedDirectory.set(Objects.requireNonNull(directory, "directory"));
        }

        /// Records one external Java download destination.
        ///
        /// @param parent owning component
        /// @param uri validated external destination
        @Override
        public void openExternalJavaDownload(Component parent, URI uri) {
            Objects.requireNonNull(parent, "parent");
            openedExternalUri.set(Objects.requireNonNull(uri, "uri"));
        }
    }

    /// Provides stopped, observable acquisition tasks without filesystem or network access.
    @NotNullByDefault
    private static final class FakeJavaRuntimeAcquisitionService implements JavaRuntimeAcquisitionService {
        /// Number of capability task requests.
        private final AtomicInteger loadCalls = new AtomicInteger();

        /// Number of capability task bodies actually started by the parent.
        private final AtomicInteger loadStarts = new AtomicInteger();

        /// Number of archive inspection task requests.
        private final AtomicInteger inspectionCalls = new AtomicInteger();

        /// Number of Mojang download task requests.
        private final AtomicInteger downloadCalls = new AtomicInteger();

        /// Number of installation-name validations that survived parent debouncing.
        private final AtomicInteger validationCalls = new AtomicInteger();

        /// Number of local archive installation task requests.
        private final AtomicInteger installCalls = new AtomicInteger();

        /// Optional capability gate used to hold a running load until a test completes it.
        private final AtomicReference<@Nullable CompletableFuture<JavaRuntimeAcquisitionSnapshot>> pendingLoad =
                new AtomicReference<>();

        /// Whether a blocked capability task observed the parent's cooperative cancellation request.
        private final AtomicBoolean loadCancellationObserved = new AtomicBoolean();

        /// Capability snapshot returned by an unblocked load.
        private final AtomicReference<JavaRuntimeAcquisitionSnapshot> acquisitionSnapshot =
                new AtomicReference<>(JavaManagementPanelTest.acquisitionSnapshot());

        /// Inspection returned for the next supported archive.
        private final AtomicReference<LocalJavaArchiveInspection> archiveInspection = new AtomicReference<>(
                new LocalJavaArchiveInspection(
                        Path.of("runtime.zip"),
                        "runtime",
                        "runtime",
                        new JavaInfo(Platform.WINDOWS_X86_64, "21", "Test"),
                        -1L,
                        ""));

        /// Runtime returned by the next Mojang download.
        private final AtomicReference<JavaRuntime> downloadedRuntime = new AtomicReference<>(runtime(
                "C:/java/managed/mojang/bin/java.exe",
                "21",
                "Mojang",
                true));

        /// Runtime returned by the next local archive installation.
        private final AtomicReference<JavaRuntime> installedRuntime = new AtomicReference<>(runtime(
                "C:/java/managed/archive/bin/java.exe",
                "21",
                "Archive",
                true));

        /// Validation status returned for the latest candidate.
        private final AtomicReference<JavaRuntimeInstallNameStatus> validationStatus =
                new AtomicReference<>(JavaRuntimeInstallNameStatus.VALID);

        /// Most recently inspected archive path.
        private final AtomicReference<@Nullable Path> lastInspectedArchive = new AtomicReference<>();

        /// Most recently requested Mojang runtime version.
        private final AtomicReference<@Nullable GameJavaVersion> lastDownloadedVersion = new AtomicReference<>();

        /// Most recently validated installation name.
        private final AtomicReference<@Nullable String> lastValidatedName = new AtomicReference<>();

        /// Most recent inspection passed to archive installation.
        private final AtomicReference<@Nullable LocalJavaArchiveInspection> lastInstalledInspection =
                new AtomicReference<>();

        /// Most recent managed name passed to archive installation.
        private final AtomicReference<@Nullable String> lastInstalledName = new AtomicReference<>();

        /// Creates an in-memory service with deterministic acquisition fixtures.
        private FakeJavaRuntimeAcquisitionService() {
        }

        /// Creates a stopped capability task whose body records actual startup.
        ///
        /// @return stopped capability task
        @Override
        public Task<JavaRuntimeAcquisitionSnapshot> loadSnapshot() {
            loadCalls.incrementAndGet();
            @Nullable CompletableFuture<JavaRuntimeAcquisitionSnapshot> gate = pendingLoad.get();
            return new ControlledAcquisitionLoadTask(
                    gate,
                    acquisitionSnapshot.get(),
                    loadStarts,
                    loadCancellationObserved);
        }

        /// Performs lexical, case-insensitive validation of supported archive suffixes.
        ///
        /// @param archiveFile candidate archive path
        /// @return whether the path ends with `.zip` or `.tar.gz`
        @Override
        public boolean supportsLocalArchive(Path archiveFile) {
            String normalized = Objects.requireNonNull(archiveFile, "archiveFile")
                    .toString()
                    .toLowerCase(Locale.ROOT);
            return normalized.endsWith(".zip") || normalized.endsWith(".tar.gz");
        }

        /// Records one explicit Mojang version and returns a stopped completed download task.
        ///
        /// @param version explicitly selected Mojang runtime version
        /// @return stopped configured download task
        @Override
        public Task<JavaRuntime> downloadMojangRuntime(GameJavaVersion version) {
            lastDownloadedVersion.set(Objects.requireNonNull(version, "version"));
            downloadCalls.incrementAndGet();
            return Task.completed(downloadedRuntime.get());
        }

        /// Records one explicit archive path and returns a stopped completed inspection task.
        ///
        /// @param archiveFile selected archive path
        /// @return stopped configured inspection task
        @Override
        public Task<LocalJavaArchiveInspection> inspectLocalArchive(Path archiveFile) {
            lastInspectedArchive.set(Objects.requireNonNull(archiveFile, "archiveFile"));
            inspectionCalls.incrementAndGet();
            return Task.completed(archiveInspection.get());
        }

        /// Records one validation that survived parent debouncing.
        ///
        /// @param inspection current archive inspection
        /// @param name current installation-name candidate
        /// @return configured validation status
        @Override
        public JavaRuntimeInstallNameStatus validateInstallName(
                LocalJavaArchiveInspection inspection,
                String name) {
            Objects.requireNonNull(inspection, "inspection");
            lastValidatedName.set(Objects.requireNonNull(name, "name"));
            validationCalls.incrementAndGet();
            return validationStatus.get();
        }

        /// Records one validated archive installation and returns its stopped completed task.
        ///
        /// @param inspection inspected archive metadata
        /// @param name validated managed-runtime name
        /// @return stopped configured installation task
        @Override
        public Task<JavaRuntime> installLocalArchive(
                LocalJavaArchiveInspection inspection,
                String name) {
            lastInstalledInspection.set(Objects.requireNonNull(inspection, "inspection"));
            lastInstalledName.set(Objects.requireNonNull(name, "name"));
            installCalls.incrementAndGet();
            return Task.completed(installedRuntime.get());
        }
    }

    /// Provides deterministic third-party choices, validation, and installation without network access.
    @NotNullByDefault
    private static final class FakeDiscoJavaRuntimeAcquisitionService
            implements DiscoJavaRuntimeAcquisitionService {
        /// Number of explicit remote version requests.
        private final AtomicInteger loadCalls = new AtomicInteger();

        /// Number of validated installation task requests.
        private final AtomicInteger installCalls = new AtomicInteger();

        /// Most recent selected distribution.
        private final AtomicReference<@Nullable DiscoJavaDistribution> lastDistribution = new AtomicReference<>();

        /// Most recent selected package type.
        private final AtomicReference<@Nullable JavaPackageType> lastPackageType = new AtomicReference<>();

        /// Most recent validated installation name.
        private final AtomicReference<@Nullable String> lastInstallName = new AtomicReference<>();

        /// Deterministic explicit version result.
        private final DiscoJavaRemoteVersion version = new DiscoJavaRemoteVersion(
                "temurin-21",
                "zip",
                "temurin",
                21,
                "21.0.8+9",
                "21.0.8+9",
                21,
                true,
                "ga",
                "lts",
                "linux",
                "c_std_lib",
                "riscv64",
                "unknown",
                "jdk",
                false,
                true,
                "temurin-21.zip",
                new DiscoJavaRemoteVersion.Links(
                        "https://example.invalid/pkg/temurin-21",
                        "https://example.invalid/download/temurin-21"),
                true,
                "yes",
                "https://example.invalid/tck",
                "yes",
                "https://example.invalid/aqavit",
                1024L);

        /// Returns the deterministic RISC-V platform used to expose its external fallback link.
        @Override
        public Platform platform() {
            return Platform.LINUX_RISCV64;
        }

        /// Returns one supported distribution without remote access.
        @Override
        public @Unmodifiable List<DiscoJavaDistribution> supportedDistributions() {
            return List.of(DiscoJavaDistribution.TEMURIN);
        }

        /// Returns deterministic non-JavaFX package choices.
        ///
        /// @param distribution explicit distribution
        /// @return immutable JRE and JDK choices
        @Override
        public @Unmodifiable List<JavaPackageType> supportedPackageTypes(
                DiscoJavaDistribution distribution) {
            Objects.requireNonNull(distribution, "distribution");
            return List.of(JavaPackageType.JRE, JavaPackageType.JDK);
        }

        /// Records one explicit version request and returns a stopped completed task.
        ///
        /// @param distribution explicit distribution
        /// @param packageType explicit package type
        /// @return stopped immutable result task
        @Override
        public Task<@Unmodifiable List<DiscoJavaRemoteVersion>> loadVersions(
                DiscoJavaDistribution distribution,
                JavaPackageType packageType) {
            lastDistribution.set(Objects.requireNonNull(distribution, "distribution"));
            lastPackageType.set(Objects.requireNonNull(packageType, "packageType"));
            loadCalls.incrementAndGet();
            return Task.completed(List.of(version));
        }

        /// Returns the deterministic managed name for the explicit fixture.
        ///
        /// @param distribution explicit distribution
        /// @param packageType explicit package type
        /// @param selectedVersion explicit version
        /// @return fixture installation name
        @Override
        public String suggestedInstallName(
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion selectedVersion) {
            Objects.requireNonNull(distribution, "distribution");
            Objects.requireNonNull(packageType, "packageType");
            Objects.requireNonNull(selectedVersion, "selectedVersion");
            return "temurin-21-jdk";
        }

        /// Accepts the deterministic name and rejects every other candidate.
        ///
        /// @param name proposed managed-runtime name
        /// @return exact fixture validation status
        @Override
        public JavaRuntimeInstallNameStatus validateInstallName(String name) {
            return "temurin-21-jdk".equals(Objects.requireNonNull(name, "name"))
                    ? JavaRuntimeInstallNameStatus.VALID
                    : JavaRuntimeInstallNameStatus.INVALID_CHARACTERS;
        }

        /// Records one validated selection and returns a stopped completed managed runtime task.
        ///
        /// @param distribution explicit distribution
        /// @param packageType explicit package type
        /// @param selectedVersion explicit version
        /// @param installName validated managed-runtime name
        /// @return stopped completed installation task
        @Override
        public Task<JavaRuntime> install(
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion selectedVersion,
                String installName) {
            lastDistribution.set(Objects.requireNonNull(distribution, "distribution"));
            lastPackageType.set(Objects.requireNonNull(packageType, "packageType"));
            Objects.requireNonNull(selectedVersion, "selectedVersion");
            lastInstallName.set(Objects.requireNonNull(installName, "installName"));
            installCalls.incrementAndGet();
            return Task.completed(runtime(
                    "C:/java/managed/disco/bin/java.exe",
                    "21.0.8",
                    "Eclipse Temurin",
                    true));
        }
    }

    /// Holds one capability load until its optional test gate opens and records cooperative cancellation.
    @NotNullByDefault
    private static final class ControlledAcquisitionLoadTask extends Task<JavaRuntimeAcquisitionSnapshot> {
        /// Optional future that delays task completion.
        private final @Nullable CompletableFuture<JavaRuntimeAcquisitionSnapshot> gate;

        /// Capability result captured when the stopped task is created.
        private final JavaRuntimeAcquisitionSnapshot snapshot;

        /// Counter incremented only when the task body actually starts.
        private final AtomicInteger starts;

        /// Shared cancellation observation recorded after the optional gate opens.
        private final AtomicBoolean cancellationObserved;

        /// Creates one stopped and optionally gated capability task.
        ///
        /// @param gate optional future controlling task completion
        /// @param snapshot capability result returned after startup
        /// @param starts actual task-body startup counter
        /// @param cancellationObserved cooperative cancellation observation target
        private ControlledAcquisitionLoadTask(
                @Nullable CompletableFuture<JavaRuntimeAcquisitionSnapshot> gate,
                JavaRuntimeAcquisitionSnapshot snapshot,
                AtomicInteger starts,
                AtomicBoolean cancellationObserved) {
            this.gate = gate;
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            this.starts = Objects.requireNonNull(starts, "starts");
            this.cancellationObserved = Objects.requireNonNull(
                    cancellationObserved,
                    "cancellationObserved");
        }

        /// Waits for the optional gate, records the executor's cancellation flag, and stores the captured snapshot.
        @Override
        public void execute() throws Exception {
            starts.incrementAndGet();
            JavaRuntimeAcquisitionSnapshot result = gate == null ? snapshot : gate.get();
            cancellationObserved.set(isCancelled());
            setResult(result);
        }
    }

    /// Thread-safe fake Java service with independently replaceable operation tasks.
    @NotNullByDefault
    private static final class FakeJavaRuntimeManagementService implements JavaRuntimeManagementService {
        /// Current immutable runtime snapshot.
        private final AtomicReference<JavaRuntimeManagementSnapshot> current;

        /// Runtime snapshot transition publisher.
        private final ValueChangeSupport<JavaRuntimeManagementSnapshot> changes = new ValueChangeSupport<>(this);

        /// Number of local refresh invocations.
        private final AtomicInteger refreshCalls = new AtomicInteger();

        /// Number of local registration task requests.
        private final AtomicInteger addCalls = new AtomicInteger();

        /// Number of unmanaged disable task requests.
        private final AtomicInteger disableCalls = new AtomicInteger();

        /// Number of managed uninstall task requests.
        private final AtomicInteger uninstallCalls = new AtomicInteger();

        /// Number of disabled record inspection task requests.
        private final AtomicInteger inspectCalls = new AtomicInteger();

        /// Number of disabled record restore task requests.
        private final AtomicInteger restoreCalls = new AtomicInteger();

        /// Number of invalid disabled record removal task requests.
        private final AtomicInteger removeDisabledCalls = new AtomicInteger();

        /// Most recently disabled unmanaged runtime.
        private final AtomicReference<@Nullable JavaRuntime> lastDisabledRuntime = new AtomicReference<>();

        /// Most recently uninstalled managed runtime.
        private final AtomicReference<@Nullable JavaRuntime> lastUninstalledRuntime = new AtomicReference<>();

        /// Most recently inspected disabled record.
        private final AtomicReference<@Nullable DisabledJavaRuntimeEntry> lastInspectedEntry = new AtomicReference<>();

        /// Most recently restored disabled record.
        private final AtomicReference<@Nullable DisabledJavaRuntimeEntry> lastRestoredEntry = new AtomicReference<>();

        /// Most recently removed invalid disabled record.
        private final AtomicReference<@Nullable DisabledJavaRuntimeEntry> lastRemovedEntry = new AtomicReference<>();

        /// Task returned for local registration.
        private Task<JavaRuntime> addTask = Task.completed(
                runtime("C:/java/added/bin/java.exe", "17", "Added", false));

        /// Task returned for unmanaged disable.
        private Task<@Nullable Void> disableTask = Task.completed(null);

        /// Task returned for managed uninstall.
        private Task<@Nullable Void> uninstallTask = Task.completed(null);

        /// Task returned for selected disabled record inspection.
        private Task<DisabledJavaRuntimeEntry> inspectTask = Task.completed(
                DisabledJavaRuntimeEntry.invalid("C:/java/invalid/bin/java.exe"));

        /// Task returned for disabled record restoration.
        private Task<JavaRuntime> restoreTask = Task.completed(
                runtime("C:/java/restored/bin/java.exe", "17", "Restored", false));

        /// Task returned for invalid disabled record removal.
        private Task<@Nullable Void> removeDisabledTask = Task.completed(null);

        /// Creates a fake service with one initial snapshot.
        ///
        /// @param initialSnapshot initial runtime state
        private FakeJavaRuntimeManagementService(JavaRuntimeManagementSnapshot initialSnapshot) {
            current = new AtomicReference<>(Objects.requireNonNull(initialSnapshot, "initialSnapshot"));
        }

        /// Returns the current fake runtime state.
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

        /// Returns the configured local registration task.
        ///
        /// @param selectedPath selected Java executable or home path
        /// @return configured stopped task
        @Override
        public Task<JavaRuntime> addLocalRuntime(Path selectedPath) {
            Objects.requireNonNull(selectedPath, "selectedPath");
            addCalls.incrementAndGet();
            return addTask;
        }

        /// Records an unmanaged runtime and returns the configured disable task.
        ///
        /// @param runtime unmanaged runtime to disable
        /// @return configured stopped task
        @Override
        public Task<@Nullable Void> disableLocalRuntime(JavaRuntime runtime) {
            lastDisabledRuntime.set(Objects.requireNonNull(runtime, "runtime"));
            disableCalls.incrementAndGet();
            return disableTask;
        }

        /// Records a managed runtime and returns the configured uninstall task.
        ///
        /// @param runtime managed runtime to uninstall
        /// @return configured stopped task
        @Override
        public Task<@Nullable Void> uninstallManagedRuntime(JavaRuntime runtime) {
            lastUninstalledRuntime.set(Objects.requireNonNull(runtime, "runtime"));
            uninstallCalls.incrementAndGet();
            return uninstallTask;
        }

        /// Records a disabled record and returns the configured inspection task.
        ///
        /// @param disabledRuntime disabled executable entry to inspect
        /// @return configured stopped inspection task
        @Override
        public Task<DisabledJavaRuntimeEntry> inspectDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime) {
            lastInspectedEntry.set(Objects.requireNonNull(disabledRuntime, "disabledRuntime"));
            inspectCalls.incrementAndGet();
            return inspectTask;
        }

        /// Records a disabled record and returns the configured restore task.
        ///
        /// @param disabledRuntime disabled executable entry to restore
        /// @return configured stopped task
        @Override
        public Task<JavaRuntime> restoreDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime) {
            lastRestoredEntry.set(Objects.requireNonNull(disabledRuntime, "disabledRuntime"));
            restoreCalls.incrementAndGet();
            return restoreTask;
        }

        /// Records an invalid disabled record and returns the configured removal task.
        ///
        /// @param disabledRuntime invalid disabled executable entry to remove
        /// @return configured stopped task
        @Override
        public Task<@Nullable Void> removeDisabledRuntime(DisabledJavaRuntimeEntry disabledRuntime) {
            lastRemovedEntry.set(Objects.requireNonNull(disabledRuntime, "disabledRuntime"));
            removeDisabledCalls.incrementAndGet();
            return removeDisabledTask;
        }

        /// Publishes one replacement runtime snapshot on the caller thread.
        ///
        /// @param replacement replacement immutable runtime state
        private void publish(JavaRuntimeManagementSnapshot replacement) {
            JavaRuntimeManagementSnapshot previous = current.getAndSet(
                    Objects.requireNonNull(replacement, "replacement"));
            changes.fireChange(previous, replacement);
        }
    }
}
