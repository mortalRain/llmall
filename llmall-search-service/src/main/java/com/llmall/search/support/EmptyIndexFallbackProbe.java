package com.llmall.search.support;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Per-instance evidence cache. Only cold/expired empty results serialize their global probe. */
public class EmptyIndexFallbackProbe {
    private final long ttlNanos;
    private final LongSupplier clock;
    private final Object refreshLock = new Object();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public EmptyIndexFallbackProbe(Duration ttl) { this(ttl, System::nanoTime); }
    EmptyIndexFallbackProbe(Duration ttl, LongSupplier clock) {
        ttlNanos = ttl.toNanos();
        if (ttlNanos <= 0) throw new IllegalArgumentException("Probe TTL must be positive");
        this.clock = Objects.requireNonNull(clock);
    }

    public boolean shouldFallback(BooleanSupplier probe) {
        Snapshot state = snapshot.get();
        if (fresh(state)) return state.fallback;
        synchronized (refreshLock) {
            state = snapshot.get();
            if (fresh(state)) return state.fallback;
            boolean fallback = probe.getAsBoolean();
            // A nonempty search may publish newer evidence while this slow probe is in flight.
            return snapshot.compareAndSet(state, new Snapshot(fallback, clock.getAsLong())) && fallback;
        }
    }

    /** Hits never wait for a count query, and they do not discard fresh nonempty evidence. */
    public void observeNonEmpty() {
        Snapshot state = snapshot.get();
        if (state == null || state.fallback || !fresh(state)) {
            snapshot.set(new Snapshot(false, clock.getAsLong()));
        }
    }

    private boolean fresh(Snapshot state) {
        if (state == null) return false;
        long age = clock.getAsLong() - state.checkedAt;
        return age >= 0 && age < ttlNanos;
    }

    private static final class Snapshot {
        final boolean fallback;
        final long checkedAt;
        Snapshot(boolean fallback, long checkedAt) { this.fallback = fallback; this.checkedAt = checkedAt; }
    }
}
