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

import org.jetbrains.annotations.NotNullByDefault;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Localizable text used by the instance overview and its desktop interactions.
///
/// Keeping the text in one immutable value object prevents operational controls from scattering resource
/// lookups through the panel and interaction implementation.
@NotNullByDefault
final class InstanceOverviewStrings {
    /// Shared English fallback used by the production overview.
    private static final InstanceOverviewStrings ENGLISH = new InstanceOverviewStrings(
            "Overview",
            "Instance name",
            "Instance folder",
            "Game folder",
            "Current instance icon",
            "Loading...",
            "Open instance folder",
            "Open game folder",
            "Refresh instance information",
            "Choose instance icon",
            "Remove custom instance icon",
            "Choose instance icon",
            "Choose custom image",
            "Image files",
            "Remove the custom icon for \"%s\"?",
            "Remove custom icon",
            "Instance operation failed");

    /// Visible overview tab title.
    private final String title;

    /// Label for the immutable instance identifier.
    private final String instanceNameLabel;

    /// Label for the version-root directory.
    private final String instanceRootLabel;

    /// Label for the effective game running directory.
    private final String gameDirectoryLabel;

    /// Assistive name for the fixed-size active icon preview.
    private final String iconPreviewAccessibleName;

    /// Initial value shown while directory metadata is loading.
    private final String loadingValue;

    /// Assistive text for opening the version-root directory.
    private final String openInstanceDirectoryTooltip;

    /// Assistive text for opening the effective game directory.
    private final String openGameDirectoryTooltip;

    /// Assistive text for refreshing repository-backed metadata.
    private final String refreshTooltip;

    /// Assistive text for choosing a custom icon image.
    private final String chooseIconTooltip;

    /// Assistive text for deleting the custom icon image.
    private final String deleteIconTooltip;

    /// Title for the custom-icon file chooser.
    private final String iconChooserTitle;

    /// Assistive text for the dialog's custom-image entry.
    private final String customIconTooltip;

    /// File-filter description for supported icon images.
    private final String imageFileDescription;

    /// Confirmation format for custom-icon deletion.
    private final String deleteIconConfirmationFormat;

    /// Confirmation dialog title for custom-icon deletion.
    private final String deleteIconTitle;

    /// Title shown when an instance operation fails.
    private final String operationFailedTitle;

    /// Creates one immutable text bundle.
    ///
    /// @param title visible overview tab title
    /// @param instanceNameLabel instance identifier label
    /// @param instanceRootLabel instance-root directory label
    /// @param gameDirectoryLabel effective game directory label
    /// @param iconPreviewAccessibleName active icon preview assistive name
    /// @param loadingValue initial loading value
    /// @param openInstanceDirectoryTooltip instance-directory tooltip
    /// @param openGameDirectoryTooltip game-directory tooltip
    /// @param refreshTooltip refresh tooltip
    /// @param chooseIconTooltip custom-icon chooser tooltip
    /// @param deleteIconTooltip custom-icon deletion tooltip
    /// @param iconChooserTitle chooser title
    /// @param customIconTooltip custom-image entry tooltip
    /// @param imageFileDescription image-file filter description
    /// @param deleteIconConfirmationFormat deletion confirmation format
    /// @param deleteIconTitle deletion confirmation title
    /// @param operationFailedTitle operation failure title
    private InstanceOverviewStrings(
            String title,
            String instanceNameLabel,
            String instanceRootLabel,
            String gameDirectoryLabel,
            String iconPreviewAccessibleName,
            String loadingValue,
            String openInstanceDirectoryTooltip,
            String openGameDirectoryTooltip,
            String refreshTooltip,
            String chooseIconTooltip,
            String deleteIconTooltip,
            String iconChooserTitle,
            String customIconTooltip,
            String imageFileDescription,
            String deleteIconConfirmationFormat,
            String deleteIconTitle,
            String operationFailedTitle) {
        this.title = requireNonBlank(title, "title");
        this.instanceNameLabel = requireNonBlank(instanceNameLabel, "instanceNameLabel");
        this.instanceRootLabel = requireNonBlank(instanceRootLabel, "instanceRootLabel");
        this.gameDirectoryLabel = requireNonBlank(gameDirectoryLabel, "gameDirectoryLabel");
        this.iconPreviewAccessibleName = requireNonBlank(
                iconPreviewAccessibleName,
                "iconPreviewAccessibleName");
        this.loadingValue = requireNonBlank(loadingValue, "loadingValue");
        this.openInstanceDirectoryTooltip = requireNonBlank(
                openInstanceDirectoryTooltip,
                "openInstanceDirectoryTooltip");
        this.openGameDirectoryTooltip = requireNonBlank(openGameDirectoryTooltip, "openGameDirectoryTooltip");
        this.refreshTooltip = requireNonBlank(refreshTooltip, "refreshTooltip");
        this.chooseIconTooltip = requireNonBlank(chooseIconTooltip, "chooseIconTooltip");
        this.deleteIconTooltip = requireNonBlank(deleteIconTooltip, "deleteIconTooltip");
        this.iconChooserTitle = requireNonBlank(iconChooserTitle, "iconChooserTitle");
        this.customIconTooltip = requireNonBlank(customIconTooltip, "customIconTooltip");
        this.imageFileDescription = requireNonBlank(imageFileDescription, "imageFileDescription");
        this.deleteIconConfirmationFormat = requireNonBlank(
                deleteIconConfirmationFormat,
                "deleteIconConfirmationFormat");
        this.deleteIconTitle = requireNonBlank(deleteIconTitle, "deleteIconTitle");
        this.operationFailedTitle = requireNonBlank(operationFailedTitle, "operationFailedTitle");
    }

    /// Returns production text resolved from the current launcher locale.
    ///
    /// @return current-locale overview text
    static InstanceOverviewStrings localized() {
        return new InstanceOverviewStrings(
                i18n("swing.instance_overview.title"),
                i18n("instance.name"),
                i18n("swing.instance_overview.instance_folder"),
                i18n("folder.game"),
                i18n("swing.instance_overview.icon_preview"),
                i18n("swing.instance_overview.loading"),
                i18n("swing.instance_overview.open_instance_folder"),
                i18n("swing.instance_overview.open_game_folder"),
                i18n("swing.instance_overview.refresh"),
                i18n("swing.instance_overview.choose_icon"),
                i18n("swing.instance_overview.remove_icon"),
                i18n("swing.instance_overview.choose_icon"),
                i18n("swing.instance_overview.custom_icon"),
                i18n("swing.instance_overview.image_files"),
                i18n("swing.instance_overview.remove_icon_confirm"),
                i18n("swing.instance_overview.remove_icon"),
                i18n("swing.instance_overview.operation_failed"));
    }

    /// Returns stable English text for deterministic component tests.
    ///
    /// @return shared immutable English text
    static InstanceOverviewStrings english() {
        return ENGLISH;
    }

    /// Returns the visible overview tab title.
    ///
    /// @return non-blank tab title
    String title() {
        return title;
    }

    /// Returns the instance identifier label.
    ///
    /// @return non-blank label
    String instanceNameLabel() {
        return instanceNameLabel;
    }

    /// Returns the version-root directory label.
    ///
    /// @return non-blank label
    String instanceRootLabel() {
        return instanceRootLabel;
    }

    /// Returns the effective game-directory label.
    ///
    /// @return non-blank label
    String gameDirectoryLabel() {
        return gameDirectoryLabel;
    }

    /// Returns the active icon preview's assistive name.
    ///
    /// @return non-blank accessible name
    String iconPreviewAccessibleName() {
        return iconPreviewAccessibleName;
    }

    /// Returns the loading placeholder text.
    ///
    /// @return non-blank loading text
    String loadingValue() {
        return loadingValue;
    }

    /// Returns the instance-directory tooltip.
    ///
    /// @return non-blank tooltip
    String openInstanceDirectoryTooltip() {
        return openInstanceDirectoryTooltip;
    }

    /// Returns the game-directory tooltip.
    ///
    /// @return non-blank tooltip
    String openGameDirectoryTooltip() {
        return openGameDirectoryTooltip;
    }

    /// Returns the refresh tooltip.
    ///
    /// @return non-blank tooltip
    String refreshTooltip() {
        return refreshTooltip;
    }

    /// Returns the custom-icon chooser tooltip.
    ///
    /// @return non-blank tooltip
    String chooseIconTooltip() {
        return chooseIconTooltip;
    }

    /// Returns the custom-icon deletion tooltip.
    ///
    /// @return non-blank tooltip
    String deleteIconTooltip() {
        return deleteIconTooltip;
    }

    /// Returns the icon file chooser title.
    ///
    /// @return non-blank dialog title
    String iconChooserTitle() {
        return iconChooserTitle;
    }

    /// Returns the custom-image tile tooltip.
    ///
    /// @return non-blank tooltip
    String customIconTooltip() {
        return customIconTooltip;
    }

    /// Returns the supported-image file filter description.
    ///
    /// @return non-blank description
    String imageFileDescription() {
        return imageFileDescription;
    }

    /// Returns the custom-icon deletion confirmation format.
    ///
    /// @return non-blank format containing one instance-name placeholder
    String deleteIconConfirmationFormat() {
        return deleteIconConfirmationFormat;
    }

    /// Returns the custom-icon deletion dialog title.
    ///
    /// @return non-blank dialog title
    String deleteIconTitle() {
        return deleteIconTitle;
    }

    /// Returns the instance-operation failure dialog title.
    ///
    /// @return non-blank failure title
    String operationFailedTitle() {
        return operationFailedTitle;
    }

    /// Rejects blank constructor text while preserving the supplied value.
    ///
    /// @param value candidate text
    /// @param name constructor parameter name
    /// @return validated text
    private static String requireNonBlank(String value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
