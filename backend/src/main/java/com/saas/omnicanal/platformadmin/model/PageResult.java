package com.saas.omnicanal.platformadmin.model;

import java.util.List;

/** Página de resultados: las consultas piden una fila extra para saber si hay otra página. */
public record PageResult<T>(List<T> items, int page, boolean hasNext) {
    public static <T> PageResult<T> of(List<T> rows, int page, int size) {
        boolean more = rows.size() > size;
        return new PageResult<>(more ? rows.subList(0, size) : rows, page, more);
    }
}
