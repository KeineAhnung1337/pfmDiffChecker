package de.kxine.pfmdiff;

import de.kxine.pfmdiff.compare.ComparisonEngine;
import de.kxine.pfmdiff.model.ComparisonReport;
import de.kxine.pfmdiff.report.HtmlReportWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

public final class AppService {
    private final ComparisonEngine engine = new ComparisonEngine();
    private final HtmlReportWriter reportWriter = new HtmlReportWriter();

    public ComparisonReport compareAndWrite(Path original, Path comparison, Path output,
                                            ComparisonEngine.ProgressListener progress,
                                            BooleanSupplier cancelled) throws Exception {
        return compareAndWrite(original, comparison, output, progress, cancelled, false);
    }

    public ComparisonReport compareAndWrite(Path original, Path comparison, Path output,
                                            ComparisonEngine.ProgressListener progress,
                                            BooleanSupplier cancelled, boolean overwrite) throws Exception {
        validateOutput(original, comparison, output);
        ComparisonReport report = engine.compare(new ComparisonEngine.Config(original, comparison, output), progress, cancelled);
        if (cancelled != null && cancelled.getAsBoolean()) throw new ComparisonEngine.ComparisonCancelledException();
        reportWriter.write(report, output, overwrite);
        return report;
    }

    private static void validateOutput(Path original, Path comparison, Path output) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path ancestor = absolute;
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (ancestor == null) throw new IOException("Cannot resolve the report destination: " + absolute);
        Path resolved = ancestor.toRealPath().resolve(ancestor.relativize(absolute));
        for (Path root : new Path[]{original, comparison}) {
            if (root != null && Files.isDirectory(root) && resolved.startsWith(root.toRealPath())) {
                throw new IOException("Report destination must be outside both compared directories: " + absolute);
            }
        }
    }
}
