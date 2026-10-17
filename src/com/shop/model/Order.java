package com.shop.model;

/**
 * Order domain entity.
 * Status can be 'PLACED' or 'CANCELLED'.
 * Total is stored in whole paise (integers).
 */
public record Order(
    long id,
    String customer,
    String createdAt,
    String status,
    int total // in paise
) {}
