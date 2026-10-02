package de.kxine.pfmdiff.report;

import de.kxine.pfmdiff.model.ComparisonReport;
import de.kxine.pfmdiff.model.EntryResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class HtmlReportWriter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());

    public void write(ComparisonReport report, Path output) throws IOException {
        write(report, output, false);
    }

    public void write(ComparisonReport report, Path output, boolean overwrite) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("The report output needs a parent directory.");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".pfm-diff-", ".html.tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writeDocument(writer, report);
            }
            if (overwrite) {
                try {
                    Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                // Do not use ATOMIC_MOVE here: its behavior when the target exists is provider-specific.
                Files.move(temporary, absolute);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeDocument(BufferedWriter out, ComparisonReport report) throws IOException {
        out.write("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
                <title>PFM Difference Report</title>
                <style>
                :root{color-scheme:light dark;--bg:#f5f7fb;--panel:#fff;--text:#18212f;--muted:#607086;--border:#d8e0ea;--accent:#2459d3;--same:#26734d;--changed:#a15c00;--added:#087f5b;--removed:#b42318;--error:#b42318}
                @media(prefers-color-scheme:dark){:root{--bg:#111720;--panel:#1b2430;--text:#eef3f8;--muted:#aebdcb;--border:#354354;--accent:#8eb4ff;--same:#70d7a4;--changed:#ffc36b;--added:#65d6ad;--removed:#ff938b;--error:#ff938b}}
                *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.45 system-ui,sans-serif}main{max-width:1400px;margin:auto;padding:28px}h1{margin:0 0 4px;font-size:26px}.sub,.meta{color:var(--muted)}.roots{margin:20px 0;padding:14px;background:var(--panel);border:1px solid var(--border);border-radius:9px}.roots div{display:grid;grid-template-columns:100px 1fr;gap:8px;margin:3px 0}.path,code,pre{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;overflow-wrap:anywhere}.cards{display:grid;grid-template-columns:repeat(6,minmax(100px,1fr));gap:10px;margin:18px 0}.card{background:var(--panel);border:1px solid var(--border);border-radius:9px;padding:13px}.card b{font-size:22px;display:block}.controls{display:flex;gap:10px;flex-wrap:wrap;margin:18px 0}.controls input,.controls select{background:var(--panel);color:var(--text);border:1px solid var(--border);border-radius:7px;padding:9px 11px}.controls input{min-width:300px;flex:1}table{width:100%;border-collapse:separate;border-spacing:0;background:var(--panel);border:1px solid var(--border);border-radius:9px;overflow:hidden}th,td{text-align:left;padding:10px;border-bottom:1px solid var(--border);vertical-align:top}th{position:sticky;top:0;background:var(--panel);color:var(--muted);font-size:12px;text-transform:uppercase}tr:last-child td{border-bottom:0}.status{font-weight:700}.identical{color:var(--same)}.changed{color:var(--changed)}.added{color:var(--added)}.removed,.error{color:var(--error)}.skipped{color:var(--muted)}.technical{font-size:12px;color:var(--muted)}details{margin-top:7px}summary{cursor:pointer;color:var(--accent)}pre{white-space:pre-wrap;background:var(--bg);border:1px solid var(--border);border-radius:6px;padding:10px;max-height:440px;overflow:auto}.minus{color:var(--removed)}.plus{color:var(--added)}.errorbox{color:var(--error);font-weight:600;margin-top:5px}.empty{text-align:center;color:var(--muted);padding:30px}@media(max-width:800px){main{padding:15px}.cards{grid-template-columns:repeat(2,1fr)}th:nth-child(3),td:nth-child(3){display:none}.roots div{display:block}}
                </style></head><body><main>
                <h1>PFM Difference Report</h1>
                """);
        out.write("<div class=\"sub\">Generated " + html(TIME.format(report.createdAt())) + "</div>");
        out.write("<section class=\"roots\"><div><b>Original</b><span class=\"path\">" + html(report.originalRoot().toString())
                + "</span></div><div><b>Comparison</b><span class=\"path\">" + html(report.comparisonRoot().toString()) + "</span></div></section>");
        out.write("<section class=\"cards\">");
        card(out, "Identical", report.count(EntryResult.Status.IDENTICAL), "identical");
        card(out, "Changed", report.count(EntryResult.Status.CHANGED), "changed");
        card(out, "Added", report.count(EntryResult.Status.ADDED), "added");
        card(out, "Removed", report.count(EntryResult.Status.REMOVED), "removed");
        card(out, "Errors", report.count(EntryResult.Status.ERROR), "error");
        card(out, "Skipped", report.count(EntryResult.Status.SKIPPED), "skipped");
        out.write("</section><div class=\"controls\"><input id=\"search\" type=\"search\" placeholder=\"Search paths and details…\" aria-label=\"Search\"><select id=\"filter\" aria-label=\"Filter by status\"><option value=\"\">All statuses</option>");
        for (EntryResult.Status status : EntryResult.Status.values()) {
            out.write("<option value=\"" + status.name().toLowerCase(Locale.ROOT) + "\">" + title(status.name()) + "</option>");
        }
        out.write("</select><span id=\"visible\" class=\"meta\"></span></div>");
        out.write("<table><thead><tr><th>Status</th><th>Path</th><th>Kind</th><th>Comparison</th></tr></thead><tbody id=\"results\">");
        for (EntryResult entry : report.entries()) writeEntry(out, entry);
        out.write("</tbody></table><div id=\"empty\" class=\"empty\" hidden>No matching entries.</div>");
        out.write("""
                <script>
                const q=document.querySelector('#search'),f=document.querySelector('#filter'),rows=document.querySelectorAll('#results tr'),count=document.querySelector('#visible'),empty=document.querySelector('#empty');
                function apply(){const term=q.value.toLocaleLowerCase(),status=f.value;let shown=0;for(const row of rows){const yes=(!status||row.dataset.status===status)&&(!term||row.textContent.toLocaleLowerCase().includes(term));row.hidden=!yes;if(yes)shown++}count.textContent=shown+' of '+rows.length+' entries';empty.hidden=shown!==0}q.addEventListener('input',apply);f.addEventListener('change',apply);apply();
                </script></main></body></html>
                """);
    }

    private void writeEntry(BufferedWriter out, EntryResult entry) throws IOException {
        String status = entry.status().name().toLowerCase(Locale.ROOT);
        out.write("<tr data-status=\"" + status + "\">");
        out.write("<td><span class=\"status " + status + "\">" + title(entry.status().name()) + "</span></td>");
        out.write("<td class=\"path\">" + html(entry.relativePath()) + "</td>");
        out.write("<td>" + title(entry.entryType().name()));
        if (entry.fileKind() != EntryResult.FileKind.NOT_APPLICABLE) out.write(" · " + entry.fileKind());
        out.write("</td><td>");
        if (entry.originalSize() != null || entry.comparisonSize() != null) {
            out.write("<div>Size: " + size(entry.originalSize()) + " → " + size(entry.comparisonSize()) + "</div>");
        }
        if (entry.semanticEqual() != null && entry.fileKind() != EntryResult.FileKind.BINARY) {
            String name = entry.fileKind() == EntryResult.FileKind.XML ? "Structure" : "Extracted text";
            out.write("<div>" + name + ": <b>" + (entry.semanticEqual() ? "equal" : "different") + "</b></div>");
        }
        if (entry.originalHash() != null || entry.comparisonHash() != null) {
            out.write("<details><summary>SHA-256 hashes</summary><div class=\"technical path\">Original: " + html(orDash(entry.originalHash()))
                    + "<br>Comparison: " + html(orDash(entry.comparisonHash())) + "</div></details>");
        }
        if (entry.error() != null) out.write("<div class=\"errorbox\">" + html(entry.error()) + "</div>");
        if (!entry.details().isEmpty()) {
            out.write("<details><summary>Difference details (" + entry.details().size() + ")</summary><pre>");
            for (String detail : entry.details()) {
                String css = detail.startsWith("+ ") ? "plus" : detail.startsWith("- ") ? "minus" : "";
                out.write("<span" + (css.isEmpty() ? "" : " class=\"" + css + "\"") + ">" + html(detail) + "</span>\n");
            }
            out.write("</pre></details>");
        }
        out.write("</td></tr>");
    }

    private static void card(BufferedWriter out, String label, long value, String css) throws IOException {
        out.write("<div class=\"card\"><b class=\"" + css + "\">" + value + "</b><span>" + label + "</span></div>");
    }

    private static String size(Long value) {
        if (value == null) return "—";
        if (value < 1024) return value + " B";
        return String.format(Locale.ROOT, "%.1f KiB (%d B)", value / 1024.0, value);
    }

    private static String title(String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String orDash(String value) { return value == null ? "—" : value; }

    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

}
