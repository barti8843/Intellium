package com.smjetanka_.intellium.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class IntelliumThreadPool {
    // Liczba rdzeni minus 1, żeby zostawić oddech dla głównego wątku gry i systemu
    private static final int THREAD_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    public static final ExecutorService WORKER_POOL = Executors.newFixedThreadPool(THREAD_COUNT, new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "Intellium-Worker-" + counter.getAndIncrement());
            // Standardowy priorytet z lekkim wyrównaniem, żeby schedulowanie CPU nie wariowało
            thread.setPriority(Thread.NORM_PRIORITY);
            thread.setDaemon(true);
            return thread;
        }
    });

    public static void init() {
        // Pusta metoda wywołująca załadowanie klasy i statycznej puli wątków
    }
}
