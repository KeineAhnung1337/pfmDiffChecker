package de.kxine.pfmdiff.compare;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Produces a bounded positional byte diff without loading either file into memory. */
final class BinaryFileComparator {
    private static final int SAMPLE_BYTES_PER_RANGE = 16;
    private static final int CONTEXT_BYTES = 8;
    private static final int MAX_CONTEXT_ROWS = 64;

    private BinaryFileComparator() { }

    static List<String> compare(Path original, Path comparison, int detailLimit, BooleanSupplier cancelled)
            throws IOException, ComparisonEngine.ComparisonCancelledException {
        int maximumStoredRanges = Math.max(1, Math.min(100, detailLimit));
        List<ChangedRange> ranges = new ArrayList<>();
        RangeBuilder current = null;
        long offset = 0;
        long differingPositions = 0;
        long rangeCount = 0;

        try (InputStream left = new BufferedInputStream(Files.newInputStream(original), 64 * 1024);
             InputStream right = new BufferedInputStream(Files.newInputStream(comparison), 64 * 1024)) {
            while (true) {
                int oldByte = left.read();
                int newByte = right.read();
                if (oldByte == -1 && newByte == -1) break;
                if (oldByte != newByte) {
                    differingPositions++;
                    if (current == null) {
                        current = new RangeBuilder(offset);
                        rangeCount++;
                    }
                    current.append(offset, oldByte, newByte);
                } else if (current != null) {
                    store(ranges, current, maximumStoredRanges);
                    current = null;
                }
                offset++;
                if ((offset & 0xFFFF) == 0 && cancelled != null && cancelled.getAsBoolean()) {
                    throw new ComparisonEngine.ComparisonCancelledException();
                }
            }
        }
        if (current != null) store(ranges, current, maximumStoredRanges);

        List<String> details = new ArrayList<>();
        long originalSize = Files.size(original);
        long comparisonSize = Files.size(comparison);
        long comparedPositions = Math.max(originalSize, comparisonSize);
        long sizeDelta = comparisonSize - originalSize;
        details.add("Binary byte comparison: " + differingPositions + " differing position"
                + (differingPositions == 1 ? "" : "s") + " in " + rangeCount + " changed range"
                + (rangeCount == 1 ? "" : "s") + ".");
        details.add("Original size: " + originalSize + " bytes; comparison size: " + comparisonSize
                + " bytes; size delta: " + signed(sizeDelta) + " bytes.");
        details.add("Changed share: " + percentage(differingPositions, comparedPositions)
                + " of positional bytes; offsets are zero-based.");
        details.add("Offsets compare bytes at the same positions; inserted bytes can make subsequent offsets differ.");
        int shownRanges = 0;
        try (FileChannel leftChannel = FileChannel.open(original, StandardOpenOption.READ);
             FileChannel rightChannel = FileChannel.open(comparison, StandardOpenOption.READ)) {
            for (ChangedRange range : ranges) {
                List<String> rangeDetails = describeRange(range, leftChannel, rightChannel,
                        originalSize, comparisonSize, shownRanges + 1);
                if (details.size() + rangeDetails.size() + 1 > detailLimit) break;
                details.addAll(rangeDetails);
                shownRanges++;
            }
        }
        if (rangeCount > shownRanges) {
            details.add("… " + (rangeCount - shownRanges) + " additional changed range"
                    + (rangeCount - shownRanges == 1 ? "" : "s") + " omitted by the detail limit");
        }
        return details;
    }

    private static List<String> describeRange(ChangedRange range, FileChannel original, FileChannel comparison,
                                              long originalSize, long comparisonSize, int number) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Changed range #" + number + ": " + rangeLabel(range));
        lines.add("  Original sample:   " + String.join(" ", range.originalSample()) + sampleSuffix(range));
        lines.add("  Comparison sample: " + String.join(" ", range.comparisonSample()) + sampleSuffix(range));
        lines.add("  Context: " + CONTEXT_BYTES + " bytes before/after; '*' marks a differing position.");
        lines.add("    Mark Offset       Decimal       Original (hex dec chr)   Comparison (hex dec chr)   XOR");

        long maximumSize = Math.max(originalSize, comparisonSize);
        long windowStart = Math.max(0, range.start() - CONTEXT_BYTES);
        long windowEnd = Math.min(maximumSize - 1, range.end() + CONTEXT_BYTES);
        long windowLength = windowEnd - windowStart + 1;
        if (windowLength <= MAX_CONTEXT_ROWS) {
            appendRows(lines, original, comparison, windowStart, windowEnd);
        } else {
            int half = MAX_CONTEXT_ROWS / 2;
            appendRows(lines, original, comparison, windowStart, windowStart + half - 1);
            long omitted = windowLength - MAX_CONTEXT_ROWS;
            lines.add("         … " + omitted + " middle position" + (omitted == 1 ? "" : "s") + " omitted …");
            appendRows(lines, original, comparison, windowEnd - half + 1, windowEnd);
        }
        return lines;
    }

    private static void appendRows(List<String> lines, FileChannel original, FileChannel comparison,
                                   long start, long end) throws IOException {
        ByteBuffer oldBuffer = ByteBuffer.allocate(1);
        ByteBuffer newBuffer = ByteBuffer.allocate(1);
        for (long offset = start; offset <= end; offset++) {
            int oldByte = readByte(original, oldBuffer, offset);
            int newByte = readByte(comparison, newBuffer, offset);
            boolean changed = oldByte != newByte;
            String xor = oldByte == -1 || newByte == -1 ? "--"
                    : String.format(Locale.ROOT, "%02X", oldByte ^ newByte);
            lines.add(String.format(Locale.ROOT, "    %s  %-12s %,11d   %-24s %-26s %s",
                    changed ? "*" : " ", hexOffset(offset), offset,
                    byteDescription(oldByte), byteDescription(newByte), xor));
        }
    }

    private static int readByte(FileChannel channel, ByteBuffer buffer, long offset) throws IOException {
        if (offset >= channel.size()) return -1;
        buffer.clear();
        int read = channel.read(buffer, offset);
        if (read < 1) return -1;
        return Byte.toUnsignedInt(buffer.array()[0]);
    }

    private static String byteDescription(int value) {
        if (value == -1) return "--  ---  <missing>";
        String character = value >= 32 && value <= 126 ? "'" + (char) value + "'" : ".";
        return String.format(Locale.ROOT, "%02X  %3d  %s", value, value, character);
    }

    private static String signed(long value) {
        return value > 0 ? "+" + value : Long.toString(value);
    }

    private static String percentage(long changed, long positions) {
        if (positions == 0) return "0.00%";
        return String.format(Locale.ROOT, "%.2f%% (%d/%d)", changed * 100.0 / positions, changed, positions);
    }

    private static void store(List<ChangedRange> ranges, RangeBuilder builder, int maximumStoredRanges) {
        if (ranges.size() < maximumStoredRanges) ranges.add(builder.build());
    }

    private static String rangeLabel(ChangedRange range) {
        long length = range.end() - range.start() + 1;
        if (range.start() == range.end()) {
            return "offset " + hexOffset(range.start()) + " (decimal " + range.start() + "), 1 byte";
        }
        return "offsets " + hexOffset(range.start()) + "–" + hexOffset(range.end())
                + " (decimal " + range.start() + "–" + range.end() + "), " + length + " bytes";
    }

    private static String sampleSuffix(ChangedRange range) {
        long length = range.end() - range.start() + 1;
        return length > SAMPLE_BYTES_PER_RANGE ? " … (first " + SAMPLE_BYTES_PER_RANGE + " shown)" : "";
    }

    private static String hexOffset(long offset) {
        return String.format(Locale.ROOT, "0x%08X", offset);
    }

    private static String byteToken(int value) {
        return value == -1 ? "--" : String.format(Locale.ROOT, "%02X", value);
    }

    private static final class RangeBuilder {
        private final long start;
        private long end;
        private final List<String> originalSample = new ArrayList<>();
        private final List<String> comparisonSample = new ArrayList<>();

        private RangeBuilder(long start) {
            this.start = start;
        }

        private void append(long offset, int oldByte, int newByte) {
            end = offset;
            if (originalSample.size() < SAMPLE_BYTES_PER_RANGE) {
                originalSample.add(byteToken(oldByte));
                comparisonSample.add(byteToken(newByte));
            }
        }

        private ChangedRange build() {
            return new ChangedRange(start, end, List.copyOf(originalSample), List.copyOf(comparisonSample));
        }
    }

    private record ChangedRange(long start, long end, List<String> originalSample, List<String> comparisonSample) { }
}
