package com.shop;

import com.shop.db.DatabaseManager;
import com.shop.model.OrderItemInput;
import com.shop.model.Product;
import com.shop.service.ShopException;
import com.shop.service.ShopService;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Command-Line Interface for the Inventory and Orders shop application.
 * Accepts interactive REPL commands or direct CLI arguments.
 * Catches all exceptions and outputs concise one-line error messages without stack traces.
 */
public class Main {
    private final ShopService service;

    public Main(ShopService service) {
        this.service = service;
    }

    public static void main(String[] args) {
        try {
            DatabaseManager dbManager = new DatabaseManager();
            ShopService service = new ShopService(dbManager);
            Main app = new Main(service);

            if (args != null && args.length > 0) {
                // Single-command mode
                app.executeCommandLine(String.join(" ", args));
            } else {
                // Interactive REPL mode
                app.runInteractiveLoop();
            }
        } catch (Exception e) {
            System.err.println("Fatal startup error: " + e.getMessage());
            System.exit(1);
        }
    }

    public void runInteractiveLoop() {
        System.out.println("Inventory and Orders Management System");
        System.out.println("Type 'help' for available commands, or 'exit' to quit.\n");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                System.out.println("Goodbye.");
                break;
            }
            executeCommandLine(line);
        }
    }

    public void executeCommandLine(String input) {
        String[] tokens = tokenize(input);
        if (tokens.length == 0) {
            return;
        }

        String command = tokens[0].toLowerCase();
        try {
            switch (command) {
                case "add" -> handleAdd(tokens);
                case "order" -> handleOrder(tokens);
                case "cancel" -> handleCancel(tokens);
                case "low-stock" -> handleLowStock(tokens);
                case "simulate" -> handleSimulate();
                case "help" -> printHelp();
                default -> System.out.println("Error: Unknown command '" + command + "'. Type 'help' for usage.");
            }
        } catch (ShopException e) {
            System.out.println("Error: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.out.println("Error: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Error: Unexpected error: " + e.getMessage());
        }
    }

    private void handleAdd(String[] tokens) throws ShopException {
        // Syntax: add <sku> <name> <price> <stock>
        if (tokens.length < 5) {
            throw new IllegalArgumentException("Usage: add <sku> <name> <price> <stock>");
        }
        String sku = tokens[1];
        String name = tokens[2];
        int price;
        int stock;
        try {
            price = Integer.parseInt(tokens[3]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid price '" + tokens[3] + "': must be an integer (in paise)");
        }
        try {
            stock = Integer.parseInt(tokens[4]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid stock '" + tokens[4] + "': must be an integer");
        }

        service.addProduct(sku, name, price, stock);
        int currentStock = service.getProductStock(sku);
        System.out.println("Product " + sku + " (" + name + ") saved. Stock: " + currentStock + ", Price: " + price + " paise");
    }

    private void handleOrder(String[] tokens) throws ShopException {
        // Syntax: order <customer> <sku>:<qty> [<sku>:<qty>...]
        if (tokens.length < 3) {
            throw new IllegalArgumentException("Usage: order <customer> <sku>:<qty> [<sku>:<qty>...]");
        }
        String customer = tokens[1];
        List<OrderItemInput> items = new ArrayList<>();

        for (int i = 2; i < tokens.length; i++) {
            String token = tokens[i];
            String[] parts = token.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid order line format '" + token + "'. Expected <sku>:<qty>");
            }
            String sku = parts[0].trim();
            int qty;
            try {
                qty = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid quantity in '" + token + "': must be an integer");
            }
            items.add(new OrderItemInput(sku, qty));
        }

        ShopService.OrderResult result = service.placeOrder(customer, items);
        System.out.println("Order #" + result.orderId() + " placed. Total: " + result.totalPaise() + " paise");
    }

    private void handleCancel(String[] tokens) throws ShopException {
        // Syntax: cancel <order_id>
        if (tokens.length < 2) {
            throw new IllegalArgumentException("Usage: cancel <order_id>");
        }
        long orderId;
        try {
            orderId = Long.parseLong(tokens[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid order ID '" + tokens[1] + "': must be an integer");
        }

        service.cancelOrder(orderId);
        System.out.println("Order #" + orderId + " cancelled. Stock restored.");
    }

    private void handleLowStock(String[] tokens) throws ShopException {
        // Syntax: low-stock [--below 5]
        int limit = 5; // default
        if (tokens.length >= 2) {
            if (tokens[1].equalsIgnoreCase("--below")) {
                if (tokens.length < 3) {
                    throw new IllegalArgumentException("Missing threshold value after --below");
                }
                try {
                    limit = Integer.parseInt(tokens[2]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid threshold '" + tokens[2] + "': must be an integer");
                }
            } else {
                throw new IllegalArgumentException("Unknown option '" + tokens[1] + "'. Expected --below <limit>");
            }
        }

        List<Product> products = service.getLowStock(limit);
        if (products.isEmpty()) {
            System.out.println("No products found with stock below " + limit + ".");
            return;
        }

        System.out.println("Products with stock below " + limit + " (lowest first):");
        for (Product p : products) {
            System.out.println("  " + p.sku() + " | " + p.name() + " | stock: " + p.stock() + " | price: " + p.price() + " paise");
        }
    }

    private void handleSimulate() throws ShopException {
        System.out.println("Running concurrency simulation (20 threads competing for 10 units of P1)...");
        ShopService.SimulationResult result = service.simulate();

        System.out.println("Simulation Results:");
        System.out.println("  Succeeded orders : " + result.succeeded() + " (Expected: 10)");
        System.out.println("  Failed (out of stock): " + result.failed() + " (Expected: 10)");
        System.out.println("  Final stock of P1: " + result.finalStock() + " (Expected: 0)");
        System.out.println("  Threading Invariant: " + (result.ruleHolds() ? "PASS (Rule holds)" : "FAIL"));
    }

    private void printHelp() {
        System.out.println("Available commands:");
        System.out.println("  add <sku> <name> <price> <stock>          Add new product or increase existing stock");
        System.out.println("  order <customer> <sku>:<qty> [<sku>:<qty>...] Place order (1-5 lines, all or nothing)");
        System.out.println("  cancel <order_id>                         Cancel placed order and restore stock");
        System.out.println("  low-stock [--below 5]                     List products with stock below threshold");
        System.out.println("  simulate                                  Run 20-thread concurrency test on P1 (stock 10)");
        System.out.println("  exit / quit                               Exit the application");
    }

    /**
     * Splits command string into tokens, preserving quoted strings e.g. "Mechanical Keyboard".
     */
    private static String[] tokenize(String input) {
        List<String> list = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (sb.length() > 0) {
                    list.add(sb.toString());
                    sb.setLength(0);
                }
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            list.add(sb.toString());
        }
        return list.toArray(new String[0]);
    }
}
