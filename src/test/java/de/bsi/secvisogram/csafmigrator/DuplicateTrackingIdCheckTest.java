package de.bsi.secvisogram.csafmigrator;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateTrackingIdCheckTest {

    private static Map<String, String> trackingIds(String... idAndTrackingIdPairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < idAndTrackingIdPairs.length; i += 2) {
            map.put(idAndTrackingIdPairs[i], idAndTrackingIdPairs[i + 1]);
        }
        return map;
    }

    @Test
    void reportsAdvisoriesSharingATrackingId() {
        MigrationReport report = new MigrationReport();
        Migrator.checkDuplicateTrackingIds(trackingIds("a", "X-1", "b", "X-1"), report);
        assertTrue(report.hasErrors());
    }

    @Test
    void acceptsDistinctTrackingIds() {
        MigrationReport report = new MigrationReport();
        Migrator.checkDuplicateTrackingIds(trackingIds("a", "X-1", "b", "X-2"), report);
        assertFalse(report.hasErrors());
    }
}
