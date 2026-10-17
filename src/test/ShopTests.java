package test;

import com.shop.db.DatabaseManager;
import com.shop.model.OrderItemInput;
import com.shop.service.ShopException;
import com.shop.service.ShopService;

import java.util.List;

/**
 * Automated test suite implementing all 5 mandatory assessment test cases.
 * Can be run directly from the command line without extra frameworks.
 */
public class ShopTests {
    private final ShopService service;
    private int passed = 0;
    private int failed = 0;

    public ShopTests(ShopService service) {
        this.service = service;
    }

    public static void main(String[] args) {
        DatabaseManager dbManager = new DatabaseManager();
        ShopService service = new ShopService(dbManager);
        ShopTests suite = new ShopTests(service);
        suite.runAllTests();
    }

    public void runAllTests() {
        System.out.println("==========================================");
        System.out.println(" Running Vayana Shop Automated Tests");
        System.out.println("==========================================\n");

        runTest("Test 1: Adding a product and increasing existing stock", this::test1_AddProductAndIncreaseStock);
        runTest("Test 2: Place order, check total, and verify stock deduction", this::test2_PlaceOrderSuccess);
        runTest("Test 3: Reject multi-product order atomically when short", this::test3_RejectAtomicMultiProductShortage);
        runTest("Test 4: Cancel order, restore stock, reject duplicate cancellation", this::test4_CancelOrderAndDuplicateRejection);
        runTest("Test 5: Concurrency simulation (20 threads vs 10 units of P1)", this::test5_ConcurrencySimulation);
        runTest("Test 6: Validation rules (negative prices, zero qty, unknown IDs)", this::test6_ValidationRules);

        System.out.println("\n==========================================");
        System.out.println(" Test Summary: " + passed + " PASSED, " + failed + " FAILED");
        System.out.println("==========================================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    private void runTest(String name, TestMethod test) {
        try {
            test.run();
            System.out.println("[PASS] " + name);
            passed++;
        } catch (Throwable t) {
            System.out.println("[FAIL] " + name + ": " + t.getMessage());
            t.printStackTrace(System.out);
            failed++;
        }
    }

    @FunctionalInterface
    interface TestMethod {
        void run() throws Exception;
    }

    private void test1_AddProductAndIncreaseStock() throws Exception {
        String sku = "T_ADD_" + System.currentTimeMillis();
        service.addProduct(sku, "Widget A", 1000, 10);
        assertEquals(10, service.getProductStock(sku), "Initial stock should be 10");

        // Add 5 more to same SKU
        service.addProduct(sku, "Widget A", 1000, 5);
        assertEquals(15, service.getProductStock(sku), "Updated stock should be 15");
    }

    private void test2_PlaceOrderSuccess() throws Exception {
        String sku = "T_ORD_" + System.currentTimeMillis();
        service.addProduct(sku, "Widget B", 500, 10);

        ShopService.OrderResult result = service.placeOrder("TestCustomer", List.of(new OrderItemInput(sku, 3)));
        assertTrue(result.orderId() > 0, "Order ID should be positive");
        assertEquals(1500, result.totalPaise(), "Total should be 3 * 500 = 1500 paise");
        assertEquals(7, service.getProductStock(sku), "Remaining stock should be 7");
    }

    private void test3_RejectAtomicMultiProductShortage() throws Exception {
        long ts = System.currentTimeMillis();
        String sku1 = "T_SHORT1_" + ts;
        String sku2 = "T_SHORT2_" + ts;

        service.addProduct(sku1, "Item 1", 200, 10);
        service.addProduct(sku2, "Item 2", 300, 2);

        boolean caughtShortage = false;
        try {
            // Request 3 of sku1 (available 10) and 5 of sku2 (available 2)
            service.placeOrder("AtomicTester", List.of(
                new OrderItemInput(sku1, 3),
                new OrderItemInput(sku2, 5)
            ));
        } catch (ShopException e) {
            caughtShortage = true;
            assertTrue(e.getMessage().contains(sku2), "Error message should identify the short SKU");
        }

        assertTrue(caughtShortage, "Should have thrown ShopException for insufficient stock");
        // CRITICAL CHECK: sku1 must NOT have been deducted!
        assertEquals(10, service.getProductStock(sku1), "SKU1 stock must remain untouched at 10");
        assertEquals(2, service.getProductStock(sku2), "SKU2 stock must remain untouched at 2");
    }

    private void test4_CancelOrderAndDuplicateRejection() throws Exception {
        String sku = "T_CAN_" + System.currentTimeMillis();
        service.addProduct(sku, "Cancelable Item", 1000, 10);

        ShopService.OrderResult order = service.placeOrder("CancelTester", List.of(new OrderItemInput(sku, 4)));
        assertEquals(6, service.getProductStock(sku), "Stock should drop to 6 after order");

        // Cancel order
        service.cancelOrder(order.orderId());
        assertEquals(10, service.getProductStock(sku), "Stock should be restored to 10 after cancel");

        // Attempt duplicate cancellation
        boolean duplicateRejected = false;
        try {
            service.cancelOrder(order.orderId());
        } catch (ShopException e) {
            duplicateRejected = true;
            assertTrue(e.getMessage().contains("already cancelled"), "Error message should indicate already cancelled");
        }

        assertTrue(duplicateRejected, "Duplicate cancel must be rejected");
        assertEquals(10, service.getProductStock(sku), "Stock must not be restored twice");
    }

    private void test5_ConcurrencySimulation() throws Exception {
        ShopService.SimulationResult result = service.simulate();
        assertEquals(10, result.succeeded(), "Exactly 10 orders must succeed");
        assertEquals(10, result.failed(), "Exactly 10 orders must fail with out of stock");
        assertEquals(0, result.finalStock(), "Final stock of P1 must be 0");
        assertTrue(result.ruleHolds(), "Simulation rule invariant must hold");
    }

    private void test6_ValidationRules() throws Exception {
        // Negative price
        assertThrows(ShopException.class, () -> service.addProduct("INV_1", "Bad Price", -500, 10));

        // Negative stock
        assertThrows(ShopException.class, () -> service.addProduct("INV_2", "Bad Stock", 500, -10));

        // Zero quantity order
        assertThrows(ShopException.class, () -> service.placeOrder("Cust", List.of(new OrderItemInput("P1", 0))));

        // Unknown product
        assertThrows(ShopException.class, () -> service.placeOrder("Cust", List.of(new OrderItemInput("NON_EXISTENT_SKU", 1))));

        // Non-existent order cancellation
        assertThrows(ShopException.class, () -> service.cancelOrder(9999999L));
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (Expected: " + expected + ", Actual: " + actual + ")");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertThrows(Class<? extends Throwable> expected, ThrowingRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("Expected " + expected.getSimpleName() + " was not thrown");
        } catch (Throwable t) {
            if (!expected.isInstance(t)) {
                throw new AssertionError("Expected " + expected.getSimpleName() + " but got " + t.getClass().getSimpleName(), t);
            }
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
