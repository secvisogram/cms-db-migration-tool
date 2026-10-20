package de.bsi.secvisogram.csafmigrator;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses {@code --key=value} CLI arguments. Deliberately not a Spring
 * {@code @ConfigurationProperties} class -- there's no Spring context in this tool at all.
 */
public class MigrationConfig {

    public final String couchdbUrl;
    public final String couchdbUser;
    public final String couchdbPassword;
    public final String pgUrl;
    public final String pgUser;
    public final String pgPassword;
    /** Load the valid documents and skip malformed ones (the run still exits non-zero) instead of
     *  aborting before anything is written. */
    public final boolean skipMalformed;
    /** Read and validate only; never connect to PostgreSQL. */
    public final boolean dryRun;
    /** Documents fetched from CouchDB per request; bounds how many full documents are in memory. */
    public final int pageSize;
    /** Validate by reading the whole documents instead of a small projection (slow, for comparison). */
    public final boolean fullValidationRead;

    /** Direct construction, e.g. from a test wiring up Testcontainers endpoints -- no CLI
     *  parsing involved. */
    public MigrationConfig(String couchdbUrl, String couchdbUser, String couchdbPassword,
                            String pgUrl, String pgUser, String pgPassword) {
        this(couchdbUrl, couchdbUser, couchdbPassword, pgUrl, pgUser, pgPassword, false);
    }

    public MigrationConfig(String couchdbUrl, String couchdbUser, String couchdbPassword,
                            String pgUrl, String pgUser, String pgPassword, boolean skipMalformed) {
        this(couchdbUrl, couchdbUser, couchdbPassword, pgUrl, pgUser, pgPassword, skipMalformed, false);
    }

    public MigrationConfig(String couchdbUrl, String couchdbUser, String couchdbPassword,
                            String pgUrl, String pgUser, String pgPassword, boolean skipMalformed, boolean dryRun) {
        this(couchdbUrl, couchdbUser, couchdbPassword, pgUrl, pgUser, pgPassword, skipMalformed, dryRun,
                CouchDbClient.DEFAULT_PAGE_SIZE);
    }

    public MigrationConfig(String couchdbUrl, String couchdbUser, String couchdbPassword,
                            String pgUrl, String pgUser, String pgPassword, boolean skipMalformed, boolean dryRun,
                            int pageSize) {
        this(couchdbUrl, couchdbUser, couchdbPassword, pgUrl, pgUser, pgPassword, skipMalformed, dryRun, pageSize, false);
    }

    public MigrationConfig(String couchdbUrl, String couchdbUser, String couchdbPassword,
                            String pgUrl, String pgUser, String pgPassword, boolean skipMalformed, boolean dryRun,
                            int pageSize, boolean fullValidationRead) {
        if (pageSize < 1) {
            throw usage("--page-size must be a positive number");
        }
        this.pageSize = pageSize;
        this.fullValidationRead = fullValidationRead;
        this.skipMalformed = skipMalformed;
        this.dryRun = dryRun;
        this.couchdbUrl = couchdbUrl;
        this.couchdbUser = couchdbUser;
        this.couchdbPassword = couchdbPassword;
        this.pgUrl = pgUrl;
        this.pgUser = pgUser;
        this.pgPassword = pgPassword;
    }

    private MigrationConfig(Map<String, String> args) {
        this(require(args, "couchdb-url"), require(args, "couchdb-user"), require(args, "couchdb-password"),
                requireUnlessDryRun(args, "pg-url"), requireUnlessDryRun(args, "pg-user"),
                requireUnlessDryRun(args, "pg-password"),
                args.containsKey("skip-malformed"), args.containsKey("dry-run"), pageSize(args),
                args.containsKey("full-validation-read"));
    }

    public static MigrationConfig parse(String[] argv) {
        Map<String, String> args = new HashMap<>();
        for (String arg : argv) {
            if (arg.equals("--skip-malformed") || arg.equals("--dry-run") || arg.equals("--full-validation-read")) {
                args.put(arg.substring(2), "true");
                continue;
            }
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw usage("Unrecognized argument: " + arg);
            }
            int eq = arg.indexOf('=');
            args.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        return new MigrationConfig(args);
    }

    private static int pageSize(Map<String, String> args) {
        String value = args.get("page-size");
        if (value == null) {
            return CouchDbClient.DEFAULT_PAGE_SIZE;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw usage("--page-size must be a positive number, got: " + value);
        }
    }

    private static String requireUnlessDryRun(Map<String, String> args, String key) {
        return args.containsKey("dry-run") ? args.get(key) : require(args, key);
    }

    private static String require(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            throw usage("Missing required argument: --" + key);
        }
        return value;
    }

    private static IllegalArgumentException usage(String message) {
        return new IllegalArgumentException(message + """


                Usage:
                  java -jar csaf-couchdb-postgres-migrator.jar \\
                    --couchdb-url=https://old-host:5984/csaf \\
                    --couchdb-user=admin \\
                    --couchdb-password=admin \\
                    --pg-url=jdbc:postgresql://new-host:5432/csaf \\
                    --pg-user=csaf \\
                    --pg-password=secret \\
                    [--skip-malformed] [--dry-run] [--page-size=500] [--full-validation-read]

                By default any malformed document aborts the run before anything is written.
                --dry-run only reads CouchDB and validates; the --pg-* arguments are then not needed.
                --skip-malformed loads the valid documents and reports the rest (exit code stays 1).
                --page-size is how many documents are read from CouchDB per request (default 500).
                Lower it if the documents are large and the tool runs out of memory.
                --full-validation-read validates by reading the whole documents. By default only the small
                fields are read, which is many times faster on large advisories.
                """);
    }
}
