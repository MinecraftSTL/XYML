// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.gradle.ci;

/// @author Glavo
public final class JenkinsUtils {

    public static final boolean IS_ON_CI = "1".equals(System.getenv("XYML_CI"));

    private JenkinsUtils() {
    }
}
