package com.neurocenter.demo;

/** La lógica compartida se compila una sola vez con Java 21. */
public final class Counter {
    private int value;

    public int increment() { return ++value; }
    public int value() { return value; }
}
