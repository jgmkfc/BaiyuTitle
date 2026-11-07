package net.baiyu.title.config;

import net.baiyu.title.config.exception.ConfigValidationException;
import net.baiyu.title.config.migration.Migration;
import net.baiyu.title.database.handler.DatabaseType;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.interfaces.meta.Exclude;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

import java.util.HashMap;
import java.util.Map;

@ConfigSerializable
public class DatabaseConfig implements VersionedConfig {
    @Comment("Do not change this value!")
    public int configVersion = 1;

    @Override
    @Exclude
    public int configVersion() {
        return configVersion;
    }

    @Override
    @Exclude
    public @NotNull Map<Integer, Migration> migrations() {
        return Map.of();
    }

    @Override
    @Exclude
    public void validate() throws ConfigValidationException {
    }

    @Comment("Database Settings")
    public Database database = new Database();

    @ConfigSerializable
    public static class Database {
        @Comment("Available types: \"sqlite\", \"h2\", \"mysql\", \"mariadb\"")
        public DatabaseType type = DatabaseType.SQLITE;
        public String tablePrefix = "";

        @Comment("Authentication")
        public String host = "localhost";
        public int port = 3306;
        public String database = "database_name";
        public String username = "root";
        public String password = "";

        @Comment("Advanced settings (Please don't touch unless you know what you're doing!)")
        public Advanced advanced = new Advanced();

        @ConfigSerializable
        public static class Advanced {
            @Comment("Should we try to repair broken migrations to the database")
            public boolean repair = false;

            @Comment("Configure the HikariCP connection pool")
            public ConnectionPool connectionPool = new ConnectionPool();

            @Comment("SSL/TLS configuration for the database connection.\nOnly applies to MySQL and MariaDB; ignored for SQLite and H2.")
            public SSL ssl = new SSL();

            @ConfigSerializable
            public static class SSL {
                @Comment("Enable TLS/SSL for the database connection")
                public boolean enabled = false;

                @Comment(
                    "How to verify the server's identity:\n" +
                        "  \"require\"          encrypt without validating the server certificate\n" +
                        "  \"verify-ca\"        encrypt and verify the server certificate against the configured CA\n" +
                        "  \"verify-identity\"  additionally check that the hostname matches the certificate\n" +
                        "Note: when this section is enabled, any useSSL property in connection-properties is ignored."
                )
                public String mode = "require";

                @Comment("Path to the Certificate Authority certificate in PEM format.\nRequired when mode is \"verify-ca\" or \"verify-identity\".")
                public String caPath = "";

                @Comment("Path to the client certificate in PEM format.\nOnly required for mutual TLS (mTLS).")
                public String certPath = "";

                @Comment(
                    "Path to the client private key in PKCS#8 PEM format.\n" +
                        "Only required for mutual TLS alongside cert-path.\n" +
                        "Convert a PKCS#1 key with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pkcs8.pem"
                )
                public String keyPath = "";
            }

            @ConfigSerializable
            public static class ConnectionPool {
                public int maxpoolsize = 10;
                public int minidle = 10;
                public long maxlifetime = 180000;
                public long keepalivetime = 60000;
                public long connectiontimeout = 20000;
            }

            @Comment("A list of connection parameters, you can include more by adding them on a new line")
            public Map<String, Object> connectionProperties = defaultValues();

            private Map<String, Object> defaultValues() {
                final Map<String, Object> map = new HashMap<>();
                map.put("useSSL", false);
                map.put("cachePrepStmts", true);
                map.put("prepStmtCacheSize", 250);
                map.put("prepStmtCacheSqlLimit", 2048);
                map.put("useServerPrepStmts", true);
                map.put("useLocalSessionState", true);
                map.put("rewriteBatchedStatements", true);
                map.put("cacheResultSetMetadata", true);
                map.put("cacheServerConfiguration", true);
                map.put("elideSetAutoCommits", true);
                map.put("maintainTimeStats", false);
                return map;
            }
        }
    }
}
