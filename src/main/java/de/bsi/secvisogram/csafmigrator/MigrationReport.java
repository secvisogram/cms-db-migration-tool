package de.bsi.secvisogram.csafmigrator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks per-type source counts vs. inserted counts, and any docs that couldn't be classified
 * or written, so a mismatch is loud rather than a silent partial migration.
 */
public class MigrationReport {

    private final Map<String, Integer> sourceCounts = new LinkedHashMap<>();
    private final Map<String, Integer> insertedCounts = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();

    public void recordSource(String type, int count) {
        sourceCounts.put(type, count);
    }

    public void recordInserted(String type, int count) {
        insertedCounts.merge(type, count, Integer::sum);
    }

    public void recordError(String docId, String message) {
        String key = docId;
        for (int n = 2; errors.containsKey(key); n++) {
            key = docId + " #" + n;
        }
        errors.put(key, message);
    }

    public int errorCount() {
        return errors.size();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public boolean hasMismatches() {
        for (Map.Entry<String, Integer> entry : sourceCounts.entrySet()) {
            if (!entry.getValue().equals(insertedCounts.getOrDefault(entry.getKey(), 0))) {
                return true;
            }
        }
        return !errors.isEmpty();
    }

    public void print() {
        System.out.println();
        System.out.println("=== Migration report ===");
        System.out.printf("%-20s %10s %10s%n", "Type", "Source", "Inserted");
        for (String type : sourceCounts.keySet()) {
            int source = sourceCounts.get(type);
            int inserted = insertedCounts.getOrDefault(type, 0);
            String flag = source == inserted ? "" : "  <-- MISMATCH";
            System.out.printf("%-20s %10d %10d%s%n", type, source, inserted, flag);
        }
        if (!errors.isEmpty()) {
            System.out.println();
            System.out.println("Errors (" + errors.size() + "):");
            errors.forEach((id, message) -> System.out.println("  " + id + ": " + message));
        }
        System.out.println();
        System.out.println(hasMismatches()
                ? "RESULT: mismatches found -- do not trust this run, investigate before cutover."
                : "RESULT: all counts match.");
    }
}
