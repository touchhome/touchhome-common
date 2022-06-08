package org.touchhome.common.util.archive;

import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.IOUtils;
import org.touchhome.common.model.ProgressBar;
import org.touchhome.common.util.ArchiveUtil;
import org.touchhome.common.util.CommonUtils;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.compress.archivers.examples.Archiver.EMPTY_FileVisitOption;
import static org.apache.commons.compress.utils.IOUtils.EMPTY_LINK_OPTIONS;
import static org.apache.commons.io.FileUtils.ONE_MB_BI;

public class ApacheCompress {

    public static void archiveTar(List<Path> sources, Path destination, String level, char[] password, ProgressBar progressBar)
            throws IOException {
        BufferedOutputStream buffOut = new BufferedOutputStream(Files.newOutputStream(destination));
        TarArchiveOutputStream out = new TarArchiveOutputStream(new GzipCompressorOutputStream(buffOut));
        archive(sources, out);
    }

    public static void archive(List<Path> sources, ArchiveOutputStream out) throws IOException {
        for (Path source : sources) {
            if (Files.isDirectory(source)) {
                Files.walkFileTree(source, EMPTY_FileVisitOption, Integer.MAX_VALUE,
                        new ArchiverFileVisitor(out, source));
            } else {
                writeZipEntry(source, true, source, out);
            }
        }
        out.finish();
        out.close();
    }

    private static void writeZipEntry(Path path, boolean isFile, Path directory, ArchiveOutputStream target) throws IOException {
        String name = directory.relativize(path).toString().replace('\\', '/');
        if (!name.isEmpty()) {
            ArchiveEntry archiveEntry =
                    target.createArchiveEntry(path, isFile || name.endsWith("/") ? name : name + "/", EMPTY_LINK_OPTIONS);
            target.putArchiveEntry(archiveEntry);
            if (isFile) {
                Files.copy(path, target);
            }
            target.closeArchiveEntry();
        }
    }

    public static List<Path> unzipSeven7Archive(Path file, Path destination, char[] password, ProgressBar progressBar,
                                                ArchiveUtil.UnzipFileIssueHandler handler, double fileSize) throws IOException {
        SevenZFile sevenZFile = new SevenZFile(file.toFile(), password);
        ArchiveInputStream stream = new ArchiveInputStream() {

            @Override
            public ArchiveEntry getNextEntry() throws IOException {
                return sevenZFile.getNextEntry();
            }

            @Override
            public int read(byte[] buf, int offset, int numToRead) throws IOException {
                return sevenZFile.read(buf, offset, numToRead);
            }

            @Override
            public void close() throws IOException {
                sevenZFile.close();
            }
        };
        return ApacheCompress.unzipCompress(stream, destination, handler, fileSize, progressBar);
    }

    @SneakyThrows
    public static List<Path> unzipCompress(ArchiveInputStream stream, Path destination,
                                           ArchiveUtil.UnzipFileIssueHandler fileResolveHandler,
                                           double fileSize, ProgressBar progressBar) {
        List<Path> paths = new ArrayList<>();
        int maxMb = (int) (fileSize / ONE_MB_BI.intValue());
        byte[] oneMBBuff = new byte[ONE_MB_BI.intValue()];
        OpenOption[] openOptions = fileResolveHandler == ArchiveUtil.UnzipFileIssueHandler.replace ?
                new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING} :
                new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE};
        ArchiveEntry entry;
        int nextStep = 1;
        int readBytes = 0;
        while ((entry = stream.getNextEntry()) != null) {
            if (!stream.canReadEntryData(entry)) {
                continue;
            }
            Path entryPath = destination.resolve(entry.getName());
            if (entry.isDirectory()) {
                if (!Files.isDirectory(entryPath)) {
                    paths.add(Files.createDirectories(entryPath));
                }
            } else {
                Path parent = entryPath.getParent();
                if (!Files.isDirectory(parent)) {
                    paths.add(Files.createDirectories(parent));
                }

                if (Files.exists(entryPath)) {
                    switch (fileResolveHandler) {
                        case skip:
                            continue;
                        case replace: // already in OpenOptions
                            break;
                        case replaceNotMatch:
                            Path tmpPath = CommonUtils.getTmpPath().resolve(entry.getName());
                            Files.copy(stream, tmpPath, StandardCopyOption.REPLACE_EXISTING);
                            if (IOUtils.contentEquals(Files.newInputStream(tmpPath), Files.newInputStream(entryPath))) {
                                Files.delete(tmpPath);
                                continue;
                            }
                            break;
                        case error:
                            throw new FileAlreadyExistsException("Unarchive file '" + entry + "' already exists");
                    }
                }

                paths.add(entryPath);

                try (OutputStream out = Files.newOutputStream(entryPath, openOptions)) {
                    int bytesToRead = (int) entry.getSize();
                    while (bytesToRead > 0) {
                        long bytes = Math.min(bytesToRead, ONE_MB_BI.intValue());
                        byte[] content = bytes == ONE_MB_BI.intValue() ? oneMBBuff : new byte[(int) bytes];
                        stream.read(content);
                        out.write(content);
                        bytesToRead -= bytes;
                        readBytes += bytes;

                        if (readBytes / ONE_MB_BI.doubleValue() > nextStep) {
                            nextStep++;
                            if (progressBar != null) {
                                progressBar.progress((readBytes / fileSize * 100) * 0.99, // max 99%
                                        "Extract " + readBytes / ONE_MB_BI.intValue() + "Mb. of " + maxMb + " Mb.");
                            }
                        }
                    }
                }
            }
        }
        stream.close();
        return paths;
    }

    @SneakyThrows
    public static void archiveSeven7(List<Path> sources, Path destination) {
        SevenZOutputFile target = new SevenZOutputFile(destination.toFile());
        for (Path source : sources) {
            if (Files.isDirectory(source)) {
                Files.walkFileTree(source, new ArchiverFileVisitor(null, source) {

                    @Override
                    protected FileVisitResult visit(Path path, BasicFileAttributes attrs, boolean isFile) throws IOException {
                        write7ZipEntry(path, isFile, source, target);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                write7ZipEntry(source, true, source, target);
            }
        }
        target.close();
    }

    private static void write7ZipEntry(Path path, boolean isFile, Path source, SevenZOutputFile target) throws IOException {
        String name = source.relativize(path).toString().replace('\\', '/');
        if (!name.isEmpty()) {
            ArchiveEntry archiveEntry = target.createArchiveEntry(path, isFile || name.endsWith("/") ? name : name + "/");
            target.putArchiveEntry(archiveEntry);
            if (isFile) {
                target.write(path);
            }
            target.closeArchiveEntry();
        }
    }

    @SneakyThrows
    public static InputStream downloadEntry(ArchiveInputStream stream, String entryName) {
        ArchiveEntry entry;
        List<File> files = new ArrayList<>();
        try {
            while ((entry = stream.getNextEntry()) != null) {
                if (!stream.canReadEntryData(entry)) {
                    continue;
                }
                if (entry.getName().equals(entryName)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream((int) entry.getSize());
                    IOUtils.copy(stream, out);
                    return new ByteArrayInputStream(out.toByteArray());
                }
            }
        } finally {
            stream.close();
        }
        return null;
    }

    @SneakyThrows
    public static List<File> getArchiveEntries(ArchiveInputStream stream) {
        ArchiveEntry entry;
        List<File> files = new ArrayList<>();
        while ((entry = stream.getNextEntry()) != null) {
            if (!stream.canReadEntryData(entry)) {
                continue;
            }

            ArchiveEntry archiveEntry = entry;
            files.add(new File(archiveEntry.getName()) {

                @Override
                public String[] list() {
                    return new String[]{""};
                }

                @Override
                public boolean isDirectory() {
                    return archiveEntry.isDirectory();
                }

                @Override
                public long length() {
                    return archiveEntry.getSize();
                }

                @Override
                public long lastModified() {
                    return archiveEntry.getLastModifiedDate().getTime();
                }
            });
        }
        stream.close();
        return files;
    }

    @AllArgsConstructor
    private static class ArchiverFileVisitor extends SimpleFileVisitor<Path> {

        private final ArchiveOutputStream target;
        private final Path directory;

        @Override
        public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
            return visit(dir, attrs, false);
        }

        protected FileVisitResult visit(Path path, BasicFileAttributes attrs, boolean isFile) throws IOException {
            writeZipEntry(path, isFile, directory, target);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
            return visit(file, attrs, true);
        }
    }
}
