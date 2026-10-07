package de.bsi.secvisogram.csafmigrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateTrackingIdCheckTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode advisory(String id, String trackingId) throws Exception {
        return MAPPER.readTree("""
                {"_id": "%s", "type": "Advisory",
                 "csaf": {"document": {"tracking": {"id": "%s"}}}}
                """.formatted(id, trackingId));
    }

    @Test
    void reportsAdvisoriesSharingATrackingId() throws Exception {
        MigrationReport report = new MigrationReport();
        Migrator.checkDuplicateTrackingIds(List.of(advisory("a", "X-1"), advisory("b", "X-1")), report);
        assertTrue(report.hasErrors());
    }

    @Test
    void acceptsDistinctTrackingIds() throws Exception {
        MigrationReport report = new MigrationReport();
        Migrator.checkDuplicateTrackingIds(List.of(advisory("a", "X-1"), advisory("b", "X-2")), report);
        assertFalse(report.hasErrors());
    }
}
