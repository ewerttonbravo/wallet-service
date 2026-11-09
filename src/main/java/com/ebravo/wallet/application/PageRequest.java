package com.ebravo.wallet.application;

/** Which page of a wallet's history to read: page numbers start at 0. */
public record PageRequest(int number, int size) {

    public static final int MAX_SIZE = 100;

    public PageRequest {
        if (number < 0) {
            throw new IllegalArgumentException("page must be a whole number from 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be a whole number from 1 to " + MAX_SIZE);
        }
    }

    /** In {@code long}: the offset of a page number near {@code Integer.MAX_VALUE} does not fit an {@code int}. */
    public long offset() {
        return (long) number * size;
    }

    public PageRequest next() {
        return new PageRequest(number + 1, size);
    }
}
