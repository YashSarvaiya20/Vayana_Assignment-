package com.shop.dao;

import com.shop.model.Product;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for products table.
 * All queries use PreparedStatement and execute within the caller's connection/transaction.
 */
public class ProductDao {

    public void upsertProduct(Connection conn, String sku, String name, int price, int stock) throws SQLException {
        String sql = """
            INSERT INTO products (sku, name, price, stock)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                stock = stock + VALUES(stock),
                name = VALUES(name),
                price = VALUES(price);
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sku);
            ps.setString(2, name);
            ps.setInt(3, price);
            ps.setInt(4, stock);
            ps.executeUpdate();
        }
    }

    public void resetProductStock(Connection conn, String sku, String name, int price, int exactStock) throws SQLException {
        String sql = """
            INSERT INTO products (sku, name, price, stock)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                stock = VALUES(stock),
                name = VALUES(name),
                price = VALUES(price);
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sku);
            ps.setString(2, name);
            ps.setInt(3, price);
            ps.setInt(4, exactStock);
            ps.executeUpdate();
        }
    }

    public Optional<Product> findBySku(Connection conn, String sku) throws SQLException {
        String sql = "SELECT sku, name, price, stock FROM products WHERE sku = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sku);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    public Optional<Product> findBySkuForUpdate(Connection conn, String sku) throws SQLException {
        String sql = "SELECT sku, name, price, stock FROM products WHERE sku = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sku);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    public int deductStock(Connection conn, String sku, int quantity) throws SQLException {
        String sql = "UPDATE products SET stock = stock - ? WHERE sku = ? AND stock >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, quantity);
            ps.setString(2, sku);
            ps.setInt(3, quantity);
            return ps.executeUpdate();
        }
    }

    public void restoreStock(Connection conn, String sku, int quantity) throws SQLException {
        String sql = "UPDATE products SET stock = stock + ? WHERE sku = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, quantity);
            ps.setString(2, sku);
            ps.executeUpdate();
        }
    }

    public List<Product> findLowStock(Connection conn, int limit) throws SQLException {
        String sql = "SELECT sku, name, price, stock FROM products WHERE stock < ? ORDER BY stock ASC, sku ASC";
        List<Product> products = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    products.add(mapRow(rs));
                }
            }
        }
        return products;
    }

    private Product mapRow(ResultSet rs) throws SQLException {
        return new Product(
            rs.getString("sku"),
            rs.getString("name"),
            rs.getInt("price"),
            rs.getInt("stock")
        );
    }
}
