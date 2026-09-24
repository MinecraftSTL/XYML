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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.util.Objects;

/// Builds the compact, unframed project-details band shared by remote add-on catalog rows.
@NotNullByDefault
final class RemoteAddonProjectDetailsLayout {

    /// Prevents construction of this stateless layout helper.
    private RemoteAddonProjectDetailsLayout() {
    }

    /// Configures the supplied details controls and returns their responsive container.
    ///
    /// @param summaryLabel selected-project summary label
    /// @param upstreamButton selected-project upstream command
    /// @param upstreamText localized upstream command text
    /// @param upstreamAction action that opens the selected upstream page
    /// @param prerequisitesLabel prerequisite-section label
    /// @param prerequisitesText localized prerequisite-section text
    /// @param prerequisiteControls wrapped prerequisite command container
    /// @return responsive project-details container
    static JPanel create(
            JLabel summaryLabel,
            JButton upstreamButton,
            String upstreamText,
            Runnable upstreamAction,
            JLabel prerequisitesLabel,
            String prerequisitesText,
            JPanel prerequisiteControls) {
        summaryLabel.setName("remoteAddonProjectSummary");
        summaryLabel.setMinimumSize(new Dimension(0, 0));
        summaryLabel.setToolTipText(null);

        upstreamButton.setName("remoteAddonUpstream");
        upstreamButton.setText(Objects.requireNonNull(upstreamText, "upstreamText"));
        upstreamButton.setMinimumSize(new Dimension(0, 0));
        upstreamButton.setVisible(false);
        upstreamButton.addActionListener(event -> Objects.requireNonNull(upstreamAction, "upstreamAction").run());

        prerequisitesLabel.setName("remoteAddonPrerequisites");
        prerequisitesLabel.setText(Objects.requireNonNull(prerequisitesText, "prerequisitesText"));
        prerequisitesLabel.setMinimumSize(new Dimension(0, 0));
        prerequisitesLabel.setVisible(false);

        prerequisiteControls.setName("remoteAddonDependencyControls");
        prerequisiteControls.setOpaque(false);
        prerequisiteControls.setMinimumSize(new Dimension(0, 0));
        prerequisiteControls.setVisible(false);

        JPanel details = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 2",
                "[grow,fill][grow,fill]",
                "[]6[]"));
        details.setName("remoteAddonProjectDetails");
        details.setOpaque(false);
        details.setMinimumSize(new Dimension(0, 0));
        details.add(summaryLabel, "growx, wmin 0");
        details.add(upstreamButton, "growx, wmin 0, h 32!");
        details.add(prerequisitesLabel, "growx, wmin 0");
        details.add(prerequisiteControls, "growx, wmin 0");
        return details;
    }
}
