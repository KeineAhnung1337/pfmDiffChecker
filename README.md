# PFM Diff Checker

PFM Diff Checker compares two directory trees offline and creates a self-contained HTML report. It runs as a desktop app or from the command line on Java 17. The report lists added, removed, changed, matching, skipped, and failed entries; it can be opened in a browser without a server or network connection.

## Requirements and build

- Java Development Kit (JDK) 17 or newer
- Maven 3.8 or newer (only needed to build)

```bash
mvn clean verify
```

The executable `target/pfm-diff-checker.jar` includes its dependencies. Maven needs access to dependencies when building for the first time; **the application itself does not use the network**.

## Run

Double-click the JAR where supported, or open the desktop interface with:

```bash
java -jar target/pfm-diff-checker.jar
```

Choose the original directory, the directory to compare it with, and an HTML report destination. The interface shows progress, allows cancellation, and can open the finished report.

For a command-line comparison:

```bash
java -jar target/pfm-diff-checker.jar \
  --original /path/to/original \
  --compare /path/to/comparison \
  --output /path/to/report.html
```

The output must be **outside both input directories**. An existing report is protected unless you pass `--overwrite` (or confirm replacement in the desktop interface). Use `--help` for a brief usage summary.

| Exit code | Meaning |
| --- | --- |
| `0` | All compared entries match; nothing was skipped. |
| `1` | Differences were found, with no skipped or failed entries. |
| `2` | Comparison incomplete or failed (including skipped links or file errors). Check the report if one was written. |

Individual file errors appear in the report whenever possible. If differences and skipped entries occur together, the result is incomplete (`2`), not a claim that all differences were found.

## What the report means

- **Exact comparison:** SHA-256 hashes detect changed file bytes. Sizes and hashes appear when available.
- **XML:** Files with different bytes are parsed and compared by element/attribute structure and text. Attribute order and indented formatting in element-only content are ignored; inline whitespace, text, and CDATA content are compared. Without a schema, whitespace significance is sometimes ambiguous. Structural comparison is limited to **16 MiB per file**.
- **PDF:** Files with different bytes are compared by extracted text on each page. Equal text does **not** mean identical images, layout, metadata, or visual appearance. Extraction is limited to **16 Mi characters per file**; image-only/scanned PDFs may have no extractable text.
- **Other files:** A positional binary comparison shows sizes, differing ranges, and short byte/context samples. Insertions may make later offsets differ even if content moves.
- **Directories and links:** A matching directory row means its path exists on both sides, **not** that all descendants match. Symbolic links are never followed; matching links are marked skipped and make the comparison incomplete.

The HTML report has an outcome summary, status and kind filters, searchable entries, expandable differences, and hash details. Search/filter controls require JavaScript; the report remains readable without it. Reports may include **absolute input paths, snippets of file contents, binary samples, error messages, and hashes**. Review them before sharing, even though the application is offline.

## Try the bundled example

After building, compare the included demo directories:

```bash
java -jar target/pfm-diff-checker.jar \
  --original demo-data/original \
  --compare demo-data/comparison \
  --output demo-data/my-report.html
```

The command exits with `1` because the demo has differences. Open `demo-data/my-report.html` in a browser; `demo-data/example-report.html` is a checked-in example with shortened input paths for portability. To recreate the input files, run:

```bash
java -cp target/pfm-diff-checker.jar tools/GenerateDemoData.java
```
