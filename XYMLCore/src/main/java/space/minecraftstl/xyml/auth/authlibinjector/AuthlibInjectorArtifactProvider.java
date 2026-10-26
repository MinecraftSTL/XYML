// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.auth.authlibinjector;

import java.io.IOException;
import java.util.Optional;

public interface AuthlibInjectorArtifactProvider {

    AuthlibInjectorArtifactInfo getArtifactInfo() throws IOException;

    Optional<AuthlibInjectorArtifactInfo> getArtifactInfoImmediately();

}
