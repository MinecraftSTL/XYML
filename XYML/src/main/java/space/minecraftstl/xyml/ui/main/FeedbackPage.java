/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.ui.main;

import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.theme.Themes;
import space.minecraftstl.xyml.ui.FXUtils;
import space.minecraftstl.xyml.ui.WeakListenerHolder;
import space.minecraftstl.xyml.ui.construct.ComponentList;
import space.minecraftstl.xyml.ui.construct.LineButton;
import space.minecraftstl.xyml.ui.construct.SpinnerPane;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Displays official community and issue-reporting links.
@NotNullByDefault
public class FeedbackPage extends SpinnerPane {

    /// Retains the weak listener that updates the themed GitHub icon.
    private final WeakListenerHolder holder = new WeakListenerHolder();

    /// Creates the feedback page with the current community and issue-reporting destinations.
    public FeedbackPage() {
        VBox content = new VBox();
        content.getStyleClass().add("spinner-pane-content");
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        FXUtils.smoothScrolling(scrollPane);
        setContent(scrollPane);

        ComponentList groups = new ComponentList();
        {
            var users = LineButton.createExternalLinkButton(Metadata.GROUPS_URL);
            users.setLargeTitle(true);
            users.setLeading(FXUtils.newBuiltinImage("/assets/img/icon.png"));
            users.setTitle(i18n("contact.chat.qq_group"));
            users.setSubtitle(i18n("contact.chat.qq_group.statement"));

            groups.getContent().setAll(users);
        }

        ComponentList feedback = new ComponentList();
        {
            var github = LineButton.createExternalLinkButton("https://github.com/MinecraftSTL/XYML/issues");
            github.setLargeTitle(true);
            github.setTitle(i18n("contact.feedback.github"));
            github.setSubtitle(i18n("contact.feedback.github.statement"));

            holder.add(FXUtils.onWeakChangeAndOperate(Themes.darkModeProperty(), darkMode -> {
                github.setLeading(darkMode
                        ? FXUtils.newBuiltinImage("/assets/img/github-white.png")
                        : FXUtils.newBuiltinImage("/assets/img/github.png"));
            }));

            feedback.getContent().setAll(github);
        }

        content.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("contact.chat")),
                groups,
                ComponentList.createComponentListTitle(i18n("contact.feedback")),
                feedback
        );
    }
}
