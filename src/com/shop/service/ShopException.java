package com.shop.service;

/**
 * Domain exception for business rule violations and user errors.
 * Caught by the CLI layer to print one-line error messages without stack traces.
 */
public class ShopException extends Exception {
    public ShopException(String message) {
        super(message);
    }
}
