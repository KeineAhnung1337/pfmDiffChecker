package de.kxine.pfmdiff;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    @TempDir
    Path temporary;

    @Test
    void cliCreatesReportAndReturnsDifferenceCode() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path comparison = Files.createDirectory(temporary.resolve("comparison"));
        Files.writeString(original.resolve("value.private"), "before");
        Files.writeString(comparison.resolve("value.private"), "after");
        Path report = temporary.resolve("report.html");

        int exit = Main.runCli(new String[]{"--original", original.toString(), "--compare", comparison.toString(),
                "--output", report.toString()});

        assertEquals(1, exit);
        assertTrue(Files.isRegularFile(report));
    }

    @Test
    void cliProtectsExistingReport() throws Exception {
        Path report = temporary.resolve("report.html");
        Files.writeString(report, "keep");

        int exit = Main.runCli(new String[]{"--original", temporary.toString(), "--compare", temporary.toString(),
                "--output", report.toString()});

        assertEquals(2, exit);
        assertEquals("keep", Files.readString(report));
    }

    @Test
    void cliRejectsReportInsideComparedDirectoryEvenWithOverwrite() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path comparison = Files.createDirectory(temporary.resolve("comparison"));
        Path source = original.resolve("source.html");
        Files.writeString(source, "important source data");

        int exit = Main.runCli(new String[]{"--original", original.toString(), "--compare", comparison.toString(),
                "--output", source.toString(), "--overwrite"});

        assertEquals(2, exit);
        assertEquals("important source data", Files.readString(source));
    }

    @Test
    void cliRejectsNewReportInsideComparedDirectory() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path comparison = Files.createDirectory(temporary.resolve("comparison"));
        Path output = original.resolve("reports/result.html");

        int exit = Main.runCli(new String[]{"--original", original.toString(), "--compare", comparison.toString(),
                "--output", output.toString()});

        assertEquals(2, exit);
        assertTrue(Files.notExists(output));
    }

    @Test
    void cliRejectsReportInsideComparedDirectoryThroughSymlink() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path comparison = Files.createDirectory(temporary.resolve("comparison"));
        Path alias = temporary.resolve("alias");
        try {
            Files.createSymbolicLink(alias, original);
        } catch (UnsupportedOperationException | SecurityException | IOException e) {
            return; // Symlink creation is not available on this platform.
        }

        Path output = alias.resolve("report.html");
        int exit = Main.runCli(new String[]{"--original", original.toString(), "--compare", comparison.toString(),
                "--output", output.toString()});

        assertEquals(2, exit);
        assertTrue(Files.notExists(output));
    }

    @Test
    void cliReportsSkippedSymlinksAsIncomplete() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path comparison = Files.createDirectory(temporary.resolve("comparison"));
        Path target = temporary.resolve("target.txt");
        Files.writeString(target, "outside data");
        try {
            Files.createSymbolicLink(original.resolve("link"), target);
            Files.createSymbolicLink(comparison.resolve("link"), target);
        } catch (UnsupportedOperationException | SecurityException | IOException e) {
            Assumptions.abort("Symbolic links are not available: " + e);
        }

        int exit = Main.runCli(new String[]{"--original", original.toString(), "--compare", comparison.toString(),
                "--output", temporary.resolve("report.html").toString()});

        assertEquals(2, exit);
    }

    @Test
    void serviceDoesNotReplaceOutputCreatedWhileComparing() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path comparison = Files.createDirectory(temporary.resolve("comparison"));
        Files.writeString(original.resolve("file"), "old");
        Files.writeString(comparison.resolve("file"), "new");
        Path output = temporary.resolve("report.html");

        assertThrows(FileAlreadyExistsException.class, () -> new AppService().compareAndWrite(
                original, comparison, output, (completed, total, path) -> {
                    if (Files.notExists(output)) {
                        try {
                            Files.writeString(output, "created during comparison");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                }, () -> false, false));
        assertEquals("created during comparison", Files.readString(output));
    }
}
