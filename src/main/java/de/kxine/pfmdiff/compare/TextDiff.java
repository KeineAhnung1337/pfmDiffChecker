package de.kxine.pfmdiff.compare;

import java.util.ArrayList;
import java.util.List;

final class TextDiff {
    private static final long MAX_LCS_CELLS = 4_000_000L;

    private TextDiff() {
    }

    static List<String> lines(String original, String comparison, int limit) {
        String[] left = normalize(original).split("\n", -1);
        String[] right = normalize(comparison).split("\n", -1);
        List<String> result = (long) left.length * right.length <= MAX_LCS_CELLS
                ? lcsDiff(left, right, limit + 1)
                : positionalDiff(left, right, limit + 1);
        if (result.size() > limit) {
            return withTruncation(result.subList(0, limit));
        }
        return result;
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static List<String> lcsDiff(String[] left, String[] right, int limit) {
        int[][] lcs = new int[left.length + 1][right.length + 1];
        for (int i = left.length - 1; i >= 0; i--) {
            for (int j = right.length - 1; j >= 0; j--) {
                lcs[i][j] = left[i].equals(right[j]) ? lcs[i + 1][j + 1]
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        List<String> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < left.length || j < right.length) {
            if (i < left.length && j < right.length && left[i].equals(right[j])) {
                i++;
                j++;
            } else if (j < right.length && (i == left.length || lcs[i][j + 1] >= lcs[i + 1][j])) {
                result.add("+ " + (j + 1) + ": " + right[j++]);
            } else {
                result.add("- " + (i + 1) + ": " + left[i++]);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private static List<String> positionalDiff(String[] left, String[] right, int limit) {
        List<String> result = new ArrayList<>();
        result.add("Large text: showing differences by line number instead of calculating moved lines.");
        int lines = Math.max(left.length, right.length);
        for (int i = 0; i < lines && result.size() < limit; i++) {
            String oldLine = i < left.length ? left[i] : null;
            String newLine = i < right.length ? right[i] : null;
            if (!java.util.Objects.equals(oldLine, newLine)) {
                if (oldLine != null) result.add("- " + (i + 1) + ": " + oldLine);
                if (newLine != null && result.size() < limit) result.add("+ " + (i + 1) + ": " + newLine);
            }
        }
        return result;
    }

    private static List<String> withTruncation(List<String> source) {
        List<String> result = new ArrayList<>(source);
        result.add("… additional differences omitted from this report");
        return result;
    }
}
