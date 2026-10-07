package com.github.cuixudong74.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Asynchronous, bounded, fixed-bucket sliding-window collector.
 *
 * <p>Business threads perform only a constant-cost (apart from the configured, bounded number of
 * dimensions) validation/sampling step and a non-blocking bounded queue offer. One daemon worker
 * serializes all aggregation and window rotation. Consequently a measurement is either present once
 * or has a distinguishable rejection reason; it cannot be counted twice.
 */
public final class SlidingWindowMetricCollector implements MetricCollector {

    private final Clock clock;
    private final LinkedBlockingQueue<Object> queue;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger pending = new AtomicInteger();
    private final Map<Admission, LongAdder> dropped = new ConcurrentHashMap<>();
    private final Object idleLock = new Object();

    private volatile MetricsConfig config;
    private volatile SlidingWindow window;
    private final Thread worker;

    public SlidingWindowMetricCollector() {
        this(MetricsConfig.defaults(), Clock.systemUTC());
    }

    public SlidingWindowMetricCollector(MetricsConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.queue = new LinkedBlockingQueue<>(config.queueCapacity());
        this.window = new SlidingWindow(config, clock.millis());
        this.worker = new Thread(this::runWorker, "runtime-perf-metrics-worker");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public Admission recordLatency(long durationNanos, RequestStatus status,
                                   Map<String, String> dimensions) {
        try {
            MetricsConfig current = config;
            if (closed.get()) {
                return Admission.SHUTDOWN;
            }
            if (durationNanos < 0L) {
                return countRejection(Admission.INVALID_ARGUMENT);
            }
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(dimensions, "dimensions");

            Map<String, String> safeDimensions = copyDimensions(dimensions, current);
            if (!current.enabled()) {
                return countRejection(Admission.DISABLED);
            }
            double sampleRate = current.sampleRate();
            if (sampleRate == 0.0d || (sampleRate < 1.0d
                    && ThreadLocalRandom.current().nextDouble() >= sampleRate)) {
                return Admission.SAMPLED_OUT;
            }

            double weight = 1.0d / sampleRate;
            MetricEvent event = new MetricEvent(
                    clock.millis(), durationNanos, status, safeDimensions, weight);
            return enqueue(event);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return countRejection(Admission.INVALID_ARGUMENT);
        } catch (RuntimeException exception) {
            return countRejection(Admission.INTERNAL_ERROR);
        }
    }

    @Override
    public MetricSnapshot snapshot() {
        try {
            Instant capturedAt = Instant.now(clock);
            return window.snapshot(capturedAt, this::snapshotDropped);
        } catch (RuntimeException exception) {
            countRejection(Admission.INTERNAL_ERROR);
            return emptySnapshot(Instant.now(clock));
        }
    }

    private MetricSnapshot emptySnapshot(Instant capturedAt) {
        long bucketLengthMillis = config.bucketLength().toMillis();
        long windowStart = Math.floorDiv(capturedAt.toEpochMilli(), bucketLengthMillis)
                * bucketLengthMillis - (long) (config.bucketCount() - 1) * bucketLengthMillis;
        return new MetricSnapshot(
                Instant.ofEpochMilli(windowStart),
                Instant.ofEpochMilli(windowStart + (long) config.bucketCount() * bucketLengthMillis),
                capturedAt,
                0L,
                0.0d,
                0.0d,
                0L,
                0L,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                snapshotDropped()
        );
    }

    @Override
    public Admission updateConfig(MetricsConfig newConfig) {
        if (newConfig == null) {
            return Admission.INVALID_ARGUMENT;
        }
        if (closed.get()) {
            return Admission.SHUTDOWN;
        }
        try {
            // Validate the immutable value before publishing it to recording threads.
            MetricsConfig validated = new MetricsConfig(
                    newConfig.enabled(),
                    newConfig.sampleRate(),
                    newConfig.windowLength(),
                    newConfig.bucketLength(),
                    newConfig.maxGroupsPerBucket(),
                    newConfig.maxDimensionsPerRecord(),
                    newConfig.cardinalityPolicy(),
                    newConfig.queueCapacity(),
                    newConfig.degradedDimensionName(),
                    newConfig.degradedDimensionValue());
            return enqueue(new ConfigurationEvent(validated), validated);
        } catch (RuntimeException exception) {
            return Admission.INVALID_ARGUMENT;
        }
    }

    @Override
    public MetricsConfig currentConfig() {
        return config;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(Duration.ofSeconds(2).toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** Test-only barrier: wait until events accepted before this call have been processed. */
    void awaitProcessed(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (idleLock) {
            while (pending.get() > 0L && System.nanoTime() < deadline) {
                try {
                    idleLock.wait(5L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private Admission enqueue(Object item) {
        return enqueue(item, null);
    }

    private Admission enqueue(Object item, MetricsConfig configAfterOffer) {
        pending.incrementAndGet();
        boolean offered = false;
        try {
            offered = queue.offer(item);
            if (offered && configAfterOffer != null) {
                config = configAfterOffer;
            }
            return offered ? Admission.ACCEPTED : countRejection(Admission.QUEUE_FULL);
        } finally {
            if (!offered) {
                pending.decrementAndGet();
                signalIdle();
            }
        }
    }

    private void runWorker() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Object item = queue.poll(250L, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (item != null) {
                    process(item);
                }
                if (item == null && closed.get() && queue.isEmpty()) {
                    return;
                }
            } catch (InterruptedException exception) {
                drainOnShutdown();
                return;
            } catch (RuntimeException exception) {
                countRejection(Admission.INTERNAL_ERROR);
            } catch (Throwable throwable) {
                countRejection(Admission.INTERNAL_ERROR);
                throw throwable;
            }
        }
        drainOnShutdown();
    }

    private void process(Object item) {
        try {
            if (item instanceof MetricEvent event) {
                Admission result = window.add(event, clock.millis());
                if (result != Admission.ACCEPTED) {
                    countRejection(result);
                }
            } else if (item instanceof ConfigurationEvent configuration) {
                MetricsConfig next = configuration.config();
                SlidingWindow currentWindow = window;
                if (!currentWindow.updatePolicyIfStructureUnchanged(next)) {
                    window = new SlidingWindow(next, clock.millis());
                }
                config = next;
            } else {
                countRejection(Admission.INTERNAL_ERROR);
            }
        } catch (RuntimeException exception) {
            countRejection(Admission.INTERNAL_ERROR);
        } finally {
            pending.decrementAndGet();
            signalIdle();
        }
    }

    private void drainOnShutdown() {
        // A producer may have incremented pending before it offers during shutdown. Wait for
        // those in-flight accepted events instead of stopping after a momentarily empty queue.
        while (pending.get() > 0 || !queue.isEmpty()) {
            try {
                Object item = queue.poll(10L, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (item != null) {
                    process(item);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                Object item = queue.poll();
                if (item != null) {
                    process(item);
                }
            }
        }
    }

    private Admission countRejection(Admission reason) {
        dropped.computeIfAbsent(reason, ignored -> new LongAdder()).increment();
        return reason;
    }

    private Map<Admission, Long> snapshotDropped() {
        Map<Admission, Long> copy = new LinkedHashMap<>();
        dropped.forEach((reason, adder) -> {
            long value = adder.sum();
            if (value > 0L) {
                copy.put(reason, value);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private Map<String, String> copyDimensions(Map<String, String> source, MetricsConfig current) {
        if (source.isEmpty()) {
            return Map.of();
        }
        if (source.size() > current.maxDimensionsPerRecord()) {
            throw new IllegalArgumentException("too many dimensions");
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                throw new IllegalArgumentException("dimension keys and values must be non-null");
            }
            sorted.put(key, value);
        }
        return Collections.unmodifiableMap(sorted);
    }

    private void signalIdle() {
        if (pending.get() == 0L) {
            synchronized (idleLock) {
                idleLock.notifyAll();
            }
        }
    }
}
