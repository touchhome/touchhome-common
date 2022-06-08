package org.touchhome.common.util;

import com.pivovarit.function.ThrowingFunction;
import com.pivovarit.function.ThrowingPredicate;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.compress.archivers.jar.JarArchiveInputStream;
import org.apache.commons.compress.archivers.jar.JarArchiveOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.touchhome.common.model.ProgressBar;
import org.touchhome.common.util.archive.ApacheCompress;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.Deflater;

@Log4j2
public final class ArchiveUtil {

    @SneakyThrows
    public static List<Path> unzip(@NotNull Path file, @NotNull Path destination, @Nullable String password,
                                   @Nullable ProgressBar progressBar, @NotNull UnzipFileIssueHandler handler) {
        if (progressBar != null) {
            progressBar.progress(0, "Unzip files. Calculate size...");
        }
        char[] pwd = Optional.ofNullable(StringUtils.trimToNull(password)).map(String::toCharArray).orElse(null);
        String fileName = file.getFileName().toString();
        String ext = FilenameUtils.getExtension(fileName);
        destination = destination.resolve(FilenameUtils.removeExtension(fileName));
        Files.createDirectories(destination);
        ZipFormat zipFormat = ZipFormat.getHandler(ext);
        double fileSize = progressBar == null ? 1D : zipFormat.zipFileSizeHandler.apply(file);
        List<Path> paths = zipFormat.getUnzipHandler().unzip(file, destination, pwd, progressBar, handler, fileSize);

        if (progressBar != null) {
            progressBar.progress(99, "Unzip files done.");
        }
        return paths;
    }

    public static void unzip(@NotNull Path file, @NotNull Path destination, @NotNull UnzipFileIssueHandler handler) {
        unzip(file, destination, null, null, handler);
    }

    public static boolean isArchive(Path archive) {
        try {
            String ext = FilenameUtils.getExtension(archive.getFileName().toString());
            ZipFormat.getHandler(ext);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @SneakyThrows
    public static List<File> getArchiveEntries(Path archive, String password) {
        String ext = FilenameUtils.getExtension(archive.getFileName().toString());
        return ZipFormat.getHandler(ext).archiveEntriesHandler.getChildren(archive,
                password == null ? null : password.toCharArray());
    }

    public static boolean isValidArchive(Path archive) {
        try {
            if (!Files.isRegularFile(archive) || !Files.isReadable(archive)) {
                return false;
            }
            String ext = FilenameUtils.getExtension(archive.getFileName().toString());
            ZipFormat zipFormat = ZipFormat.getHandler(ext);
            return zipFormat.validateHandler.test(archive);
        } catch (Exception ex) {
            return false;
        }
    }

    public enum UnzipFileIssueHandler {
        skip, replace, replaceNotMatch, error;
    }

    @Getter
    @RequiredArgsConstructor
    public enum ZipFormat {
        tar("tar", new String[]{"tar", "tar.gz"}, (sources, destination, level, password, progressBar) -> {
            ApacheCompress.archiveTar(sources, destination, level, password, progressBar);
        }, (file, destination, password, progressBar, handler, fileSize) -> {
            return ApacheCompress.unzipCompress(getTarArchiveInputStream(file), destination, handler, fileSize, progressBar);
        }, path -> {
            return 0D;
        }, path -> {
            return true;
        }, (file, password) -> {
            return ApacheCompress.getArchiveEntries(getTarArchiveInputStream(file));
        }, (file, entryName, password) -> {
            return ApacheCompress.downloadEntry(getTarArchiveInputStream(file), entryName);
        }), //
        jar("jar", new String[]{"jar", "war"}, (sources, destination, level, password, progressBar) -> {
            JarArchiveOutputStream out = new JarArchiveOutputStream(new BufferedOutputStream(Files.newOutputStream(destination)));
            out.setLevel("low".equals(level) ? Deflater.BEST_SPEED :
                    "high".equals(level) ? Deflater.BEST_COMPRESSION : Deflater.DEFAULT_COMPRESSION);
            ApacheCompress.archive(sources, out);
        }, (file, destination, password, progressBar, handler, fileSize) -> {
            return ApacheCompress.unzipCompress(new JarArchiveInputStream(Files.newInputStream(file)), destination, handler,
                    fileSize, progressBar);
        }, path -> {
            return 0D;
        }, path -> {
            new ZipFile(path.toFile()).close();
            return true;
        }, (file, password) -> {
            return ApacheCompress.getArchiveEntries(new JarArchiveInputStream(Files.newInputStream(file)));
        }, (file, entryName, password) -> {
            return ApacheCompress.downloadEntry(new JarArchiveInputStream(Files.newInputStream(file)), entryName);
        }), //
        zip("zip", new String[]{"zip"}, (sources, destination, level, password, progressBar) -> {
            ZipArchiveOutputStream out = new ZipArchiveOutputStream(new BufferedOutputStream(Files.newOutputStream(destination)));
            out.setLevel("low".equals(level) ? Deflater.BEST_SPEED :
                    "high".equals(level) ? Deflater.BEST_COMPRESSION : Deflater.DEFAULT_COMPRESSION);
            ApacheCompress.archive(sources, out);
        }, (file, destination, password, progressBar, handler, fileSize) -> {
            return ApacheCompress.unzipCompress(new ZipArchiveInputStream(Files.newInputStream(file)), destination, handler,
                    fileSize, progressBar);
        }, path -> {
            return 0D;
        }, path -> {
            new ZipFile(path.toFile()).close();
            return true;
        }, (file, password) -> {
            return ApacheCompress.getArchiveEntries(new ZipArchiveInputStream(Files.newInputStream(file)));
        }, (file, entryName, password) -> {
            return ApacheCompress.downloadEntry(new ZipArchiveInputStream(Files.newInputStream(file)), entryName);
        }), //
        sevenZ("7z", new String[]{"7z"}, (sources, destination, level, password, progressBar) -> {
            ApacheCompress.archiveSeven7(sources, destination);
        }, (file, destination, password, progressBar, handler, fileSize) -> {
            return ApacheCompress.unzipSeven7Archive(file, destination, password, progressBar, handler, fileSize);
        }, path -> {
            SevenZFile sevenZFile = new SevenZFile(path.toFile(), new char[0]);
            long fullSize = 0;
            for (SevenZArchiveEntry sevenZArchiveEntry : sevenZFile.getEntries()) {
                fullSize += sevenZArchiveEntry.getSize();
            }
            sevenZFile.close();
            return Double.valueOf(fullSize);
        }, path -> {
            new SevenZFile(path.toFile());
            return true;
        }, (file, password) -> {
            throw new IllegalStateException("Not implemented");
        }, (file, entryName, password) -> {
            return null;
        });

        private final String name;
        private final String[] extensions;
        private final ZipArchiveHandler zipHandler;
        private final UnZipArchiveHandler unzipHandler;
        private final ThrowingFunction<Path, Double, Exception> zipFileSizeHandler;
        private final ThrowingPredicate<Path, Exception> validateHandler;
        private final GetArchiveEntriesHandler archiveEntriesHandler;
        private final DownloadArchiveEntryHandler downloadArchiveEntryHandler;

        public static ZipFormat getHandler(String ext) {
            for (ZipFormat zipFormat : ZipFormat.values()) {
                for (String extension : zipFormat.extensions) {
                    if (extension.equals(ext)) {
                        return zipFormat;
                    }
                }
            }
            throw new RuntimeException("Unable to find unzip handle for file extension: " + ext);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @NotNull
    private static TarArchiveInputStream getTarArchiveInputStream(Path file) throws IOException {
        TarArchiveInputStream stream =
                new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(Files.newInputStream(file))));
        return stream;
    }

    interface ZipArchiveHandler {
        void zip(List<Path> sources, Path destination, String level, char[] password, ProgressBar progressBar) throws Exception;
    }

    interface DownloadArchiveEntryHandler {
        InputStream getEntry(Path archive, String entryName, char[] password) throws Exception;
    }

    interface GetArchiveEntriesHandler {
        List<File> getChildren(Path archive, char[] password) throws Exception;
    }

    interface UnZipArchiveHandler {
        List<Path> unzip(Path file, Path destination, char[] password, ProgressBar progressBar, UnzipFileIssueHandler handler,
                         double fileSize)
                throws Exception;
    }

    @SneakyThrows
    public static InputStream downloadArchiveEntry(Path archive, String entryName, String password) {
        String ext = FilenameUtils.getExtension(archive.getFileName().toString());
        return ZipFormat.getHandler(ext).downloadArchiveEntryHandler.getEntry(archive, entryName.replaceAll("\\\\", "/"),
                password == null ? null : password.toCharArray());
    }

    @SneakyThrows
    public static @NotNull Path zip(@NotNull List<Path> sources, @NotNull Path destination, ZipFormat zipFormat,
                                    @Nullable String level, @Nullable String password, @Nullable ProgressBar progressBar) {
        if (progressBar != null) {
            progressBar.progress(0, "Zip files. Calculate size...");
        }
        String fileExt = FilenameUtils.getExtension(destination.getFileName().toString());
        for (String ext : zipFormat.extensions) {
            if (fileExt.equals(ext)) {
                destination = destination.resolveSibling(destination.getFileName() + ext);
                break;
            }
        }
        char[] pwd = Optional.ofNullable(StringUtils.trimToNull(password)).map(String::toCharArray).orElse(null);
        zipFormat.zipHandler.zip(sources, destination, level, pwd, progressBar);

        if (progressBar != null) {
            progressBar.progress(99, "Zip files done.");
        }
        return destination;
    }
}
