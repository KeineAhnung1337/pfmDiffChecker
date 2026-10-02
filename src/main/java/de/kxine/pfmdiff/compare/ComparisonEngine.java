package de.kxine.pfmdiff.compare;

import de.kxine.pfmdiff.model.ComparisonReport;
import de.kxine.pfmdiff.model.EntryResult;
import de.kxine.pfmdiff.model.EntryResult.EntryType;
import de.kxine.pfmdiff.model.EntryResult.FileKind;
import de.kxine.pfmdiff.model.EntryResult.Status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

public final class ComparisonEngine {
    public static final int DEFAULT_DETAIL_LIMIT = 500;

    public ComparisonReport compare(Config config, ProgressListener progress, BooleanSupplier cancelled)
            throws IOException, ComparisonCancelledException {
        Path original = validateRoot(config.originalRoot(), "Original");
        Path comparison = validateRoot(config.comparisonRoot(), "Comparison");
        Path excluded = config.excludedOutput() == null ? null : config.excludedOutput().toAbsolutePath().normalize();

        Map<String, NodeInfo> left = scan(original, excluded, cancelled);
        Map<String, NodeInfo> right = scan(comparison, excluded, cancelled);
        TreeSet<String> paths = new TreeSet<>();
        paths.addAll(left.keySet());
        paths.addAll(right.keySet());

        List<EntryResult> entries = new ArrayList<>();
        int current = 0;
        for (String relativePath : paths) {
            checkCancelled(cancelled);
            progress.update(current++, paths.size(), relativePath);
            entries.add(compareEntry(relativePath, left.get(relativePath), right.get(relativePath), config.detailLimit(), cancelled));
        }
        progress.update(paths.size(), paths.size(), "Complete");
        return new ComparisonReport(original, comparison, Instant.now(), entries);
    }

    private EntryResult compareEntry(String relativePath, NodeInfo left, NodeInfo right, int detailLimit,
                                     BooleanSupplier cancelled) throws ComparisonCancelledException {
        if (left == null || right == null) {
            NodeInfo present = left == null ? right : left;
            Status status = left == null ? Status.ADDED : Status.REMOVED;
            if (present.error != null) return error(relativePath, present.type, FileKind.NOT_APPLICABLE, present.error);
            if (present.type == EntryType.SYMBOLIC_LINK) {
                return new EntryResult(relativePath, EntryType.SYMBOLIC_LINK, FileKind.NOT_APPLICABLE, status,
                        null, null, null, null, null, List.of("Symbolic link was not followed."), null);
            }
            FileKind kind = FileKind.NOT_APPLICABLE;
            String hash = null;
            if (present.type == EntryType.FILE) {
                try {
                    kind = detectKind(present.path);
                    hash = sha256(present.path, cancelled);
                } catch (ComparisonCancelledException e) {
                    throw e;
                } catch (Exception e) {
                    return new EntryResult(relativePath, EntryType.FILE, kind, Status.ERROR,
                            left == null ? null : present.size, right == null ? null : present.size,
                            null, null, null, List.of(), readableError(e));
                }
            }
            return new EntryResult(relativePath, present.type, kind, status,
                    left == null ? null : present.size, right == null ? null : present.size,
                    left == null ? null : hash, right == null ? null : hash, null, List.of(), null);
        }
        if (left.error != null || right.error != null) {
            return error(relativePath, left.type, FileKind.NOT_APPLICABLE,
                    joinErrors(left.error, right.error));
        }
        if (left.type != right.type) {
            return new EntryResult(relativePath, EntryType.TYPE_MISMATCH, FileKind.NOT_APPLICABLE, Status.CHANGED,
                    left.size, right.size, null, null, false,
                    List.of("Original is " + label(left.type) + "; comparison is " + label(right.type) + "."), null);
        }
        if (left.type == EntryType.SYMBOLIC_LINK) {
            return new EntryResult(relativePath, EntryType.SYMBOLIC_LINK, FileKind.NOT_APPLICABLE, Status.SKIPPED,
                    null, null, null, null, null, List.of("Symbolic link was not followed."), null);
        }
        if (left.type == EntryType.DIRECTORY) {
            return new EntryResult(relativePath, EntryType.DIRECTORY, FileKind.NOT_APPLICABLE, Status.IDENTICAL,
                    null, null, null, null, true, List.of(), null);
        }

        FileKind leftKind;
        FileKind rightKind;
        try {
            leftKind = detectKind(left.path);
            rightKind = detectKind(right.path);
        } catch (IOException e) {
            return error(relativePath, EntryType.FILE, FileKind.NOT_APPLICABLE, readableError(e));
        }
        if (leftKind != rightKind) {
            return new EntryResult(relativePath, EntryType.FILE, FileKind.NOT_APPLICABLE, Status.CHANGED,
                    left.size, right.size, null, null, false,
                    List.of("Original is " + leftKind + "; comparison is " + rightKind + "."), null);
        }

        checkCancelled(cancelled);
        String leftHash;
        String rightHash;
        try {
            leftHash = sha256(left.path, cancelled);
            rightHash = sha256(right.path, cancelled);
        } catch (ComparisonCancelledException e) {
            throw e;
        } catch (Exception e) {
            return error(relativePath, EntryType.FILE, leftKind, readableError(e));
        }
        if (leftHash.equals(rightHash)) {
            return new EntryResult(relativePath, EntryType.FILE, leftKind, Status.IDENTICAL,
                    left.size, right.size, leftHash, rightHash, true, List.of(), null);
        }

        if (leftKind == FileKind.BINARY) {
            try {
                List<String> details = BinaryFileComparator.compare(left.path, right.path, detailLimit, cancelled);
                return new EntryResult(relativePath, EntryType.FILE, leftKind, Status.CHANGED,
                        left.size, right.size, leftHash, rightHash, null, details, null);
            } catch (ComparisonCancelledException e) {
                throw e;
            } catch (Exception e) {
                return new EntryResult(relativePath, EntryType.FILE, leftKind, Status.ERROR,
                        left.size, right.size, leftHash, rightHash, null, List.of(), readableError(e));
            }
        }
        try {
            if (leftKind == FileKind.XML) {
                XmlFileComparator.ContentComparison result = XmlFileComparator.compare(left.path, right.path, detailLimit);
                return new EntryResult(relativePath, EntryType.FILE, leftKind, Status.CHANGED,
                        left.size, right.size, leftHash, rightHash, result.semanticEqual(), result.details(), null);
            }
            PdfFileComparator.ContentComparison result = PdfFileComparator.compare(left.path, right.path, detailLimit);
            return new EntryResult(relativePath, EntryType.FILE, leftKind, Status.CHANGED,
                    left.size, right.size, leftHash, rightHash, result.semanticEqual(), result.details(), null);
        } catch (Exception e) {
            return new EntryResult(relativePath, EntryType.FILE, leftKind, Status.ERROR,
                    left.size, right.size, leftHash, rightHash, null, List.of(), readableError(e));
        }
    }

    private Map<String, NodeInfo> scan(Path root, Path excluded, BooleanSupplier cancelled)
            throws IOException, ComparisonCancelledException {
        Map<String, NodeInfo> entries = new TreeMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    checkCancelledUnchecked(cancelled);
                    if (!dir.equals(root) && !isExcluded(dir, excluded)) {
                        entries.put(relative(root, dir), new NodeInfo(dir, EntryType.DIRECTORY, null, null));
                    }
                    return isExcluded(dir, excluded) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    checkCancelledUnchecked(cancelled);
                    if (!isExcluded(file, excluded)) {
                        EntryType type = attrs.isSymbolicLink() ? EntryType.SYMBOLIC_LINK : EntryType.FILE;
                        entries.put(relative(root, file), new NodeInfo(file, type, attrs.size(), null));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    if (!isExcluded(file, excluded)) {
                        entries.put(relative(root, file), new NodeInfo(file, EntryType.FILE, null, readableError(exc)));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (CancelledIoException e) {
            throw new ComparisonCancelledException();
        }
        return entries;
    }

    private static Path validateRoot(Path root, String label) throws IOException {
        if (root == null) throw new IOException(label + " directory is required.");
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) throw new IOException(label + " path is not a directory: " + normalized);
        if (!Files.isReadable(normalized)) throw new IOException(label + " directory is not readable: " + normalized);
        return normalized;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static boolean isExcluded(Path path, Path excluded) {
        return excluded != null && path.toAbsolutePath().normalize().equals(excluded);
    }

    private static FileKind detectKind(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".xml")) return FileKind.XML;
        if (name.endsWith(".pdf")) return FileKind.PDF;
        byte[] prefix = new byte[5];
        try (InputStream input = Files.newInputStream(path)) {
            int count = input.read(prefix);
            if (count == 5 && Arrays.equals(prefix, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                return FileKind.PDF;
            }
        }
        return FileKind.BINARY;
    }

    private static String sha256(Path path, BooleanSupplier cancelled) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream raw = Files.newInputStream(path); DigestInputStream input = new DigestInputStream(raw, digest)) {
            byte[] buffer = new byte[64 * 1024];
            while (input.read(buffer) != -1) checkCancelled(cancelled);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static EntryResult error(String path, EntryType type, FileKind kind, String message) {
        return new EntryResult(path, type, kind, Status.ERROR, null, null, null, null, null, List.of(), message);
    }

    private static String joinErrors(String left, String right) {
        if (left == null) return right;
        if (right == null) return left;
        return "Original: " + left + "; comparison: " + right;
    }

    private static String readableError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String label(EntryType type) {
        return type.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws ComparisonCancelledException {
        if (cancelled != null && cancelled.getAsBoolean()) throw new ComparisonCancelledException();
    }

    private static void checkCancelledUnchecked(BooleanSupplier cancelled) throws CancelledIoException {
        if (cancelled != null && cancelled.getAsBoolean()) throw new CancelledIoException();
    }

    private record NodeInfo(Path path, EntryType type, Long size, String error) { }

    private static final class CancelledIoException extends IOException { }

    public record Config(Path originalRoot, Path comparisonRoot, Path excludedOutput, int detailLimit) {
        public Config(Path originalRoot, Path comparisonRoot, Path excludedOutput) {
            this(originalRoot, comparisonRoot, excludedOutput, DEFAULT_DETAIL_LIMIT);
        }

        public Config {
            if (detailLimit < 10) throw new IllegalArgumentException("detailLimit must be at least 10");
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void update(int completed, int total, String currentPath);
    }

    public static final class ComparisonCancelledException extends Exception { }
}
