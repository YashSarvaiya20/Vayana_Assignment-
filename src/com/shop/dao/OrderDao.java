package com.shop.dao;

import com.shop.model.Order;
import com.shop.model.OrderLine;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data Access Object for orders and order_lines tables.
 * Participates in transactions coordinated by the Service layer.
 */
public class OrderDao {

    public long createOrder(Connection conn, String customer, int total) throws SQLException {
        String sql = "INSERT INTO orders (customer, status, total) VALUES (?, 'PLACED', ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, customer);
            ps.setInt(2, total);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("Failed to retrieve generated order ID.");
            }
        }
    }

    public void insertOrderLine(Connection conn, long orderId, String sku, int quantity, int priceAtOrder) throws SQLException {
        String sql = "INSERT INTO order_lines (order_id, sku, quantity, price_at_order) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            ps.setString(2, sku);
            ps.setInt(3, quantity);
            ps.setInt(4, priceAtOrder);
            ps.executeUpdate();
        }
    }

    public Optional<Order> findOrderForUpdate(Connection conn, long orderId) throws SQLException {
        String sql = "SELECT id, customer, created_at, status, total FROM orders WHERE id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Order(
                        rs.getLong("id"),
                        rs.getString("customer"),
                        rs.getString("created_at"),
                        rs.getString("status"),
                        rs.getInt("total")
                    ));
                }
                return Optional.empty();
            }
        }
    }

    public List<OrderLine> findOrderLines(Connection conn, long orderId) throws SQLException {
        String sql = "SELECT order_id, sku, quantity, price_at_order FROM order_lines WHERE order_id = ?";
        List<OrderLine> lines = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new OrderLine(
                        rs.getLong("order_id"),
                        rs.getString("sku"),
                        rs.getInt("quantity"),
                        rs.getInt("price_at_order")
                    ));
                }
            }
        }
        return lines;
    }

    public void updateOrderStatus(Connection conn, long orderId, String status) throws SQLException {
        String sql = "UPDATE orders SET status = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, orderId);
            ps.executeUpdate();
        }
    }
}
