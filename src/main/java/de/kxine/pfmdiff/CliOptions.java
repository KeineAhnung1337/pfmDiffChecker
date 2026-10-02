package de.kxine.pfmdiff;

import java.nio.file.Path;

record CliOptions(Path original, Path comparison, Path output, boolean overwrite, boolean help) {
    static CliOptions parse(String[] args) {
        Path original = null;
        Path comparison = null;
        Path output = null;
        boolean overwrite = false;
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--original" -> original = value(args, ++i, "--original");
                case "--compare" -> comparison = value(args, ++i, "--compare");
                case "--output" -> output = value(args, ++i, "--output");
                case "--overwrite" -> overwrite = true;
                case "--help", "-h" -> help = true;
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        if (!help && (original == null || comparison == null || output == null)) {
            throw new IllegalArgumentException("--original, --compare and --output are required.");
        }
        return new CliOptions(original, comparison, output, overwrite, help);
    }

    private static Path value(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException("Missing value for " + option + ".");
        }
        return Path.of(args[index]);
    }
}
