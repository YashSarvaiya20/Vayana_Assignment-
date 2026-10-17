package com.shop.service;

import com.shop.dao.OrderDao;
import com.shop.dao.ProductDao;
import com.shop.db.DatabaseManager;
import com.shop.model.Order;
import com.shop.model.OrderItemInput;
import com.shop.model.OrderLine;
import com.shop.model.Product;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service coordinating business logic, transactions, and concurrency rules.
 */
public class ShopService {
    private final DatabaseManager dbManager;
    private final ProductDao productDao;
    private final OrderDao orderDao;

    public record OrderResult(long orderId, int totalPaise) {}

    public record SimulationResult(
        int succeeded,
        int failed,
        int finalStock,
        boolean ruleHolds
    ) {}

    public ShopService(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        this.productDao = new ProductDao();
        this.orderDao = new OrderDao();
    }

    public void addProduct(String sku, String name, int price, int stock) throws ShopException {
        if (sku == null || sku.trim().isEmpty()) {
            throw new ShopException("SKU cannot be empty");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new ShopException("Product name cannot be empty");
        }
        if (price < 0) {
            throw new ShopException("Price cannot be negative");
        }
        if (stock < 0) {
            throw new ShopException("Stock cannot be negative");
        }

        try (Connection conn = dbManager.getConnection()) {
            productDao.upsertProduct(conn, sku.trim(), name.trim(), price, stock);
        } catch (SQLException e) {
            throw new ShopException("Failed to add product: " + e.getMessage());
        }
    }

    public OrderResult placeOrder(String customer, List<OrderItemInput> items) throws ShopException {
        if (customer == null || customer.trim().isEmpty()) {
            throw new ShopException("Customer cannot be empty");
        }
        if (items == null || items.isEmpty()) {
            throw new ShopException("Order must have at least 1 line item");
        }
        if (items.size() > 5) {
            throw new ShopException("Order cannot have more than 5 line items");
        }

        // Consolidate duplicate SKUs in the same order (e.g. P1:2 and P1:3 -> P1:5)
        Map<String, Integer> consolidated = new LinkedHashMap<>();
        for (OrderItemInput item : items) {
            if (item.sku() == null || item.sku().trim().isEmpty()) {
                throw new ShopException("SKU cannot be empty in order line");
            }
            if (item.quantity() <= 0) {
                throw new ShopException("Quantity must be greater than 0");
            }
            consolidated.merge(item.sku().trim(), item.quantity(), Integer::sum);
        }

        if (consolidated.size() > 5) {
            throw new ShopException("Order cannot have more than 5 distinct SKUs");
        }

        // Sort SKUs alphabetically before acquiring locks to eliminate AB-BA deadlocks
        List<String> sortedSkus = new ArrayList<>(consolidated.keySet());
        Collections.sort(sortedSkus);

        try (Connection conn = dbManager.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                // 1. Lock rows in deterministic order and verify stock & existence
                Map<String, Product> productSnapshots = new HashMap<>();
                int totalPaise = 0;

                for (String sku : sortedSkus) {
                    int requestedQty = consolidated.get(sku);
                    Optional<Product> prodOpt = productDao.findBySkuForUpdate(conn, sku);
                    if (prodOpt.isEmpty()) {
                        throw new ShopException("Product " + sku + " does not exist");
                    }
                    Product prod = prodOpt.get();
                    if (prod.stock() < requestedQty) {
                        throw new ShopException("SKU " + sku + " is short (requested " + requestedQty + ", available " + prod.stock() + ")");
                    }
                    productSnapshots.put(sku, prod);
                    totalPaise += requestedQty * prod.price();
                }

                // 2. All or nothing: deduct stock
                for (String sku : sortedSkus) {
                    int requestedQty = consolidated.get(sku);
                    int updated = productDao.deductStock(conn, sku, requestedQty);
                    if (updated == 0) {
                        throw new ShopException("SKU " + sku + " is short");
                    }
                }

                // 3. Create order record
                long orderId = orderDao.createOrder(conn, customer.trim(), totalPaise);

                // 4. Create order line records preserving price at order time
                for (String sku : sortedSkus) {
                    int requestedQty = consolidated.get(sku);
                    int priceAtOrder = productSnapshots.get(sku).price();
                    orderDao.insertOrderLine(conn, orderId, sku, requestedQty, priceAtOrder);
                }

                conn.commit();
                return new OrderResult(orderId, totalPaise);

            } catch (Exception e) {
                conn.rollback();
                if (e instanceof ShopException se) {
                    throw se;
                }
                throw new ShopException("Order failed: " + e.getMessage());
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            throw new ShopException("Database error during order: " + e.getMessage());
        }
    }

    public void cancelOrder(long orderId) throws ShopException {
        try (Connection conn = dbManager.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);

                // 1. Lock order row and verify status
                Optional<Order> orderOpt = orderDao.findOrderForUpdate(conn, orderId);
                if (orderOpt.isEmpty()) {
                    throw new ShopException("Order " + orderId + " does not exist");
                }
                Order order = orderOpt.get();
                if ("CANCELLED".equalsIgnoreCase(order.status())) {
                    throw new ShopException("Order " + orderId + " is already cancelled");
                }

                // 2. Fetch lines and restore stock
                List<OrderLine> lines = orderDao.findOrderLines(conn, orderId);
                for (OrderLine line : lines) {
                    productDao.restoreStock(conn, line.sku(), line.quantity());
                }

                // 3. Update order status
                orderDao.updateOrderStatus(conn, orderId, "CANCELLED");

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof ShopException se) {
                    throw se;
                }
                throw new ShopException("Cancellation failed: " + e.getMessage());
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            throw new ShopException("Database error during cancellation: " + e.getMessage());
        }
    }

    public List<Product> getLowStock(int limit) throws ShopException {
        if (limit < 0) {
            throw new ShopException("Limit cannot be negative");
        }
        try (Connection conn = dbManager.getConnection()) {
            return productDao.findLowStock(conn, limit);
        } catch (SQLException e) {
            throw new ShopException("Failed to fetch low stock products: " + e.getMessage());
        }
    }

    public int getProductStock(String sku) throws ShopException {
        try (Connection conn = dbManager.getConnection()) {
            Optional<Product> prod = productDao.findBySku(conn, sku);
            return prod.map(Product::stock).orElseThrow(() -> new ShopException("Product " + sku + " not found"));
        } catch (SQLException e) {
            throw new ShopException("Failed to query product stock: " + e.getMessage());
        }
    }

    public SimulationResult simulate() throws ShopException {
        // Reset or add product P1 with exact stock 10
        try (Connection conn = dbManager.getConnection()) {
            productDao.resetProductStock(conn, "P1", "Product P1", 1000, 10);
        } catch (SQLException e) {
            throw new ShopException("Failed to setup simulate product: " + e.getMessage());
        }

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger outOfStockCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        for (int i = 1; i <= threadCount; i++) {
            final String customer = "SimCust_" + i;
            executor.submit(() -> {
                try {
                    startGun.await(); // wait for simultaneous start
                    placeOrder(customer, List.of(new OrderItemInput("P1", 1)));
                    successCount.incrementAndGet();
                } catch (ShopException e) {
                    if (e.getMessage().contains("short") || e.getMessage().contains("out of stock")) {
                        outOfStockCount.incrementAndGet();
                    } else {
                        otherErrorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherErrorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Fire start gun
        startGun.countDown();

        try {
            boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
            if (!completed) {
                throw new ShopException("Simulation timed out waiting for threads");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShopException("Simulation interrupted: " + e.getMessage());
        } finally {
            executor.shutdown();
        }

        int finalStock = getProductStock("P1");
        boolean ruleHolds = (successCount.get() == 10) && (outOfStockCount.get() == 10) && (finalStock == 0);

        return new SimulationResult(
            successCount.get(),
            outOfStockCount.get(),
            finalStock,
            ruleHolds
        );
    }
}
