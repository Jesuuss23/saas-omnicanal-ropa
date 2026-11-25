package com.saas.omnicanal.catalog.service;

/** Redacta la descripción de un producto con un modelo de lenguaje. */
public interface DescriptionWriter {
    boolean configured();

    String write(String system, String prompt);
}
