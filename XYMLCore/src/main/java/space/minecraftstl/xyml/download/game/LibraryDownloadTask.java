/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.download.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.glavo.url.WebURL;
import space.minecraftstl.xyml.download.AbstractDependencyManager;
import space.minecraftstl.xyml.download.DefaultCacheRepository;
import space.minecraftstl.xyml.game.Library;
import space.minecraftstl.xyml.task.DownloadException;
import space.minecraftstl.xyml.task.FileDownloadTask.IntegrityCheck;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Downloads one library to its exact target while using the cache repository's short internal coordination.
///
/// The target-file declaration protects both cache-hit copies and remote download publication. Cache-index and
/// content-addressed cache writes are coordinated inside the repository for only the publication operation, so the
/// network lifetime of unrelated library targets remains concurrent.
@NotNullByDefault
public class LibraryDownloadTask extends Task<Void> {
    private @Nullable FileDownloadTask task;
    protected final Path jar;
    protected final DefaultCacheRepository cacheRepository;
    protected final AbstractDependencyManager dependencyManager;
    protected final Library library;
    protected final String url;
    private final Library originalLibrary;
    /// Cache directory captured when the task's resource declaration was created.
    private final Path cacheDirectorySnapshot;
    private boolean cached = false;

    /// Creates a download task for one immutable library descriptor and target path.
    ///
    /// @param dependencyManager repository and candidate-source provider
    /// @param file exact library target
    /// @param library library descriptor to resolve
    public LibraryDownloadTask(AbstractDependencyManager dependencyManager, Path file, Library library) {
        this.dependencyManager = dependencyManager;
        this.originalLibrary = library;

        setSignificance(TaskSignificance.MODERATE);

        if (library.is("net.minecraftforge", "forge"))
            library = library.setClassifier("universal");

        this.library = library;
        this.cacheRepository = dependencyManager.getCacheRepository();
        cacheDirectorySnapshot = Objects.requireNonNull(
                cacheRepository.getCacheDirectory(),
                "cache directory").toAbsolutePath().normalize();

        url = library.getDownload().getUrl();
        jar = file;
        setResources(TaskResource.downloadTarget(file));
    }

    @Override
    public Collection<Task<?>> getDependents() {
        @Nullable FileDownloadTask downloadTask = task;
        if (cached || downloadTask == null) return Collections.emptyList();
        else return Collections.singleton(downloadTask);
    }

    @Override
    public boolean isRelyingOnDependents() {
        return false;
    }

    @Override
    public void execute() throws Exception {
        if (cached) return;

        if (!isDependentsSucceeded()) {
            // Since FileDownloadTask wraps the actual exception with DownloadException.
            // We should extract it letting the error message clearer.
            Exception t = Objects.requireNonNull(task, "download task").getException();
            if (t instanceof DownloadException)
                throw new LibraryDownloadException(library, t.getCause());
            else if (t instanceof CancellationException)
                throw new CancellationException();
            else
                throw new LibraryDownloadException(library, t);
        }
    }

    @Override
    public boolean doPreExecute() {
        return true;
    }

    /// Validates the cache repository identity before reading or writing shared cache state.
    ///
    /// @throws IllegalStateException when the cache directory changed after resource declaration
    @Override
    public void preExecute() {
        Path currentCacheDirectory = Objects.requireNonNull(
                cacheRepository.getCacheDirectory(),
                "cache directory").toAbsolutePath().normalize();
        if (!cacheDirectorySnapshot.equals(currentCacheDirectory)) {
            throw new IllegalStateException("Cache directory changed after task resource declaration");
        }

        Optional<Path> libPath = cacheRepository.getLibrary(originalLibrary);
        if (libPath.isPresent()) {
            try {
                FileUtils.copyFile(libPath.get(), jar);
                cached = true;
                return;
            } catch (IOException e) {
                LOG.warning("Failed to copy file from cache", e);
                // We cannot copy cached file to current location
                // so we try to download a new one.
            }
        }


        @Unmodifiable List<WebURL> urls = dependencyManager.getDownloadProvider().injectURLWithCandidates(url);
        task = new FileDownloadTask(urls, jar,
                library.getDownload().getSha1() != null ? new IntegrityCheck("SHA-1", library.getDownload().getSha1()) : null);
        task.setCacheRepository(cacheRepository);
        task.setCaching(true);
        task.addIntegrityCheckHandler(FileDownloadTask.ZIP_INTEGRITY_CHECK_HANDLER);
    }

    @Override
    public boolean doPostExecute() {
        return true;
    }

    @Override
    public void postExecute() throws Exception {
        if (!cached) {
            try {
                cacheRepository.cacheLibrary(library, jar, false);
            } catch (IOException e) {
                LOG.warning("Failed to cache downloaded library " + library, e);
            }
        }
    }

    public static boolean checksumValid(Path libPath, List<String> checksums) {
        try {
            if (checksums == null || checksums.isEmpty()) {
                return true;
            }
            byte[] fileData = Files.readAllBytes(libPath);
            boolean valid = checksums.contains(DigestUtils.digestToString("SHA-1", fileData));
            if (!valid && FileUtils.getName(libPath).endsWith(".jar")) {
                valid = validateJar(fileData, checksums);
            }
            return valid;
        } catch (IOException e) {
            LOG.warning("Failed to validate " + libPath, e);
        }
        return false;
    }

    private static boolean validateJar(byte[] data, List<String> checksums) throws IOException {
        HashMap<String, String> files = new HashMap<>();
        String[] hashes = null;
        JarInputStream jar = new JarInputStream(new ByteArrayInputStream(data));
        JarEntry entry = jar.getNextJarEntry();
        while (entry != null) {
            byte[] eData = jar.readAllBytes();
            if (entry.getName().equals("checksums.sha1")) {
                hashes = new String(eData, StandardCharsets.UTF_8).split("\n");
            }
            if (!entry.isDirectory()) {
                files.put(entry.getName(), DigestUtils.digestToString("SHA-1", eData));
            }
            entry = jar.getNextJarEntry();
        }
        jar.close();
        if (hashes != null) {
            boolean failed = !checksums.contains(files.get("checksums.sha1"));
            if (!failed) {
                for (String hash : hashes) {
                    if (!hash.trim().isEmpty() && hash.contains(" ")) {
                        String[] e = hash.split(" ");
                        String validChecksum = e[0];
                        String target = hash.substring(validChecksum.length() + 1);
                        String checksum = files.get(target);
                        if ((!files.containsKey(target)) || (checksum == null)) {
                            LOG.warning("    " + target + " : missing");
                            failed = true;
                            break;
                        } else if (!checksum.equals(validChecksum)) {
                            LOG.warning("    " + target + " : failed (" + checksum + ", " + validChecksum + ")");
                            failed = true;
                            break;
                        }
                    }
                }
            }
            return !failed;
        }
        return false;
    }
}
