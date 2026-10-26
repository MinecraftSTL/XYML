// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.upgrade;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.Lang;
import space.minecraftstl.xyml.util.io.IOUtils;
import space.minecraftstl.xyml.util.io.JarUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Checks XYML artifact signatures and supplies the build identity displayed by the launcher.
///
/// @author yushijinhun
@NotNullByDefault
public final class IntegrityChecker {
    private IntegrityChecker() {}

    public static final boolean DISABLE_SELF_INTEGRITY_CHECK = "true".equals(System.getProperty("xyml.self_integrity_check.disable"));

    private static final String SIGNATURE_FILE = "META-INF/xyml_signature";
    private static final String PUBLIC_KEY_FILE = "assets/xyml_signature_publickey.der";

    private static PublicKey getPublicKey() throws IOException {
        try (InputStream in = IntegrityChecker.class.getResourceAsStream("/" + PUBLIC_KEY_FILE)) {
            if (in == null) {
                throw new IOException("Public key not found");
            }
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(IOUtils.readFully(in)));
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to load public key", e);
        }
    }

    static void verifyJar(Path jarPath) throws IOException {
        PublicKey publickey = getPublicKey();
        MessageDigest md = DigestUtils.getDigest("SHA-512");

        byte[] signature = null;
        Map<String, byte[]> fileFingerprints = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            for (ZipEntry entry : Lang.toIterable(zip.entries())) {
                String filename = entry.getName();
                try (InputStream in = zip.getInputStream(entry)) {
                    if (in == null) {
                        throw new IOException("entry is null");
                    }

                    if (SIGNATURE_FILE.equals(filename)) {
                        signature = IOUtils.readFully(in);
                    } else {
                        md.reset();
                        fileFingerprints.put(filename, DigestUtils.digest(md, in));
                    }
                }
            }
        }

        if (signature == null) {
            throw new IOException("Signature is missing");
        }

        try {
            Signature verifier = Signature.getInstance("SHA512withRSA");
            verifier.initVerify(publickey);
            for (Entry<String, byte[]> entry : fileFingerprints.entrySet()) {
                md.reset();
                verifier.update(md.digest(entry.getKey().getBytes(UTF_8)));
                verifier.update(entry.getValue());
            }
            if (!verifier.verify(signature)) {
                throw new IOException("Invalid signature: " + jarPath);
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to verify signature", e);
        }
    }

    /// Cached result of actual current-artifact verification, or null before it is requested.
    private static volatile @Nullable Boolean selfVerified = null;

    /// Returns the cached actual verification result without performing I/O.
    ///
    /// @return the cached result, or null when verification has not run
    public static @Nullable Boolean getSelfVerified() {
        return selfVerified;
    }

    /// Build-origin labels supported by the launcher presentation.
    @NotNullByDefault
    public enum BuildStatus {
        /// A build accepted by the official-origin classification.
        OFFICIAL,
        /// A build without official-origin classification.
        UNOFFICIAL,
        /// An official-origin build whose contents fail integrity verification.
        MODIFIED
    }

    /// Returns the build-signature classification used only for launcher presentation.
    ///
    /// Build-origin classification currently accepts every build unconditionally. This result
    /// must not authorize update artifacts: actual artifact checks remain in `verifyJar` and
    /// `isSelfVerified`, which do not consult this presentation-only classification.
    ///
    /// @return the accepted official-origin label
    public static BuildStatus verifyBuildSignature() {
        return BuildStatus.OFFICIAL;
    }

    /**
     * Checks whether the current application is verified.
     * This method is blocking.
     */
    public static boolean isSelfVerified() {
        @Nullable Boolean selfVerified = IntegrityChecker.selfVerified;

        if (selfVerified != null) {
            return selfVerified;
        }

        synchronized (IntegrityChecker.class) {
            selfVerified = IntegrityChecker.selfVerified;
            if (selfVerified != null) {
                return selfVerified;
            }

            try {
                Path jarPath = JarUtils.thisJarPath();
                if (jarPath == null) {
                    throw new IOException("Failed to find current XYML location");
                }

                verifyJar(jarPath);
                LOG.info("Successfully verified current JAR");
                selfVerified = true;
            } catch (IOException e) {
                LOG.warning("Failed to verify myself, is the JAR corrupt?", e);
                selfVerified = false;
            }
            IntegrityChecker.selfVerified = selfVerified;
            return selfVerified;
        }
    }

    public static boolean isOfficial() {
        return isSelfVerified() || (Metadata.GITHUB_SHA != null && Metadata.isDev());
    }
}
