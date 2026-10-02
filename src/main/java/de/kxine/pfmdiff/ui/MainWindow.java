package de.kxine.pfmdiff.ui;

import de.kxine.pfmdiff.AppService;
import de.kxine.pfmdiff.compare.ComparisonEngine;
import de.kxine.pfmdiff.model.ComparisonReport;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

public final class MainWindow extends JFrame {
    private final JTextField originalField = new JTextField();
    private final JTextField comparisonField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JButton startButton = new JButton("Create report");
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton openButton = new JButton("Open report");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel("Select two directories and a report destination.");
    private SwingWorker<ComparisonReport, ProgressUpdate> worker;
    private Path completedReport;

    public MainWindow() {
        super("PFM Diff Checker");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(720, 390));
        setSize(840, 430);
        setLocationByPlatform(true);
        createUi();
    }

    private void createUi() {
        JPanel root = new JPanel(new BorderLayout(12, 18));
        root.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));

        JLabel heading = new JLabel("PFM Directory Difference Report");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 22f));
        JLabel description = new JLabel("Compare binaries, XML documents, and PDF text entirely offline.");
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.add(heading, BorderLayout.NORTH);
        header.add(description, BorderLayout.SOUTH);
        root.add(header, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(6, 4, 6, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.gridy = 0;
        addPathRow(form, constraints, "Original directory", originalField, true);
        constraints.gridy++;
        addPathRow(form, constraints, "Comparison directory", comparisonField, true);
        constraints.gridy++;
        addPathRow(form, constraints, "HTML report", outputField, false);
        root.add(form, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        cancelButton.setEnabled(false);
        openButton.setEnabled(false);
        startButton.addActionListener(event -> startComparison());
        cancelButton.addActionListener(event -> cancelComparison());
        openButton.addActionListener(event -> openReport());
        buttons.add(openButton);
        buttons.add(cancelButton);
        buttons.add(startButton);

        progress.setStringPainted(true);
        progress.setString("");
        JPanel footer = new JPanel(new BorderLayout(8, 10));
        footer.add(status, BorderLayout.NORTH);
        footer.add(progress, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private void addPathRow(JPanel panel, GridBagConstraints constraints, String label, JTextField field, boolean directory) {
        constraints.gridx = 0;
        constraints.weightx = 0;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(field, constraints);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(event -> choosePath(field, directory));
        constraints.gridx = 2;
        constraints.weightx = 0;
        panel.add(browse, constraints);
    }

    private void choosePath(JTextField target, boolean directory) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        if (!target.getText().isBlank()) chooser.setSelectedFile(new File(target.getText()));
        if (!directory) {
            chooser.setDialogTitle("Choose HTML report location");
            if (target.getText().isBlank()) chooser.setSelectedFile(new File("pfm-diff-report.html"));
        }
        int result = directory ? chooser.showOpenDialog(this) : chooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) target.setText(chooser.getSelectedFile().getAbsolutePath());
    }

    private void startComparison() {
        Path original;
        Path comparison;
        Path output;
        try {
            original = requiredPath(originalField, "original directory");
            comparison = requiredPath(comparisonField, "comparison directory");
            output = requiredPath(outputField, "HTML report").toAbsolutePath().normalize();
            if (!Files.isDirectory(original)) throw new IllegalArgumentException("The original path is not a directory.");
            if (!Files.isDirectory(comparison)) throw new IllegalArgumentException("The comparison path is not a directory.");
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Invalid input", JOptionPane.WARNING_MESSAGE);
            return;
        }
        boolean overwrite = Files.exists(output);
        if (overwrite) {
            int choice = JOptionPane.showConfirmDialog(this, "Replace the existing report?\n" + output,
                    "Confirm overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }

        completedReport = null;
        setRunning(true);
        progress.setIndeterminate(true);
        progress.setString("Scanning…");
        status.setText("Comparing directory trees…");
        worker = new SwingWorker<>() {
            @Override
            protected ComparisonReport doInBackground() throws Exception {
                return new AppService().compareAndWrite(original, comparison, output,
                        (completed, total, path) -> publish(new ProgressUpdate(completed, total, path)),
                        this::isCancelled, overwrite);
            }

            @Override
            protected void process(List<ProgressUpdate> updates) {
                ProgressUpdate update = updates.get(updates.size() - 1);
                progress.setIndeterminate(update.total == 0);
                progress.setMaximum(Math.max(1, update.total));
                progress.setValue(update.completed);
                progress.setString(update.total == 0 ? "Scanning…" : update.completed + " / " + update.total);
                status.setText(shorten(update.path, 100));
            }

            @Override
            protected void done() {
                setRunning(false);
                try {
                    ComparisonReport report = get();
                    completedReport = output;
                    openButton.setEnabled(Desktop.isDesktopSupported());
                    progress.setValue(progress.getMaximum());
                    progress.setString("Complete");
                    String outcome = report.hasErrors() ? "Completed with errors"
                            : report.hasSkipped() ? "Incomplete: symbolic links skipped"
                            : report.hasDifferences() ? "Differences found" : "Directories are identical";
                    status.setText(outcome + ". Report: " + output);
                    JOptionPane.showMessageDialog(MainWindow.this,
                            outcome + ".\n\nThe HTML report was written to:\n" + output,
                            "Comparison complete", report.hasErrors() || report.hasSkipped()
                                    ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
                } catch (CancellationException e) {
                    progress.setString("Cancelled");
                    status.setText("Comparison cancelled; no report was written.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    progress.setString("Cancelled");
                    status.setText("Comparison interrupted; no report was written.");
                } catch (ExecutionException e) {
                    progress.setString("Failed");
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    status.setText("Comparison failed.");
                    JOptionPane.showMessageDialog(MainWindow.this, message(cause), "Comparison failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private void cancelComparison() {
        if (worker != null) worker.cancel(true);
    }

    private void openReport() {
        if (completedReport == null || !Desktop.isDesktopSupported()) return;
        try {
            Desktop.getDesktop().browse(completedReport.toUri());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, message(e), "Could not open report", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void setRunning(boolean running) {
        startButton.setEnabled(!running);
        cancelButton.setEnabled(running);
        originalField.setEnabled(!running);
        comparisonField.setEnabled(!running);
        outputField.setEnabled(!running);
        if (running) openButton.setEnabled(false);
    }

    private static Path requiredPath(JTextField field, String name) {
        if (field.getText().isBlank()) throw new IllegalArgumentException("Please select the " + name + ".");
        return Path.of(field.getText().trim());
    }

    private static String shorten(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return "…" + value.substring(value.length() - max + 1);
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private record ProgressUpdate(int completed, int total, String path) { }
}
