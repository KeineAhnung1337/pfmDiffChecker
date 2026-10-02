package de.kxine.pfmdiff.compare;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

final class PdfFileComparator {
    private static final long MAX_EXTRACTED_CHARACTERS = 16L * 1024 * 1024;
    private PdfFileComparator() {
    }

    static ContentComparison compare(Path original, Path comparison, int detailLimit, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        try (PDDocument left = Loader.loadPDF(original.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            checkCancelled(cancelled);
            try (PDDocument right = Loader.loadPDF(comparison.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                checkCancelled(cancelled);
                List<String> leftPages = extractPages(left, cancelled);
                List<String> rightPages = extractPages(right, cancelled);
                boolean textEqual = leftPages.equals(rightPages);
                List<String> details = new ArrayList<>();
                details.add("Exact PDF bytes: different.");
                details.add(textEqual
                        ? "Extracted PDF text: equal (the binary representation changed)."
                        : "Extracted PDF text: different.");
                if (!textEqual) {
                    int pageCount = Math.max(leftPages.size(), rightPages.size());
                    for (int i = 0; i < pageCount && details.size() < detailLimit; i++) {
                        checkCancelled(cancelled);
                        String oldPage = i < leftPages.size() ? leftPages.get(i) : null;
                        String newPage = i < rightPages.size() ? rightPages.get(i) : null;
                        if (java.util.Objects.equals(oldPage, newPage)) continue;
                        if (oldPage == null) {
                            details.add("Page " + (i + 1) + " added.");
                        } else if (newPage == null) {
                            details.add("Page " + (i + 1) + " removed.");
                        } else {
                            details.add("Page " + (i + 1) + " text differences:");
                            List<String> pageDiff = TextDiff.lines(oldPage, newPage, detailLimit - details.size(), cancelled);
                            details.addAll(pageDiff.subList(0, Math.min(pageDiff.size(), detailLimit - details.size())));
                        }
                    }
                    if (details.size() >= detailLimit) details.add("… additional details omitted from this report");
                }
                return new ContentComparison(textEqual, details);
            }
        }
    }

    private static List<String> extractPages(PDDocument document, BooleanSupplier cancelled) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        List<String> pages = new ArrayList<>();
        long characters = 0;
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            checkCancelled(cancelled);
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = stripper.getText(document).replace("\r\n", "\n").replace('\r', '\n');
            characters += text.length();
            if (characters > MAX_EXTRACTED_CHARACTERS) {
                throw new IOException("PDF text extraction is limited to 16 Mi characters per file.");
            }
            pages.add(text);
        }
        return pages;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws ComparisonEngine.ComparisonCancelledException {
        if (cancelled != null && cancelled.getAsBoolean()) throw new ComparisonEngine.ComparisonCancelledException();
    }

    record ContentComparison(boolean semanticEqual, List<String> details) { }
}
