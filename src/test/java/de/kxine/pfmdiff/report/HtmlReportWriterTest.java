package de.kxine.pfmdiff.report;

import de.kxine.pfmdiff.model.ComparisonReport;
import de.kxine.pfmdiff.model.EntryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportWriterTest {
    @TempDir
    Path temporary;

    @Test
    void writesSelfContainedEscapedReport() throws Exception {
        EntryResult entry = new EntryResult("folder/<script>alert('x')</script>.xml",
                EntryResult.EntryType.FILE, EntryResult.FileKind.XML, EntryResult.Status.CHANGED,
                1L, 2L, "old", "new", false,
                List.of("- <old>", "+ <new>"), null);
        ComparisonReport report = new ComparisonReport(Path.of("original"), Path.of("comparison"),
                Instant.parse("2026-01-01T00:00:00Z"), List.of(entry));
        Path output = temporary.resolve("report.html");

        new HtmlReportWriter().write(report, output);
        String html = Files.readString(output);

        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"));
        assertFalse(html.contains("<script>alert('x')</script>"));
        assertTrue(html.contains("Content-Security-Policy"));
        assertFalse(html.contains("https://"));
        assertFalse(html.contains("data-search="));
        assertTrue(html.contains("row.textContent.toLocaleLowerCase()"));
    }

    @Test
    void refusesAnExistingDestinationUnlessOverwriteWasApproved() throws Exception {
        ComparisonReport report = new ComparisonReport(Path.of("original"), Path.of("comparison"),
                Instant.parse("2026-01-01T00:00:00Z"), List.of());
        Path output = temporary.resolve("report.html");
        Files.writeString(output, "created during comparison");

        assertThrows(FileAlreadyExistsException.class, () -> new HtmlReportWriter().write(report, output, false));
        assertEquals("created during comparison", Files.readString(output));
        new HtmlReportWriter().write(report, output, true);
        assertTrue(Files.readString(output).startsWith("<!doctype html>"));
    }
}
