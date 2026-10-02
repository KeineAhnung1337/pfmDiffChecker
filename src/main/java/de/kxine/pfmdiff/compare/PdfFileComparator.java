package de.kxine.pfmdiff.compare;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class PdfFileComparator {
    private PdfFileComparator() {
    }

    static ContentComparison compare(Path original, Path comparison, int detailLimit) throws Exception {
        try (PDDocument left = Loader.loadPDF(original.toFile());
             PDDocument right = Loader.loadPDF(comparison.toFile())) {
            List<String> leftPages = extractPages(left);
            List<String> rightPages = extractPages(right);
            boolean textEqual = leftPages.equals(rightPages);
            List<String> details = new ArrayList<>();
            details.add("Exact PDF bytes: different.");
            details.add(textEqual
                    ? "Extracted PDF text: equal (the binary representation changed)."
                    : "Extracted PDF text: different.");
            if (!textEqual) {
                int pageCount = Math.max(leftPages.size(), rightPages.size());
                for (int i = 0; i < pageCount && details.size() < detailLimit; i++) {
                    String oldPage = i < leftPages.size() ? leftPages.get(i) : null;
                    String newPage = i < rightPages.size() ? rightPages.get(i) : null;
                    if (java.util.Objects.equals(oldPage, newPage)) continue;
                    if (oldPage == null) {
                        details.add("Page " + (i + 1) + " added.");
                    } else if (newPage == null) {
                        details.add("Page " + (i + 1) + " removed.");
                    } else {
                        details.add("Page " + (i + 1) + " text differences:");
                        List<String> pageDiff = TextDiff.lines(oldPage, newPage, detailLimit - details.size());
                        details.addAll(pageDiff.subList(0, Math.min(pageDiff.size(), detailLimit - details.size())));
                    }
                }
                if (details.size() >= detailLimit) details.add("… additional details omitted from this report");
            }
            return new ContentComparison(textEqual, details);
        }
    }

    private static List<String> extractPages(PDDocument document) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        List<String> pages = new ArrayList<>();
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            pages.add(stripper.getText(document).replace("\r\n", "\n").replace('\r', '\n'));
        }
        return pages;
    }

    record ContentComparison(boolean semanticEqual, List<String> details) { }
}
