package de.kxine.pfmdiff;

import de.kxine.pfmdiff.compare.ComparisonEngine;
import de.kxine.pfmdiff.model.ComparisonReport;
import de.kxine.pfmdiff.report.HtmlReportWriter;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

public final class AppService {
    private final ComparisonEngine engine = new ComparisonEngine();
    private final HtmlReportWriter reportWriter = new HtmlReportWriter();

    public ComparisonReport compareAndWrite(Path original, Path comparison, Path output,
                                            ComparisonEngine.ProgressListener progress,
                                            BooleanSupplier cancelled) throws Exception {
        ComparisonReport report = engine.compare(new ComparisonEngine.Config(original, comparison, output), progress, cancelled);
        if (cancelled != null && cancelled.getAsBoolean()) throw new ComparisonEngine.ComparisonCancelledException();
        reportWriter.write(report, output);
        return report;
    }
}
