package com.shop.model;

/**
 * Product domain entity.
 * Prices are stored in whole paise (integers) to prevent floating-point rounding issues.
 */
public record Product(
    String sku,
    String name,
    int price, // in paise
    int stock
) {}
