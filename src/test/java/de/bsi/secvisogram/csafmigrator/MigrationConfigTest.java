package de.bsi.secvisogram.csafmigrator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationConfigTest {

    private static final String[] DRY_RUN = {
            "--dry-run", "--couchdb-url=http://c:5984/db", "--couchdb-user=u", "--couchdb-password=p"};

    private static String[] withArg(String arg) {
        String[] argv = java.util.Arrays.copyOf(DRY_RUN, DRY_RUN.length + 1);
        argv[DRY_RUN.length] = arg;
        return argv;
    }

    @Test
    void pageSizeDefaultsTo500() {
        assertEquals(500, MigrationConfig.parse(DRY_RUN).pageSize);
    }

    @Test
    void pageSizeCanBeSet() {
        assertEquals(50, MigrationConfig.parse(withArg("--page-size=50")).pageSize);
    }

    @Test
    void rejectsInvalidPageSize() {
        assertThrows(IllegalArgumentException.class, () -> MigrationConfig.parse(withArg("--page-size=0")));
        assertThrows(IllegalArgumentException.class, () -> MigrationConfig.parse(withArg("--page-size=-5")));
        assertThrows(IllegalArgumentException.class, () -> MigrationConfig.parse(withArg("--page-size=abc")));
    }
}
