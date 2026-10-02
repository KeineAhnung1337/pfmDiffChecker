package de.kxine.pfmdiff.compare;

import de.kxine.pfmdiff.model.ComparisonReport;
import de.kxine.pfmdiff.model.EntryResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComparisonEngineTest {
    @TempDir
    Path temporary;

    @Test
    void comparesMixedTreesByRelativePath() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path comparison = Files.createDirectory(temporary.resolve("comparison"));
        Files.createDirectories(original.resolve("pfm-1-ABCABC/nested"));
        Files.createDirectories(comparison.resolve("pfm-1-ABCABC/nested"));
        Files.write(original.resolve("pfm-1-ABCABC/same.private"), new byte[]{1, 2, 3});
        Files.write(comparison.resolve("pfm-1-ABCABC/same.private"), new byte[]{1, 2, 3});
        Files.write(original.resolve("pfm-1-ABCABC/changed.private"), new byte[]{1, 2, 3});
        Files.write(comparison.resolve("pfm-1-ABCABC/changed.private"), new byte[]{1, 9, 3});
        Files.writeString(original.resolve("only-original.txt"), "old");
        Files.writeString(comparison.resolve("only-comparison.txt"), "new");

        ComparisonReport report = compare(original, comparison);
        Map<String, EntryResult> entries = report.entries().stream()
                .collect(Collectors.toMap(EntryResult::relativePath, Function.identity()));

        assertEquals(EntryResult.Status.IDENTICAL, entries.get("pfm-1-ABCABC/same.private").status());
        assertEquals(EntryResult.Status.CHANGED, entries.get("pfm-1-ABCABC/changed.private").status());
        assertEquals(EntryResult.FileKind.BINARY, entries.get("pfm-1-ABCABC/changed.private").fileKind());
        assertNotEquals(entries.get("pfm-1-ABCABC/changed.private").originalHash(),
                entries.get("pfm-1-ABCABC/changed.private").comparisonHash());
        assertTrue(entries.get("pfm-1-ABCABC/changed.private").details().stream()
                .anyMatch(line -> line.contains("0x00000001") && line.contains("decimal 1")));
        assertTrue(entries.get("pfm-1-ABCABC/changed.private").details().stream()
                .anyMatch(line -> line.contains("Original sample:") && line.contains("02")));
        assertTrue(entries.get("pfm-1-ABCABC/changed.private").details().stream()
                .anyMatch(line -> line.contains("Comparison sample:") && line.contains("09")));
        assertTrue(entries.get("pfm-1-ABCABC/changed.private").details().stream()
                .anyMatch(line -> line.contains("Changed share:")));
        assertTrue(entries.get("pfm-1-ABCABC/changed.private").details().stream()
                .anyMatch(line -> line.contains("*") && line.contains("0x00000001") && line.contains("0B")));
        assertEquals(EntryResult.Status.REMOVED, entries.get("only-original.txt").status());
        assertTrue(entries.get("only-original.txt").originalHash() != null);
        assertEquals(EntryResult.Status.ADDED, entries.get("only-comparison.txt").status());
        assertTrue(entries.get("only-comparison.txt").comparisonHash() != null);
        assertTrue(report.hasDifferences());
        assertFalse(report.hasErrors());
    }

    @Test
    void separatesExactAndStructuralXmlChanges() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path comparison = Files.createDirectory(temporary.resolve("comparison"));
        Files.writeString(original.resolve("format.xml"), "<root a=\"1\" b=\"2\"><item>same</item></root>");
        Files.writeString(comparison.resolve("format.xml"), "<root b=\"2\" a=\"1\">\n  <item>same</item>\n</root>\n");
        Files.writeString(original.resolve("value.xml"), "<root><item>old</item></root>");
        Files.writeString(comparison.resolve("value.xml"), "<root><item>new</item></root>");

        Map<String, EntryResult> entries = compare(original, comparison).entries().stream()
                .collect(Collectors.toMap(EntryResult::relativePath, Function.identity()));

        EntryResult formatting = entries.get("format.xml");
        assertEquals(EntryResult.Status.CHANGED, formatting.status());
        assertEquals(Boolean.TRUE, formatting.semanticEqual());
        assertTrue(formatting.details().stream().anyMatch(line -> line.contains("equal")));
        EntryResult value = entries.get("value.xml");
        assertEquals(Boolean.FALSE, value.semanticEqual());
        assertTrue(value.details().stream().anyMatch(line -> line.contains("old")));
        assertTrue(value.details().stream().anyMatch(line -> line.contains("new")));
    }

    @Test
    void reportsMalformedXmlButContinues() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path comparison = Files.createDirectory(temporary.resolve("comparison"));
        Files.writeString(original.resolve("broken.xml"), "<root>");
        Files.writeString(comparison.resolve("broken.xml"), "<different>");
        Files.writeString(original.resolve("healthy.bin"), "same");
        Files.writeString(comparison.resolve("healthy.bin"), "same");

        ComparisonReport report = compare(original, comparison);
        Map<String, EntryResult> entries = report.entries().stream()
                .collect(Collectors.toMap(EntryResult::relativePath, Function.identity()));

        assertEquals(EntryResult.Status.ERROR, entries.get("broken.xml").status());
        assertEquals(EntryResult.Status.IDENTICAL, entries.get("healthy.bin").status());
        assertTrue(report.hasErrors());
    }

    @Test
    void comparesPdfTextPerPage() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path comparison = Files.createDirectory(temporary.resolve("comparison"));
        writePdf(original.resolve("document.pdf"), "Original PDF text");
        writePdf(comparison.resolve("document.pdf"), "Changed PDF text");

        EntryResult pdf = compare(original, comparison).entries().stream()
                .filter(entry -> entry.relativePath().equals("document.pdf")).findFirst().orElseThrow();

        assertEquals(EntryResult.Status.CHANGED, pdf.status());
        assertEquals(EntryResult.FileKind.PDF, pdf.fileKind());
        assertEquals(Boolean.FALSE, pdf.semanticEqual());
        assertTrue(pdf.details().stream().anyMatch(line -> line.contains("Page 1")));
    }

    @Test
    void recognizesPdfSignatureWithPrivateExtension() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path comparison = Files.createDirectory(temporary.resolve("comparison"));
        writePdf(original.resolve("document.private"), "old");
        writePdf(comparison.resolve("document.private"), "new");

        EntryResult pdf = compare(original, comparison).entries().stream()
                .filter(entry -> entry.relativePath().equals("document.private")).findFirst().orElseThrow();
        assertEquals(EntryResult.FileKind.PDF, pdf.fileKind());
    }

    @Test
    void reportsBinaryTailWhenFileLengthChanges() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path comparison = Files.createDirectory(temporary.resolve("comparison"));
        Files.write(original.resolve("growing.private"), new byte[]{1, 2});
        Files.write(comparison.resolve("growing.private"), new byte[]{1, 2, 3, 4});

        EntryResult binary = compare(original, comparison).entries().stream()
                .filter(entry -> entry.relativePath().equals("growing.private")).findFirst().orElseThrow();

        assertTrue(binary.details().stream().anyMatch(line -> line.contains("0x00000002")
                && line.contains("0x00000003")));
        assertTrue(binary.details().stream().anyMatch(line -> line.contains("Original sample:") && line.contains("-- --")));
        assertTrue(binary.details().stream().anyMatch(line -> line.contains("Comparison sample:") && line.contains("03 04")));
    }

    @Test
    void separatesPdfBinaryOnlyChangesFromTextChanges() throws Exception {
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path comparison = Files.createDirectory(temporary.resolve("comparison"));
        writePdf(original.resolve("document.pdf"), "unchanged", "Old metadata");
        writePdf(comparison.resolve("document.pdf"), "unchanged", "New metadata");

        EntryResult pdf = compare(original, comparison).entries().stream()
                .filter(entry -> entry.relativePath().equals("document.pdf")).findFirst().orElseThrow();

        assertEquals(EntryResult.Status.CHANGED, pdf.status());
        assertEquals(Boolean.TRUE, pdf.semanticEqual());
        assertTrue(pdf.details().stream().anyMatch(line -> line.contains("binary representation changed")));
    }

    private ComparisonReport compare(Path original, Path comparison) throws Exception {
        return new ComparisonEngine().compare(new ComparisonEngine.Config(original, comparison, null),
                (completed, total, path) -> { }, () -> false);
    }

    private static void writePdf(Path path, String text) throws Exception {
        writePdf(path, text, null);
    }

    private static void writePdf(Path path, String text, String title) throws Exception {
        try (PDDocument document = new PDDocument()) {
            if (title != null) {
                PDDocumentInformation information = new PDDocumentInformation();
                information.setTitle(title);
                document.setDocumentInformation(information);
            }
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            document.save(path.toFile());
        }
    }
}
