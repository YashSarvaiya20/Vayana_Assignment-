package com.shop.model;

/**
 * OrderLine domain entity.
 * priceAtOrder preserves the historical unit price at the moment the order was placed.
 */
public record OrderLine(
    long orderId,
    String sku,
    int quantity,
    int priceAtOrder // in paise
) {}
