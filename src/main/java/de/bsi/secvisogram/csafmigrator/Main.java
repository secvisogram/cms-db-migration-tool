package de.bsi.secvisogram.csafmigrator;

/**
 * CLI entrypoint. All actual migration logic lives in {@link Migrator} so it can be called
 * directly from tests without going through argument parsing or {@code System.exit}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        MigrationConfig config;
        try {
            config = MigrationConfig.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        try {
            MigrationReport report = Migrator.migrate(config);
            report.print();
            if (report.hasMismatches()) {
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Migration failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
