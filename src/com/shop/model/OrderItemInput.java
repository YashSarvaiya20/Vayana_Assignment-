package com.shop.model;

/**
 * Represents a line item input request (SKU and quantity).
 */
public record OrderItemInput(String sku, int quantity) {}
