package dev.blockboost.client.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;

import dev.blockboost.BlockBoost;

/**
 * Writes reports and CSV sample files into the game directory.
 *
 * <p>Local files only — {@code run/blockboost/}. There is deliberately no HTTP
 * client anywhere in this mod.
 */
public final class ReportWriter {

    public Path outputDir() {
        return FabricLoader.getInstance().getGameDir().resolve("blockboost");
    }

    public String writeReport(List<String> lines) {
        try {
            Files.createDirectories(outputDir());
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            Path file = outputDir().resolve("report-" + stamp + ".txt");
            Files.write(file, lines, StandardCharsets.UTF_8);
            BlockBoost.logInfo("wrote diagnostics report to " + file);
            return file.toString();
        } catch (IOException e) {
            BlockBoost.LOG.error("[BlockBoost] could not write the diagnostics report", e);
            return "write failed: " + e.getMessage();
        }
    }

    /**
     * @param header CSV header, one cell per sample column
     * @param rows   rows of already-formatted cells; numbers use '.' regardless of locale
     */
    public String writeCsv(String kind, List<String> header, List<List<String>> rows) {
        try {
            Files.createDirectories(outputDir());
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            Path file = outputDir().resolve(kind + "-" + stamp + ".csv");
            StringBuilder sb = new StringBuilder(rows.size() * 48 + 128);
            sb.append(String.join(",", header)).append('\n');
            for (List<String> row : rows) {
                sb.append(String.join(",", row)).append('\n');
            }
            Files.writeString(file, sb, StandardCharsets.UTF_8);
            // Also keep a stable "latest" copy so external tooling and CI can find it
            // without parsing a timestamp.
            Files.writeString(outputDir().resolve(kind + "-latest.csv"), sb, StandardCharsets.UTF_8);
            BlockBoost.logInfo("wrote " + rows.size() + " samples to " + file.getFileName());
            return file.toString();
        } catch (IOException e) {
            BlockBoost.LOG.error("[BlockBoost] could not write the sample CSV", e);
            return "write failed: " + e.getMessage();
        }
    }

    /** Plain text artifact used by the benchmark harness and the launch test. */
    public String writeText(String name, String content) {
        try {
            Files.createDirectories(outputDir());
            Path file = outputDir().resolve(name);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file.toString();
        } catch (IOException e) {
            BlockBoost.LOG.warn("[BlockBoost] could not write {}", name, e);
            return "write failed: " + e.getMessage();
        }
    }
}
