# PFM Diff Checker

An offline Java 17 application that compares two directory trees and writes a self-contained HTML report. XML files receive exact and structural comparisons, PDFs receive exact and extracted-text comparisons, and all other files are treated as proprietary binaries.

## Build

```bash
mvn clean package
```

The executable JAR is `target/pfm-diff-checker.jar` and includes all dependencies.

## Use

Double-click the JAR (where supported), or launch the desktop interface with:

```bash
java -jar target/pfm-diff-checker.jar
```

For command-line use:

```bash
java -jar target/pfm-diff-checker.jar \
  --original /path/to/original \
  --compare /path/to/comparison \
  --output /path/to/report.html
```

Add `--overwrite` to replace an existing report. Exit code `0` means identical, `1` means differences were found, and `2` means an error or incomplete comparison (for example, skipped symbolic links). A report is still written after individual file errors whenever possible.

Save the report **outside both directories being compared** to prevent it from replacing a source file. XML structural comparison is limited to 16 MiB per file and PDF extracted text to 16 Mi characters per file; files exceeding these limits appear as errors in the report. XML comparison ignores indented line-break formatting in element-only content, but preserves inline whitespace and combines adjacent text/CDATA nodes. Without a schema, whitespace significance is sometimes ambiguous.

The application does not use the network at runtime. Symbolic links are listed as skipped warnings and are never followed.

## Demo data

After building, generate the included demonstration data and report with:

```bash
java -cp target/pfm-diff-checker.jar tools/GenerateDemoData.java
java -jar target/pfm-diff-checker.jar \
  --original demo-data/original \
  --compare demo-data/comparison \
  --output demo-data/example-report.html
```

The second command exits with code `1`, which is the expected result because the demo contains differences.
