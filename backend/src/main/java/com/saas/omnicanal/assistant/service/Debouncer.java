package com.saas.omnicanal.assistant.service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Espera a que una clave (un chat) deje de recibir mensajes por {@code delayMs} y recién entonces corre la tarea, una
 * sola vez. Cada {@link #submit} reinicia la espera. Si la tarea de esa clave ya está corriendo cuando vence la espera,
 * se vuelve a esperar: nunca corren dos tareas de la misma clave a la vez en esta instancia (entre instancias, el
 * UPDATE atómico de {@code takeBatch} evita responder dos veces el mismo mensaje).
 */
final class Debouncer implements AutoCloseable {
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
        var t = new Thread(r, "ia-agrupacion");
        t.setDaemon(true);
        return t;
    });
    private final Executor workers;
    private final long delayMs;
    private final Map<String, ScheduledFuture<?>> waiting = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    Debouncer(Executor workers, long delayMs) {
        this.workers = workers;
        this.delayMs = Math.max(0, delayMs);
        timer.setRemoveOnCancelPolicy(true);
    }

    long delayMs() {
        return delayMs;
    }

    void submit(String key, Runnable task) {
        waiting.compute(key, (k, previous) -> {
            if (previous != null) previous.cancel(false);
            var self = new AtomicReference<ScheduledFuture<?>>();
            ScheduledFuture<?> next = timer.schedule(() -> {
                waiting.remove(k, self.get());
                workers.execute(() -> run(k, task));
            }, delayMs, TimeUnit.MILLISECONDS);
            self.set(next);
            return next;
        });
    }

    private void run(String key, Runnable task) {
        if (!running.add(key)) {
            submit(key, task);
            return;
        }
        try {
            task.run();
        } finally {
            running.remove(key);
        }
    }

    boolean waiting(String key) {
        return waiting.containsKey(key);
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
