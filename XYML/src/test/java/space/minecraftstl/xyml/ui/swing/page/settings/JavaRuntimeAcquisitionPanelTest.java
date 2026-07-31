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
import space.minecraftstl.xyml.download.java.JavaPackageType;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaDistribution;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaRemoteVersion;
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.java.JavaInfo;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.ScrollDirection;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.choice.ViewportLoadPlan;
import space.minecraftstl.xyml.util.platform.Platform;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;
import java.awt.Component;
import java.awt.Container;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the pure Swing Java acquisition input surface without starting tasks or opening native UI.
@NotNullByDefault
public final class JavaRuntimeAcquisitionPanelTest {
    /// Verifies that Mojang choices begin unselected, remain single-select, and block installed downloads.
    @Test
    public void requiresExplicitSingleMojangSelectionAndBlocksInstalledDownload() {
        RecordingListener listener = new RecordingListener();
        JavaRuntimeAcquisitionPanel panel = onEventDispatchThread(() ->
                new JavaRuntimeAcquisitionPanel(snapshot(), listener));

        onEventDispatchThread(() -> {
            JList<?> list = findComponent(panel, "javaManagementAcquireMojangList", JList.class);
            AbstractButton download = findComponent(panel, "javaManagementAcquireDownload", AbstractButton.class);
            assertEquals(ListSelectionModel.SINGLE_SELECTION, list.getSelectionMode());
            assertTrue(list.isSelectionEmpty());
            assertFalse(download.isEnabled());
            loadMojangChoices(panel, 2);

            list.setSelectedIndex(0);
            assertEquals(1, list.getSelectedIndices().length);
            assertFalse(download.isEnabled());
            String installedTooltip = Objects.requireNonNull(download.getToolTipText(), "installed tooltip");
            assertFalse(installedTooltip.isBlank());

            list.setSelectedIndex(1);
            assertEquals(1, list.getSelectedIndices().length);
            assertTrue(download.isEnabled());
            assertFalse(installedTooltip.equals(download.getToolTipText()));
            download.doClick();
            assertEquals(GameJavaVersion.JAVA_21, listener.downloadedVersion);

            panel.applySnapshot(snapshot());
            assertNull(panel.selectedMojangVersion());
            loadMojangChoices(panel, 2);
            assertEquals(GameJavaVersion.JAVA_21, panel.selectedMojangVersion());
            panel.close();
        });
    }

    /// Rejects an older inspection when the user selects the same archive path again.
    @Test
    public void rejectsStaleInspectionForRepeatedSamePathSelection() {
        RecordingListener listener = new RecordingListener();
        JavaRuntimeAcquisitionPanel panel = onEventDispatchThread(() ->
                new JavaRuntimeAcquisitionPanel(snapshot(), listener));

        onEventDispatchThread(() -> {
            Path archive = Path.of("runtime.zip").toAbsolutePath().normalize();
            assertTrue(panel.selectArchive(archive));
            long staleRevision = listener.inspectionRevision;
            assertTrue(panel.selectArchive(archive));
            long currentRevision = listener.inspectionRevision;
            assertTrue(currentRevision > staleRevision);

            panel.applyArchiveInspection(staleRevision, inspection(archive, "stale-jdk"));
            assertNull(listener.validationInspection);
            assertEquals(
                    "",
                    findComponent(
                            panel,
                            "javaManagementAcquireArchiveVersion",
                            JTextField.class).getText());

            LocalJavaArchiveInspection currentInspection = inspection(archive, "current-jdk");
            panel.applyArchiveInspection(currentRevision, currentInspection);
            assertSame(currentInspection, listener.validationInspection);
            assertEquals(currentRevision, listener.validationRevision);
            panel.close();
        });
    }

    /// Preserves the explicit Mojang choice, archive path, and edited install-name draft across both modes.
    @Test
    public void preservesSelectionAndArchiveDraftAcrossModes() {
        RecordingListener listener = new RecordingListener();
        JavaRuntimeAcquisitionPanel panel = onEventDispatchThread(() ->
                new JavaRuntimeAcquisitionPanel(snapshot(), listener));

        onEventDispatchThread(() -> {
            loadMojangChoices(panel, 2);
            findComponent(panel, "javaManagementAcquireMojangList", JList.class).setSelectedIndex(1);
            findComponent(panel, "javaManagementAcquireArchiveMode", AbstractButton.class).doClick();

            Path archive = Path.of("runtime.zip").toAbsolutePath().normalize();
            assertTrue(panel.selectArchive(archive));
            LocalJavaArchiveInspection inspection = inspection(archive, "suggested-jdk");
            panel.applyArchiveInspection(listener.inspectionRevision, inspection);
            assertEquals("suggested-jdk", listener.validationCandidate);
            panel.applyInstallNameStatus(
                    listener.validationRevision,
                    inspection,
                    "suggested-jdk",
                    JavaRuntimeInstallNameStatus.VALID);

            JTextField name = findComponent(panel, "javaManagementAcquireInstallName", JTextField.class);
            assertEquals("suggested-jdk", name.getText());
            assertEquals(Boolean.TRUE, name.getClientProperty("JTextField.showClearButton"));
            name.setText("custom-jdk");
            assertEquals("custom-jdk", listener.validationCandidate);
            panel.applyInstallNameStatus(
                    listener.validationRevision,
                    inspection,
                    "custom-jdk",
                    JavaRuntimeInstallNameStatus.VALID);

            findComponent(panel, "javaManagementAcquireMojangMode", AbstractButton.class).doClick();
            assertEquals(GameJavaVersion.JAVA_21, panel.selectedMojangVersion());
            findComponent(panel, "javaManagementAcquireArchiveMode", AbstractButton.class).doClick();
            assertEquals(archive, panel.selectedArchive());
            assertEquals("custom-jdk", name.getText());

            findComponent(panel, "javaManagementAcquireInstall", AbstractButton.class).doClick();
            assertSame(inspection, listener.installedInspection);
            assertEquals("custom-jdk", listener.installedName);
            panel.close();
        });
    }

    /// Refuses to populate an untouched draft until the parent confirms that the suggestion is valid.
    @Test
    public void populatesOnlyAValidSuggestedInstallName() {
        RecordingListener listener = new RecordingListener();
        JavaRuntimeAcquisitionPanel panel = onEventDispatchThread(() ->
                new JavaRuntimeAcquisitionPanel(snapshot(), listener));

        onEventDispatchThread(() -> {
            JTextField name = findComponent(panel, "javaManagementAcquireInstallName", JTextField.class);
            Path invalidArchive = Path.of("invalid.zip").toAbsolutePath().normalize();
            assertTrue(panel.selectArchive(invalidArchive));
            LocalJavaArchiveInspection invalidInspection = inspection(invalidArchive, "CON");
            panel.applyArchiveInspection(listener.inspectionRevision, invalidInspection);
            assertEquals("", name.getText());
            panel.applyInstallNameStatus(
                    listener.validationRevision,
                    invalidInspection,
                    "CON",
                    JavaRuntimeInstallNameStatus.RESERVED_PLATFORM_NAME);
            assertEquals("", name.getText());

            Path validArchive = Path.of("valid.tar.gz").toAbsolutePath().normalize();
            assertTrue(panel.selectArchive(validArchive));
            LocalJavaArchiveInspection validInspection = inspection(validArchive, "temurin-21");
            panel.applyArchiveInspection(listener.inspectionRevision, validInspection);
            assertEquals("", name.getText());
            panel.applyInstallNameStatus(
                    listener.validationRevision,
                    validInspection,
                    "temurin-21",
                    JavaRuntimeInstallNameStatus.VALID);
            assertEquals("temurin-21", name.getText());
            panel.close();
        });
    }

    /// Accepts exactly one supported archive transfer and rejects multiple or unsupported files.
    @Test
    public void acceptsOnlyOneSupportedDroppedArchive() {
        RecordingListener listener = new RecordingListener();
        JavaRuntimeAcquisitionPanel panel = onEventDispatchThread(() ->
                new JavaRuntimeAcquisitionPanel(snapshot(), listener));

        onEventDispatchThread(() -> {
            TransferHandler handler = Objects.requireNonNull(panel.getTransferHandler(), "transfer handler");
            TransferHandler.TransferSupport multiple = support(
                    panel,
                    List.of(new File("one.zip"), new File("two.tar.gz")));
            TransferHandler.TransferSupport unsupported = support(panel, List.of(new File("runtime.7z")));
            TransferHandler.TransferSupport supported = support(panel, List.of(new File("runtime.zip")));

            assertFalse(handler.canImport(multiple));
            assertFalse(handler.importData(multiple));
            assertFalse(handler.canImport(unsupported));
            assertTrue(handler.canImport(supported));
            assertTrue(handler.importData(supported));
            assertEquals(
                    Path.of("runtime.zip").toAbsolutePath().normalize(),
                    listener.inspectedArchive);
            panel.close();
        });
    }

    /// Disables return, segmented modes, list input, chooser, and both operations while retaining state.
    @Test
    public void disablesAllCommandsWhileBusyAndRestoresStateAfterward() {
        RecordingListener listener = new RecordingListener();
        JavaRuntimeAcquisitionPanel panel = onEventDispatchThread(() ->
                new JavaRuntimeAcquisitionPanel(snapshot(), listener));

        onEventDispatchThread(() -> {
            loadMojangChoices(panel, 2);
            findComponent(panel, "javaManagementAcquireMojangList", JList.class).setSelectedIndex(1);
            Path archive = Path.of("runtime.zip").toAbsolutePath().normalize();
            assertTrue(panel.selectArchive(archive));
            LocalJavaArchiveInspection inspection = inspection(archive, "runtime-21");
            panel.applyArchiveInspection(listener.inspectionRevision, inspection);
            panel.applyInstallNameStatus(
                    listener.validationRevision,
                    inspection,
                    "runtime-21",
                    JavaRuntimeInstallNameStatus.VALID);
            findComponent(panel, "javaManagementAcquireMojangMode", AbstractButton.class).doClick();

            panel.setBusy(true);
            assertFalse(findComponent(panel, "javaManagementAcquireBack", AbstractButton.class).isEnabled());
            assertFalse(findComponent(panel, "javaManagementAcquireMojangMode", AbstractButton.class).isEnabled());
            assertFalse(findComponent(panel, "javaManagementAcquireArchiveMode", AbstractButton.class).isEnabled());
            assertFalse(findComponent(panel, "javaManagementAcquireMojangList", JList.class).isEnabled());
            assertFalse(findComponent(panel, "javaManagementAcquireDownload", AbstractButton.class).isEnabled());
            assertFalse(findComponent(panel, "javaManagementAcquireChooseArchive", AbstractButton.class).isEnabled());
            assertFalse(findComponent(panel, "javaManagementAcquireInstall", AbstractButton.class).isEnabled());

            panel.setBusy(false);
            assertTrue(findComponent(panel, "javaManagementAcquireBack", AbstractButton.class).isEnabled());
            assertTrue(findComponent(panel, "javaManagementAcquireMojangMode", AbstractButton.class).isEnabled());
            assertTrue(findComponent(panel, "javaManagementAcquireArchiveMode", AbstractButton.class).isEnabled());
            assertTrue(findComponent(panel, "javaManagementAcquireDownload", AbstractButton.class).isEnabled());
            assertTrue(findComponent(panel, "javaManagementAcquireChooseArchive", AbstractButton.class).isEnabled());
            assertTrue(findComponent(panel, "javaManagementAcquireInstall", AbstractButton.class).isEnabled());
            assertEquals(GameJavaVersion.JAVA_21, panel.selectedMojangVersion());
            assertEquals(archive, panel.selectedArchive());
            panel.close();
        });
    }

    /// Releases viewport resources and prevents chooser, drop, return, and operation callbacks after closure.
    @Test
    public void closeRejectsFutureInputAndReleasesViewportChoices() {
        RecordingListener listener = new RecordingListener();
        JavaRuntimeAcquisitionPanel panel = onEventDispatchThread(() ->
                new JavaRuntimeAcquisitionPanel(snapshot(), listener));

        onEventDispatchThread(() -> {
            loadMojangChoices(panel, 2);
            findComponent(panel, "javaManagementAcquireMojangList", JList.class).setSelectedIndex(1);
            ViewportChoiceList<?> choices = findComponent(
                    panel,
                    "javaManagementAcquireMojangChoices",
                    ViewportChoiceList.class);
            panel.close();

            assertEquals(0, choices.getChoiceModel().getSize());
            assertNull(panel.getTransferHandler());
            assertFalse(panel.selectArchive(Path.of("late.zip")));
            findComponent(panel, "javaManagementAcquireBack", AbstractButton.class).doClick();
            findComponent(panel, "javaManagementAcquireChooseArchive", AbstractButton.class).doClick();
            findComponent(panel, "javaManagementAcquireDownload", AbstractButton.class).doClick();
            assertEquals(0, listener.backCalls);
            assertEquals(0, listener.chooserCalls);
            assertNull(listener.downloadedVersion);
            assertNull(listener.inspectedArchive);
        });
    }

    /// Starts in the third-party card when Mojang has no choices while keeping all three choices explicit.
    @Test
    public void exposesThreeModesWithoutDefaultDiscoSelectionsOrJavaFxPackages() {
        RecordingListener listener = new RecordingListener();
        JavaRuntimeAcquisitionPanel panel = onEventDispatchThread(() ->
                new JavaRuntimeAcquisitionPanel(
                        emptySnapshot(Platform.WINDOWS_X86_64),
                        List.of(DiscoJavaDistribution.LIBERICA),
                        List.of(),
                        listener));

        onEventDispatchThread(() -> {
            assertEquals(JavaRuntimeAcquisitionPanel.Mode.DISCO, panel.mode());
            assertTrue(findComponent(
                    panel,
                    "javaManagementAcquireDiscoMode",
                    AbstractButton.class).isSelected());
            JComboBox<?> distributions = findComponent(
                    panel,
                    "javaManagementAcquireDiscoDistribution",
                    JComboBox.class);
            JComboBox<?> packages = findComponent(
                    panel,
                    "javaManagementAcquireDiscoPackageType",
                    JComboBox.class);
            JList<?> versions = findComponent(
                    panel,
                    "javaManagementAcquireDiscoVersionList",
                    JList.class);
            assertEquals(-1, distributions.getSelectedIndex());
            assertEquals(-1, packages.getSelectedIndex());
            assertEquals(ListSelectionModel.SINGLE_SELECTION, versions.getSelectionMode());
            assertTrue(versions.isSelectionEmpty());
            assertEquals(0, listener.discoVersionRequests);

            distributions.setSelectedItem(DiscoJavaDistribution.LIBERICA);
            assertEquals(-1, packages.getSelectedIndex());
            assertEquals(2, packages.getItemCount());
            assertEquals(JavaPackageType.JRE, packages.getItemAt(0));
            assertEquals(JavaPackageType.JDK, packages.getItemAt(1));
            assertFalse(containsComboItem(packages, JavaPackageType.JREFX));
            assertFalse(containsComboItem(packages, JavaPackageType.JDKFX));
            assertEquals(0, listener.discoVersionRequests);

            packages.setSelectedItem(JavaPackageType.JDK);
            assertEquals(1, listener.discoVersionRequests);
            assertEquals(DiscoJavaDistribution.LIBERICA, listener.discoDistribution);
            assertEquals(JavaPackageType.JDK, listener.discoPackageType);
            panel.close();
        });
    }

    /// Covers failed load retry, late-result rejection, successful cache reuse, explicit version, and install gating.
    @Test
    public void retriesDiscoFailuresRejectsLateResultsAndCachesOnlySuccessfulExplicitChoices() {
        RecordingListener listener = new RecordingListener();
        JavaRuntimeAcquisitionPanel panel = onEventDispatchThread(() ->
                new JavaRuntimeAcquisitionPanel(
                        snapshot(),
                        List.of(DiscoJavaDistribution.TEMURIN, DiscoJavaDistribution.ZULU),
                        List.of(),
                        listener));
        DiscoJavaRemoteVersion java21 = discoVersion("temurin-21", 21, "21.0.8+9", true, "jdk");

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
            distributions.setSelectedItem(DiscoJavaDistribution.TEMURIN);
            packages.setSelectedItem(JavaPackageType.JDK);
            long firstRevision = listener.discoVersionRevision;

            distributions.setSelectedItem(DiscoJavaDistribution.ZULU);
            panel.applyDiscoVersions(
                    firstRevision,
                    DiscoJavaDistribution.TEMURIN,
                    JavaPackageType.JDK,
                    List.of(java21));
            assertEquals(0, discoVersionChoices(panel).getChoiceModel().getSize());

            distributions.setSelectedItem(DiscoJavaDistribution.TEMURIN);
            assertEquals(JavaPackageType.JDK, packages.getSelectedItem());
            assertEquals(2, listener.discoVersionRequests);
            long restoredRevision = listener.discoVersionRevision;

            panel.applyDiscoVersionLoadFailure(
                    restoredRevision,
                    DiscoJavaDistribution.TEMURIN,
                    JavaPackageType.JDK);
            AbstractButton retry = findComponent(
                    panel,
                    "javaManagementAcquireDiscoRetry",
                    AbstractButton.class);
            assertTrue(retry.isVisible());
            retry.doClick();
            assertEquals(3, listener.discoVersionRequests);
            long successfulRevision = listener.discoVersionRevision;
            panel.applyDiscoVersions(
                    successfulRevision,
                    DiscoJavaDistribution.TEMURIN,
                    JavaPackageType.JDK,
                    List.of(java21));
            loadDiscoChoices(panel, 1);
            JList<?> versionList = findComponent(
                    panel,
                    "javaManagementAcquireDiscoVersionList",
                    JList.class);
            assertTrue(versionList.isSelectionEmpty());
            versionList.setSelectedIndex(0);
            assertSame(java21, panel.selectedDiscoVersion());
            assertSame(java21, listener.discoVersion);

            panel.applyDiscoInstallNameSuggestion(
                    listener.discoInstallRevision,
                    DiscoJavaDistribution.TEMURIN,
                    JavaPackageType.JDK,
                    java21,
                    "temurin-21-jdk",
                    JavaRuntimeInstallNameStatus.VALID);
            JTextField installName = findComponent(
                    panel,
                    "javaManagementAcquireDiscoInstallName",
                    JTextField.class);
            assertEquals("temurin-21-jdk", installName.getText());
            assertEquals(Boolean.TRUE, installName.getClientProperty("JTextField.showClearButton"));
            AbstractButton install = findComponent(
                    panel,
                    "javaManagementAcquireDiscoInstall",
                    AbstractButton.class);
            assertTrue(install.isEnabled());
            install.doClick();
            assertEquals("temurin-21-jdk", listener.discoInstalledName);

            packages.setSelectedItem(JavaPackageType.JRE);
            assertEquals(4, listener.discoVersionRequests);
            packages.setSelectedItem(JavaPackageType.JDK);
            assertEquals(4, listener.discoVersionRequests);
            assertTrue(listener.discoVersionCancellations > 0);
            assertEquals(1, discoVersionChoices(panel).getChoiceModel().getSize());
            panel.close();
        });
    }

    /// Routes exceptional-platform links through the parent boundary and keeps all Java page icons accessible.
    @Test
    public void routesPlatformLinksAndUsesAccessibleThemeAwareIcons() {
        RecordingListener listener = new RecordingListener();
        @Unmodifiable List<JavaRuntimePlatformLinks.Link> links =
                JavaRuntimePlatformLinks.forPlatform(Platform.LINUX_RISCV64);
        JavaRuntimeAcquisitionPanel panel = onEventDispatchThread(() ->
                new JavaRuntimeAcquisitionPanel(
                        emptySnapshot(Platform.LINUX_RISCV64),
                        List.of(),
                        links,
                        listener));

        onEventDispatchThread(() -> {
            assertEquals(JavaRuntimeAcquisitionPanel.Mode.DISCO, panel.mode());
            AbstractButton external = findComponent(
                    panel,
                    "javaManagementAcquireExternalLink0",
                    AbstractButton.class);
            AbstractButton chooseArchive = findComponent(
                    panel,
                    "javaManagementAcquireChooseArchive",
                    AbstractButton.class);
            AbstractButton download = findComponent(
                    panel,
                    "javaManagementAcquireDownload",
                    AbstractButton.class);
            assertTrue(external.getIcon() != null);
            assertTrue(chooseArchive.getIcon() != null);
            assertTrue(download.getIcon() != null);
            assertEquals(
                    "assets/swing/icons/open-in-new.svg",
                    ((FlatSVGIcon) external.getIcon()).getName());
            assertEquals(
                    "assets/swing/icons/folder-open.svg",
                    ((FlatSVGIcon) chooseArchive.getIcon()).getName());
            assertEquals(
                    "assets/swing/icons/nav-downloads.svg",
                    ((FlatSVGIcon) download.getIcon()).getName());
            assertTrue(((FlatSVGIcon) external.getIcon()).getColorFilter() != null);
            assertTrue(((FlatSVGIcon) chooseArchive.getIcon()).getColorFilter() != null);
            assertTrue(((FlatSVGIcon) download.getIcon()).getColorFilter() != null);
            assertFalse(Objects.requireNonNull(
                    external.getAccessibleContext().getAccessibleName(),
                    "external accessible name").isBlank());
            external.doClick();
            assertEquals(URI.create("https://www.zthread.cn/#product"), listener.externalUri);
            panel.close();
            listener.externalUri = null;
            external.doClick();
            assertNull(listener.externalUri);
        });

        assertEquals(
                URI.create("https://www.loongnix.cn/zh/api/java/downloads-jdk21/index.html"),
                JavaRuntimePlatformLinks.forPlatform(Platform.LINUX_LOONGARCH64_OW).get(0).uri());
    }

    /// Creates the two-row fixture with one installed and one downloadable Mojang runtime.
    ///
    /// @return immutable acquisition snapshot
    private static JavaRuntimeAcquisitionSnapshot snapshot() {
        return new JavaRuntimeAcquisitionSnapshot(
                Platform.WINDOWS_X86_64,
                List.of(
                        new MojangJavaRuntimeOption(GameJavaVersion.JAVA_17, true),
                        new MojangJavaRuntimeOption(GameJavaVersion.JAVA_21, false)));
    }

    /// Creates an empty built-in snapshot for one exact platform.
    ///
    /// @param platform target platform
    /// @return empty immutable acquisition snapshot
    private static JavaRuntimeAcquisitionSnapshot emptySnapshot(Platform platform) {
        return new JavaRuntimeAcquisitionSnapshot(
                Objects.requireNonNull(platform, "platform"),
                List.of());
    }

    /// Creates one deterministic Disco version fixture.
    ///
    /// @param id stable Disco identifier
    /// @param majorVersion Java major version
    /// @param javaVersion exact Java version text
    /// @param lts whether the fixture is LTS
    /// @param packageType Disco package type token
    /// @return remote version fixture
    private static DiscoJavaRemoteVersion discoVersion(
            String id,
            int majorVersion,
            String javaVersion,
            boolean lts,
            String packageType) {
        return new DiscoJavaRemoteVersion(
                id,
                "zip",
                "temurin",
                majorVersion,
                javaVersion,
                javaVersion,
                majorVersion,
                true,
                "ga",
                lts ? "lts" : "sts",
                "windows",
                "c_std_lib",
                "x64",
                "unknown",
                packageType,
                false,
                true,
                id + ".zip",
                new DiscoJavaRemoteVersion.Links(
                        "https://example.invalid/pkg/" + id,
                        "https://example.invalid/download/" + id),
                true,
                "yes",
                "https://example.invalid/tck",
                "yes",
                "https://example.invalid/aqavit",
                1024L);
    }

    /// Returns the third-party viewport choice list.
    ///
    /// @param panel acquisition panel
    /// @return typed-erased version choices
    private static ViewportChoiceList<?> discoVersionChoices(JavaRuntimeAcquisitionPanel panel) {
        return findComponent(
                panel,
                "javaManagementAcquireDiscoVersions",
                ViewportChoiceList.class);
    }

    /// Loads a bounded third-party result through the viewport-list contract.
    ///
    /// @param panel acquisition panel
    /// @param itemCount exact fixture row count
    private static void loadDiscoChoices(JavaRuntimeAcquisitionPanel panel, int itemCount) {
        ViewportChoiceList<?> choices = discoVersionChoices(panel);
        IndexRange range = IndexRange.ofLength(0, itemCount);
        choices.getChoiceModel().applyPlan(new ViewportLoadPlan(
                range,
                range,
                Set.of(),
                ScrollDirection.STATIONARY,
                0.0,
                0));
    }

    /// Returns whether one combo box contains an exact item.
    ///
    /// @param comboBox source combo box
    /// @param expected expected item
    /// @return whether the item is present
    private static boolean containsComboItem(JComboBox<?> comboBox, Object expected) {
        for (int index = 0; index < comboBox.getItemCount(); index++) {
            if (Objects.equals(expected, comboBox.getItemAt(index))) {
                return true;
            }
        }
        return false;
    }

    /// Creates one deterministic archive inspection fixture.
    ///
    /// @param archiveFile normalized archive path
    /// @param suggestedName suggested managed-runtime name
    /// @return immutable archive inspection
    private static LocalJavaArchiveInspection inspection(Path archiveFile, String suggestedName) {
        return new LocalJavaArchiveInspection(
                archiveFile,
                suggestedName,
                suggestedName,
                new JavaInfo(Platform.WINDOWS_X86_64, "21.0.8", "Eclipse Adoptium"),
                -1L,
                "");
    }

    /// Loads the bounded static Mojang rows through the viewport-list contract.
    ///
    /// @param panel acquisition panel
    /// @param itemCount exact fixture row count
    private static void loadMojangChoices(JavaRuntimeAcquisitionPanel panel, int itemCount) {
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

    /// Creates one Swing transfer context carrying a Java file-list payload.
    ///
    /// @param component target component
    /// @param files immutable transferred file list
    /// @return transfer support context
    private static TransferHandler.TransferSupport support(
            Component component,
            @Unmodifiable List<File> files) {
        return new TransferHandler.TransferSupport(
                Objects.requireNonNull(component, "component"),
                new FileListTransferable(files));
    }

    /// Finds one named component in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable UI-audit name
    /// @param type expected component type
    /// @param <T> component type
    /// @return matching component
    private static <T extends Component> T findComponent(
            Container root,
            String name,
            Class<T> type) {
        @Nullable T component = findOptionalComponent(root, name, type);
        if (component != null) {
            return component;
        }
        throw new IllegalArgumentException("Missing component: " + name);
    }

    /// Searches a nested Swing hierarchy without throwing when no component matches.
    ///
    /// @param root hierarchy root
    /// @param name stable UI-audit name
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

    /// Runs a value-producing operation synchronously on the Swing event dispatch thread.
    ///
    /// @param operation operation to run
    /// @param <T> non-null result type
    /// @return operation result
    private static <T extends Object> T onEventDispatchThread(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(operation.get()));
        return Objects.requireNonNull(result.get(), "EDT operation did not return a result");
    }

    /// Runs an operation synchronously on the Swing event dispatch thread.
    ///
    /// @param operation operation to run
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(operation);
    }

    /// Records pure UI callbacks without performing I/O or starting tasks.
    @NotNullByDefault
    private static final class RecordingListener implements JavaRuntimeAcquisitionPanel.Listener {
        /// Number of return requests.
        private int backCalls;

        /// Number of native archive chooser requests.
        private int chooserCalls;

        /// Most recently requested archive inspection path.
        private @Nullable Path inspectedArchive;

        /// Revision attached to the most recent archive inspection request.
        private long inspectionRevision;

        /// Most recently requested Mojang download version.
        private @Nullable GameJavaVersion downloadedVersion;

        /// Most recent inspection used for name validation.
        private @Nullable LocalJavaArchiveInspection validationInspection;

        /// Revision attached to the most recent name validation request.
        private long validationRevision;

        /// Most recent candidate requested for name validation.
        private @Nullable String validationCandidate;

        /// Most recent archive requested for installation.
        private @Nullable LocalJavaArchiveInspection installedInspection;

        /// Most recent validated name requested for installation.
        private @Nullable String installedName;

        /// Revision attached to the most recent third-party version request.
        private long discoVersionRevision;

        /// Number of explicit third-party version requests.
        private int discoVersionRequests;

        /// Number of third-party version cancellation requests.
        private int discoVersionCancellations;

        /// Distribution attached to the most recent third-party request.
        private @Nullable DiscoJavaDistribution discoDistribution;

        /// Package type attached to the most recent third-party request.
        private @Nullable JavaPackageType discoPackageType;

        /// Revision attached to the most recent third-party name request.
        private long discoInstallRevision;

        /// Version attached to the most recent third-party name request.
        private @Nullable DiscoJavaRemoteVersion discoVersion;

        /// Most recent edited third-party name candidate.
        private @Nullable String discoInstallCandidate;

        /// Most recent validated third-party installation name.
        private @Nullable String discoInstalledName;

        /// Most recent external Java download destination.
        private @Nullable URI externalUri;

        /// Records one return request.
        @Override
        public void backRequested() {
            backCalls++;
        }

        /// Records one native chooser request.
        @Override
        public void archiveChooserRequested() {
            chooserCalls++;
        }

        /// Records one archive inspection request.
        ///
        /// @param revision monotonic archive-selection identity
        /// @param archiveFile normalized absolute archive path
        @Override
        public void archiveInspectionRequested(long revision, Path archiveFile) {
            inspectionRevision = revision;
            inspectedArchive = Objects.requireNonNull(archiveFile, "archiveFile");
        }

        /// Records one explicit Mojang download request.
        ///
        /// @param version explicitly selected Mojang runtime version
        @Override
        public void mojangDownloadRequested(GameJavaVersion version) {
            downloadedVersion = Objects.requireNonNull(version, "version");
        }

        /// Records one parent-owned name validation request.
        ///
        /// @param revision monotonic archive-selection identity
        /// @param inspection current archive inspection
        /// @param candidate trimmed name candidate
        @Override
        public void installNameValidationRequested(
                long revision,
                LocalJavaArchiveInspection inspection,
                String candidate) {
            validationRevision = revision;
            validationInspection = Objects.requireNonNull(inspection, "inspection");
            validationCandidate = Objects.requireNonNull(candidate, "candidate");
        }

        /// Records one local archive installation request.
        ///
        /// @param inspection current archive inspection
        /// @param name validated managed-runtime name
        @Override
        public void archiveInstallRequested(
                LocalJavaArchiveInspection inspection,
                String name) {
            installedInspection = Objects.requireNonNull(inspection, "inspection");
            installedName = Objects.requireNonNull(name, "name");
        }

        /// Returns deterministic service-authoritative package choices including filtered JavaFX fixtures.
        ///
        /// @param distribution selected distribution
        /// @return immutable package choices
        @Override
        public @Unmodifiable List<JavaPackageType> discoPackageTypesRequested(
                DiscoJavaDistribution distribution) {
            Objects.requireNonNull(distribution, "distribution");
            return List.of(
                    JavaPackageType.JRE,
                    JavaPackageType.JDK,
                    JavaPackageType.JREFX,
                    JavaPackageType.JDKFX);
        }

        /// Records one explicit third-party version request.
        @Override
        public void discoVersionsRequested(
                long revision,
                DiscoJavaDistribution distribution,
                JavaPackageType packageType) {
            discoVersionRevision = revision;
            discoVersionRequests++;
            discoDistribution = Objects.requireNonNull(distribution, "distribution");
            discoPackageType = Objects.requireNonNull(packageType, "packageType");
        }

        /// Records cancellation of one replaced third-party version request.
        @Override
        public void discoVersionLoadCancellationRequested() {
            discoVersionCancellations++;
        }

        /// Records one third-party suggested-name request.
        @Override
        public void discoInstallNameSuggestionRequested(
                long revision,
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion version) {
            discoInstallRevision = revision;
            discoDistribution = Objects.requireNonNull(distribution, "distribution");
            discoPackageType = Objects.requireNonNull(packageType, "packageType");
            discoVersion = Objects.requireNonNull(version, "version");
        }

        /// Records one edited third-party managed-name validation request.
        @Override
        public void discoInstallNameValidationRequested(
                long revision,
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion version,
                String candidate) {
            discoInstallRevision = revision;
            discoDistribution = Objects.requireNonNull(distribution, "distribution");
            discoPackageType = Objects.requireNonNull(packageType, "packageType");
            discoVersion = Objects.requireNonNull(version, "version");
            discoInstallCandidate = Objects.requireNonNull(candidate, "candidate");
        }

        /// Records one validated third-party install request.
        @Override
        public void discoInstallRequested(
                DiscoJavaDistribution distribution,
                JavaPackageType packageType,
                DiscoJavaRemoteVersion version,
                String installName) {
            discoDistribution = Objects.requireNonNull(distribution, "distribution");
            discoPackageType = Objects.requireNonNull(packageType, "packageType");
            discoVersion = Objects.requireNonNull(version, "version");
            discoInstalledName = Objects.requireNonNull(installName, "installName");
        }

        /// Records one external Java download command.
        @Override
        public void externalJavaDownloadRequested(URI uri) {
            externalUri = Objects.requireNonNull(uri, "uri");
        }
    }

    /// Exposes one immutable Java file-list transfer payload.
    @NotNullByDefault
    private static final class FileListTransferable implements Transferable {
        /// Immutable files carried by this transfer.
        private final @Unmodifiable List<File> files;

        /// Creates one transfer payload.
        ///
        /// @param files transferred files
        private FileListTransferable(@Unmodifiable List<File> files) {
            this.files = List.copyOf(Objects.requireNonNull(files, "files"));
        }

        /// Returns the one supported Java file-list flavor.
        ///
        /// @return immutable flavor array
        @Override
        public DataFlavor @Unmodifiable [] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        /// Returns whether the requested flavor is the Java file-list flavor.
        ///
        /// @param flavor requested data flavor
        /// @return whether the flavor is supported
        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(Objects.requireNonNull(flavor, "flavor"));
        }

        /// Returns the immutable file-list payload for the supported flavor.
        ///
        /// @param flavor requested data flavor
        /// @return immutable transferred files
        /// @throws UnsupportedFlavorException when another flavor is requested
        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return files;
        }
    }
}
