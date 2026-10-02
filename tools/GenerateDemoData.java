import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generates a small, deterministic demonstration directory pair. */
public final class GenerateDemoData {
    private GenerateDemoData() { }

    public static void main(String[] args) throws Exception {
        Path demoRoot = args.length == 0 ? Path.of("demo-data") : Path.of(args[0]);
        Path original = demoRoot.resolve("original/pfm-1-ABCABC");
        Path comparison = demoRoot.resolve("comparison/pfm-1-ABCABC");
        Files.createDirectories(original.resolve("nested"));
        Files.createDirectories(comparison.resolve("nested"));

        writeText(original.resolve("formatting-only.xml"),
                "<configuration version=\"1\" enabled=\"true\"><name>Demo</name></configuration>\n");
        writeText(comparison.resolve("formatting-only.xml"),
                "<configuration enabled=\"true\" version=\"1\">\n  <name>Demo</name>\n</configuration>\n");

        writeText(original.resolve("changed-values.xml"),
                "<configuration><threshold>10</threshold><mode>safe</mode></configuration>\n");
        writeText(comparison.resolve("changed-values.xml"),
                "<configuration><threshold>25</threshold><mode>fast</mode></configuration>\n");

        Files.write(original.resolve("firmware.private"), new byte[]{0x01, 0x23, 0x45, 0x67, 0x01});
        Files.write(comparison.resolve("firmware.private"), new byte[]{0x01, 0x23, 0x45, 0x67, 0x02});
        Files.write(original.resolve("unchanged.private"), new byte[]{0x10, 0x20, 0x30});
        Files.write(comparison.resolve("unchanged.private"), new byte[]{0x10, 0x20, 0x30});
        writeText(original.resolve("original-only.private"), "This file exists only in the original tree.\n");
        writeText(comparison.resolve("comparison-only.private"), "This file exists only in the comparison tree.\n");
        writeText(original.resolve("nested/unchanged.txt"), "Same nested content\n");
        writeText(comparison.resolve("nested/unchanged.txt"), "Same nested content\n");

        writePdf(original.resolve("changed-text.pdf"), "Original PDF text", "Text example");
        writePdf(comparison.resolve("changed-text.pdf"), "Changed PDF text", "Text example");
        writePdf(original.resolve("binary-only.pdf"), "Visible text is unchanged", "Original metadata");
        writePdf(comparison.resolve("binary-only.pdf"), "Visible text is unchanged", "Comparison metadata");

        System.out.println("Demo data created in " + demoRoot.toAbsolutePath().normalize());
    }

    private static void writeText(Path path, String value) throws Exception {
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }

    private static void writePdf(Path path, String text, String title) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDDocumentInformation information = new PDDocumentInformation();
            information.setTitle(title);
            document.setDocumentInformation(information);
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            document.save(path.toFile());
        }
    }
}
