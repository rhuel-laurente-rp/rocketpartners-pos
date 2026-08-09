package com.rocketpartners.onboarding.possystem.repository.h2;

import com.rocketpartners.onboarding.commons.model.Item;
import com.rocketpartners.onboarding.possystem.repository.ItemRepository;
import com.rocketpartners.onboarding.possystem.repository.PricebookTsv;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;

/**
 * H2-backed {@link ItemRepository}. Opens (or creates) a file-mode H2 database, ensures the
 * {@code ITEMS} table exists, and seeds it from a classpath pricebook the first time the table is
 * empty. Subsequent runs open the same file and skip seeding — the pricebook lives in the DB,
 * not in memory.
 *
 * <p>One JDBC connection is opened for the lifetime of the repository. H2 file DBs are
 * single-writer, and the POS makes read-mostly lookups from the EDT, so a single-connection
 * setup is both simpler than a pool and closer to the pattern being taught. Callers own the
 * repository's lifecycle — call {@link #close()} on shutdown.</p>
 *
 * <p>H2 lock files pin the DB to one process. If the POS is already running and a second launch
 * tries to open the same file, {@link #open} fails loudly rather than silently sharing state.</p>
 */
public class H2ItemRepository implements ItemRepository, AutoCloseable {

    private static final String SCHEMA_SQL =
            "CREATE TABLE IF NOT EXISTS ITEMS ("
                    + "  UPC VARCHAR(64) PRIMARY KEY,"
                    + "  DESCRIPTION VARCHAR(255) NOT NULL,"
                    + "  UNIT_PRICE DECIMAL(19,4) NOT NULL,"
                    + "  DISPLAY_NAME VARCHAR(255)"
                    + ")";

    private static final String FIND_BY_UPC_SQL =
            "SELECT UPC, DESCRIPTION, UNIT_PRICE, DISPLAY_NAME FROM ITEMS WHERE UPC = ?";

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM ITEMS";

    private static final String INSERT_SQL =
            "INSERT INTO ITEMS (UPC, DESCRIPTION, UNIT_PRICE, DISPLAY_NAME) VALUES (?, ?, ?, ?)";

    private final Connection connection;

    H2ItemRepository(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens (or creates) the H2 file DB at {@code dbDir/dbName}. Ensures the schema exists and,
     * if the table is empty, seeds it from the given classpath pricebook resource.
     *
     * @param dbDir             directory to hold the H2 files; created if absent
     * @param dbName            base name of the DB (no extension) — H2 appends {@code .mv.db}
     * @param seedResourcePath  classpath pricebook (e.g. {@code "/pricebook.tsv"})
     */
    public static H2ItemRepository open(Path dbDir, String dbName, String seedResourcePath) {
        if (dbDir == null) throw new IllegalArgumentException("dbDir must not be null");
        if (dbName == null || dbName.isBlank()) {
            throw new IllegalArgumentException("dbName must not be null or blank");
        }
        try {
            Files.createDirectories(dbDir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to create H2 db dir: " + dbDir, e);
        }

        String url = "jdbc:h2:file:" + dbDir.toAbsolutePath().resolve(dbName);
        Connection conn;
        try {
            conn = DriverManager.getConnection(url, "sa", "");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to open H2 database at " + url, e);
        }

        try {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(SCHEMA_SQL);
            }
            if (rowCount(conn) == 0 && seedResourcePath != null) {
                seed(conn, PricebookTsv.loadFromClasspath(seedResourcePath));
            }
        } catch (SQLException | RuntimeException e) {
            closeQuietly(conn);
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException("failed to initialise H2 items table", e);
        }

        return new H2ItemRepository(conn);
    }

    /**
     * H2 stores prices at scale 4; the pricebook and the rest of the app work at scale 2. Strip
     * trailing zeros then floor at scale 2 so {@code 8.9100} round-trips as {@code 8.91}. A price
     * that legitimately needs more precision than 2 keeps its extra digits.
     */
    private static BigDecimal normalisePrice(BigDecimal raw) {
        BigDecimal stripped = raw.stripTrailingZeros();
        int scale = Math.max(stripped.scale(), 2);
        return stripped.setScale(scale, java.math.RoundingMode.UNNECESSARY);
    }

    private static int rowCount(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(COUNT_SQL)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void seed(Connection conn, Map<String, Item> items) throws SQLException {
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            for (Item item : items.values()) {
                ps.setString(1, item.getUpc());
                ps.setString(2, item.getDescription());
                ps.setBigDecimal(3, item.getUnitPrice());
                ps.setString(4, item.getDisplayName());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    @Override
    public Optional<Item> findByUpc(String upc) {
        if (upc == null) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement(FIND_BY_UPC_SQL)) {
            ps.setString(1, upc);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Item(
                        rs.getString("UPC"),
                        rs.getString("DESCRIPTION"),
                        normalisePrice(rs.getBigDecimal("UNIT_PRICE")),
                        rs.getString("DISPLAY_NAME")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("H2 lookup failed for upc '" + upc + "'", e);
        }
    }

    @Override
    public int size() {
        try {
            return rowCount(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("H2 count failed", e);
        }
    }

    @Override
    public void close() {
        closeQuietly(connection);
    }

    private static void closeQuietly(Connection conn) {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException ignore) {
        }
    }
}
