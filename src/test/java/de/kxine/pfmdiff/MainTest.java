package de.kxine.pfmdiff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
