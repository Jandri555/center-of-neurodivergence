package com.neurocenter.devkit;

/** Separate .class to verify that the custom streaming ClassLoader resolves dependencies. */
public final class ProbeDependency {
    private ProbeDependency() { }
    public static String value() { return "ok-dependencia"; }
}
