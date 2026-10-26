// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
