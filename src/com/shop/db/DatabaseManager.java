package com.shop.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages MySQL database connections and initializes the schema.
 * Credentials and URL are loaded from environment variables with safe defaults:
 * - DB_URL: defaults to jdbc:mysql://localhost:3306/inventory_orders
 * - DB_USER: defaults to root
 * - DB_PASSWORD: defaults to root (never printed or exposed)
 */
public class DatabaseManager {
    private static final String DEFAULT_URL =
            "jdbc:mysql://localhost:3306/inventory_orders?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String DEFAULT_USER = "root";
    private static final String DEFAULT_PASSWORD = "root";

    private final String url;
    private final String user;
    private final String password;

    public DatabaseManager() {
        this.url = System.getenv().getOrDefault("DB_URL", DEFAULT_URL);
        this.user = System.getenv().getOrDefault("DB_USER", DEFAULT_USER);
        this.password = System.getenv().getOrDefault("DB_PASSWORD", DEFAULT_PASSWORD);
        initSchema();
    }

    public DatabaseManager(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        initSchema();
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public void initSchema() {
        String createProductsTable = """
            CREATE TABLE IF NOT EXISTS products (
                sku         VARCHAR(64) PRIMARY KEY,
                name        VARCHAR(255) NOT NULL,
                price       BIGINT NOT NULL CHECK (price >= 0),
                stock       INT NOT NULL CHECK (stock >= 0),
                INDEX idx_products_stock (stock)
            ) ENGINE=InnoDB;
            """;

        String createOrdersTable = """
            CREATE TABLE IF NOT EXISTS orders (
                id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                customer    VARCHAR(255) NOT NULL,
                created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                status      VARCHAR(32) NOT NULL CHECK (status IN ('PLACED', 'CANCELLED')),
                total       BIGINT NOT NULL CHECK (total >= 0)
            ) ENGINE=InnoDB;
            """;

        String createOrderLinesTable = """
            CREATE TABLE IF NOT EXISTS order_lines (
                order_id        BIGINT NOT NULL,
                sku             VARCHAR(64) NOT NULL,
                quantity        INT NOT NULL CHECK (quantity > 0),
                price_at_order  BIGINT NOT NULL CHECK (price_at_order >= 0),
                PRIMARY KEY (order_id, sku),
                CONSTRAINT fk_orderlines_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
                CONSTRAINT fk_orderlines_product FOREIGN KEY (sku) REFERENCES products(sku)
            ) ENGINE=InnoDB;
            """;

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createProductsTable);
            stmt.execute(createOrdersTable);
            stmt.execute(createOrderLinesTable);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize MySQL schema: " + e.getMessage(), e);
        }
    }
}
