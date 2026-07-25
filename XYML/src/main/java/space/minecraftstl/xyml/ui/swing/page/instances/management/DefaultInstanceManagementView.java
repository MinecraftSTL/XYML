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
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.page.mods.DefaultModCatalogModel;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogActionStrings;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogInteractions;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogPanel;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogStrings;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.DefaultResourcePackCatalogModel;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogActionStrings;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogInteractions;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogPanel;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogStrings;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserInteractions;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserStrings;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.Font;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Hosts clearly separated Mod, resource-pack, and schematic tools for one managed game instance.
///
/// Both tabs are constructed on the EDT, while their filesystem work remains lazy and uses the supplied
/// executor. The view owns all child lifecycles and returns to the instance list through one shared toolbar.
@NotNullByDefault
public final class DefaultInstanceManagementView extends JPanel implements InstanceManagementView {
    /// Stable repository instance identifier represented by this view.
    private final String instanceId;

    /// Installed-Mod catalog owned by the Mods tab.
    private final ModCatalogPanel mods;

    /// Resource-pack catalog owned by the resource-pack tab.
    private final ResourcePackCatalogPanel resourcePacks;

    /// Schematic browser host owned by the schematic tab.
    private final SchematicInstanceManagementView schematics;

    /// Shared return command disabled after close begins.
    private final JButton returnButton = new JButton();

    /// Stable tab container retaining each tool's independent lazy state.
    private final JTabbedPane tabs = new JTabbedPane();

    /// Prevents repeated child and component cleanup.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates the production instance-management tabs on the Swing event dispatch thread.
    ///
    /// @param repository repository containing the managed instance
    /// @param schematicDirectoryResolver resolver for the managed instance's schematic root
    /// @param instanceId stable non-blank repository instance identifier
    /// @param executor caller-owned executor for all filesystem and metadata work
    /// @param managementStrings localized outer management text
    /// @param schematicStrings localized schematic-browser text
    /// @param schematicInteractions schematic dialog and desktop interactions
    /// @param modStrings localized installed-Mod content text
    /// @param modStatusStrings localized installed-Mod lifecycle text
    /// @param modActionStrings localized installed-Mod action text
    /// @param modInteractions installed-Mod dialog and desktop interactions
    /// @param resourcePackStrings localized resource-pack content text
    /// @param resourcePackStatusStrings localized resource-pack lifecycle text
    /// @param resourcePackActionStrings localized resource-pack action text
    /// @param resourcePackInteractions resource-pack dialog and desktop interactions
    /// @param returnCommand coordinator command returning to the instance list
    public DefaultInstanceManagementView(
            GameRepository repository,
            SchematicDirectoryResolver schematicDirectoryResolver,
            String instanceId,
            Executor executor,
            SchematicInstanceManagementStrings managementStrings,
            SchematicBrowserStrings schematicStrings,
            SchematicBrowserInteractions schematicInteractions,
            ModCatalogStrings modStrings,
            ModCatalogStatusStrings modStatusStrings,
            ModCatalogActionStrings modActionStrings,
            ModCatalogInteractions modInteractions,
            ResourcePackCatalogStrings resourcePackStrings,
            ResourcePackCatalogStatusStrings resourcePackStatusStrings,
            ResourcePackCatalogActionStrings resourcePackActionStrings,
            ResourcePackCatalogInteractions resourcePackInteractions,
            Runnable returnCommand) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]12[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(schematicDirectoryResolver, "schematicDirectoryResolver");
        this.instanceId = requireNonBlank(instanceId, "instanceId");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(managementStrings, "managementStrings");
        Objects.requireNonNull(schematicStrings, "schematicStrings");
        Objects.requireNonNull(schematicInteractions, "schematicInteractions");
        Objects.requireNonNull(modStrings, "modStrings");
        Objects.requireNonNull(modStatusStrings, "modStatusStrings");
        Objects.requireNonNull(modActionStrings, "modActionStrings");
        Objects.requireNonNull(modInteractions, "modInteractions");
        Objects.requireNonNull(resourcePackStrings, "resourcePackStrings");
        Objects.requireNonNull(resourcePackStatusStrings, "resourcePackStatusStrings");
        Objects.requireNonNull(resourcePackActionStrings, "resourcePackActionStrings");
        Objects.requireNonNull(resourcePackInteractions, "resourcePackInteractions");
        Objects.requireNonNull(returnCommand, "returnCommand");

        @Nullable ModCatalogPanel createdMods = null;
        @Nullable ResourcePackCatalogPanel createdResourcePacks = null;
        @Nullable SchematicInstanceManagementView createdSchematics = null;
        try {
            createdMods = new ModCatalogPanel(
                    new DefaultModCatalogModel(
                            repository,
                            this.instanceId,
                            executor,
                            modStatusStrings),
                    modStrings,
                    modActionStrings,
                    modInteractions);
            createdResourcePacks = new ResourcePackCatalogPanel(
                    new DefaultResourcePackCatalogModel(
                            repository,
                            this.instanceId,
                            executor,
                            resourcePackStatusStrings),
                    resourcePackStrings,
                    resourcePackActionStrings,
                    resourcePackInteractions,
                    repository.getResourcePackDirectory(this.instanceId));
            createdSchematics = new SchematicInstanceManagementView(
                    this.instanceId,
                    schematicDirectoryResolver,
                    executor,
                    managementStrings,
                    schematicStrings,
                    schematicInteractions,
                    () -> { },
                    false);
            mods = createdMods;
            resourcePacks = createdResourcePacks;
            schematics = createdSchematics;
            configureComponents(
                    managementStrings,
                    modStrings,
                    resourcePackStrings,
                    schematicStrings,
                    returnCommand);
        } catch (RuntimeException | Error constructionFailure) {
            @Nullable Throwable cleanupFailure = null;
            if (createdSchematics != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdSchematics::close);
            }
            if (createdResourcePacks != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdResourcePacks::close);
            }
            if (createdMods != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdMods::close);
            }
            if (cleanupFailure != null && cleanupFailure != constructionFailure) {
                constructionFailure.addSuppressed(cleanupFailure);
            }
            throw constructionFailure;
        }
    }

    /// Returns the stable repository identifier represented by this view.
    ///
    /// @return stable instance identifier
    @Override
    public String instanceId() {
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

    /// Closes all tabs and releases their Swing component trees exactly once.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        AtomicReference<@Nullable Throwable> cleanupFailure = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                @Nullable Throwable failure = null;
                failure = attemptCleanup(failure, schematics::close);
                failure = attemptCleanup(failure, resourcePacks::close);
                failure = attemptCleanup(failure, mods::close);
                returnButton.setEnabled(false);
                tabs.removeAll();
                removeAll();
                cleanupFailure.set(failure);
            });
        } catch (RuntimeException | Error componentFailure) {
            cleanupFailure.set(combineFailure(cleanupFailure.get(), componentFailure));
        }
        rethrowFailure(cleanupFailure.get());
    }

    /// Builds the top-level return toolbar and named tool tabs.
    ///
    /// @param managementStrings localized outer management text
    /// @param modStrings localized installed-Mod content text
    /// @param resourcePackStrings localized resource-pack content text
    /// @param schematicStrings localized schematic-browser text
    /// @param returnCommand coordinator command returning to the instance list
    private void configureComponents(
            SchematicInstanceManagementStrings managementStrings,
            ModCatalogStrings modStrings,
            ResourcePackCatalogStrings resourcePackStrings,
            SchematicBrowserStrings schematicStrings,
            Runnable returnCommand) {
        JPanel toolbar = new JPanel(new MigLayout("insets 0, fillx", "[][grow,fill]", "[40!]"));
        toolbar.setOpaque(false);
        returnButton.setName("instanceManagementReturn");
        returnButton.setText(null);
        returnButton.setToolTipText(managementStrings.returnTooltip());
        returnButton.setIcon(new FlatSVGIcon("assets/swing/icons/arrow-back.svg", 18, 18));
        returnButton.getAccessibleContext().setAccessibleName(managementStrings.returnTooltip());
        returnButton.addActionListener(event -> {
            if (!closed.get()) {
                returnCommand.run();
            }
        });
        toolbar.add(returnButton, "h 40!");

        JLabel title = new JLabel(managementStrings.returnAction());
        title.setName("instanceManagementTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22.0F));
        toolbar.add(title, "growx");
        add(toolbar, "growx");

        tabs.setName("instanceManagementTabs");
        tabs.addTab(modStrings.title(), mods);
        tabs.addTab(resourcePackStrings.pageTitle(), resourcePacks);
        tabs.addTab(schematicStrings.pageTitle(), schematics);
        tabs.getAccessibleContext().setAccessibleName(managementStrings.returnAction());
        add(tabs, "grow");
    }

    /// Attempts one cleanup action and retains the first failure identity.
    ///
    /// @param previous first cleanup failure, or null
    /// @param cleanup cleanup action
    /// @return first failure with later failures suppressed, or null
    private static @Nullable Throwable attemptCleanup(
            @Nullable Throwable previous,
            Runnable cleanup) {
        try {
            cleanup.run();
            return previous;
        } catch (RuntimeException | Error failure) {
            return combineFailure(previous, failure);
        }
    }

    /// Appends one cleanup failure without replacing an earlier failure.
    ///
    /// @param previous first cleanup failure, or null
    /// @param current later cleanup failure
    /// @return first failure with later failures suppressed
    private static Throwable combineFailure(@Nullable Throwable previous, Throwable current) {
        Objects.requireNonNull(current, "current");
        if (previous == null) {
            return current;
        }
        if (previous != current) {
            previous.addSuppressed(current);
        }
        return previous;
    }

    /// Rethrows an accumulated unchecked cleanup failure.
    ///
    /// @param failure cleanup failure, or null
    private static void rethrowFailure(@Nullable Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected checked instance-management failure", failure);
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
