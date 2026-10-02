package de.kxine.pfmdiff;

import de.kxine.pfmdiff.model.ComparisonReport;
import de.kxine.pfmdiff.ui.MainWindow;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        if (args.length == 0 && !GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
            return;
        }
        int exit = runCli(args);
        if (exit != 0) System.exit(exit);
    }

    static int runCli(String[] args) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            usage();
            return 2;
        }
        if (options.help()) {
            usage();
            return 0;
        }
        Path output = options.output().toAbsolutePath().normalize();
        if (Files.exists(output) && !options.overwrite()) {
            System.err.println("Error: report already exists; use --overwrite to replace it: " + output);
            return 2;
        }
        try {
            System.out.println("Comparing directory trees…");
            ComparisonReport report = new AppService().compareAndWrite(
                     options.original(), options.comparison(), output,
                    (completed, total, path) -> {
                        if (total > 0 && (completed == total || completed % 100 == 0)) {
                            System.out.printf("Processed %d/%d entries%n", completed, total);
                        }
                     }, () -> false, options.overwrite());
            System.out.println("Report written to " + output);
            if (report.hasErrors()) {
                System.err.println("Comparison completed with file-processing errors; see the report.");
                return 2;
            }
            if (report.hasSkipped()) {
                System.err.println("Comparison incomplete: symbolic links were skipped; see the report.");
                return 2;
            }
            if (report.hasDifferences()) {
                System.out.println("Differences found.");
                return 1;
            }
            System.out.println("The directory trees are identical.");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + errorMessage(e));
            return 2;
        }
    }

    private static void usage() {
        System.out.println("""
                PFM Diff Checker
                Usage:
                  java -jar pfm-diff-checker.jar --original <directory> --compare <directory> --output <report.html> [--overwrite]

                Run without arguments in a desktop environment to open the graphical interface.
                Exit codes: 0 identical, 1 differences, 2 error or incomplete comparison.
                """);
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
