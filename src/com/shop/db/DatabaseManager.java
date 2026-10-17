package com.shop.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages SQLite database connection and schema initialization.
 * Configures critical PRAGMAs for concurrency and data integrity:
 * 1. foreign_keys = ON (Enforces relational constraints)
 * 2. journal_mode = WAL (Write-Ahead Logging for concurrent reads and writes)
 * 3. busy_timeout = 5000 (Waits up to 5s on locked database before throwing SQLITE_BUSY)
 */
public class DatabaseManager {
    private final String dbUrl;

    public DatabaseManager(String dbPath) {
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        initSchema();
    }

    public Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("PRAGMA busy_timeout = 5000;");
        }
        return conn;
    }

    private void initSchema() {
        String createProductsTable = """
            CREATE TABLE IF NOT EXISTS products (
                sku         TEXT PRIMARY KEY,
                name        TEXT NOT NULL,
                price       INTEGER NOT NULL CHECK(price >= 0),
                stock       INTEGER NOT NULL CHECK(stock >= 0)
            );
            """;

        String createOrdersTable = """
            CREATE TABLE IF NOT EXISTS orders (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                customer    TEXT NOT NULL,
                created_at  TEXT NOT NULL DEFAULT (datetime('now')),
                status      TEXT NOT NULL CHECK(status IN ('PLACED', 'CANCELLED')),
                total       INTEGER NOT NULL CHECK(total >= 0)
            );
            """;

        String createOrderLinesTable = """
            CREATE TABLE IF NOT EXISTS order_lines (
                order_id        INTEGER NOT NULL,
                sku             TEXT NOT NULL,
                quantity        INTEGER NOT NULL CHECK(quantity > 0),
                price_at_order  INTEGER NOT NULL CHECK(price_at_order >= 0),
                PRIMARY KEY (order_id, sku),
                FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
                FOREIGN KEY (sku) REFERENCES products(sku)
            );
            """;

        String createIndexes = """
            CREATE INDEX IF NOT EXISTS idx_products_stock ON products(stock);
            CREATE INDEX IF NOT EXISTS idx_orderlines_order ON order_lines(order_id);
            """;

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createProductsTable);
            stmt.execute(createOrdersTable);
            stmt.execute(createOrderLinesTable);
            stmt.execute(createIndexes);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database schema: " + e.getMessage(), e);
        }
    }
}
