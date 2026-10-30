package com.llmall.search.support;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class EmptyIndexFallbackProbeTest {
    @Test void zeroClockIsAValidCachedSnapshotAndExactTtlExpires() {
        AtomicLong clock=new AtomicLong();AtomicInteger calls=new AtomicInteger();
        EmptyIndexFallbackProbe p=new EmptyIndexFallbackProbe(Duration.ofNanos(10),clock::get);
        assertTrue(p.shouldFallback(()->{calls.incrementAndGet();return true;}));
        clock.set(9);assertTrue(p.shouldFallback(()->{fail("fresh snapshot must be reused");return false;}));
        clock.set(10);assertFalse(p.shouldFallback(()->{calls.incrementAndGet();return false;}));
        assertEquals(2,calls.get());
    }
    @Test void hitsAreReusableNonemptyEvidenceAndDoNotSlideFreshTtl() {
        AtomicLong clock=new AtomicLong();EmptyIndexFallbackProbe p=new EmptyIndexFallbackProbe(Duration.ofNanos(10),clock::get);
        p.observeNonEmpty();clock.set(9);p.observeNonEmpty();
        assertFalse(p.shouldFallback(()->{fail("nonempty evidence should avoid count");return true;}));
        clock.set(10);assertTrue(p.shouldFallback(()->true));
    }
    @Test void actualHitImmediatelyRevokesCachedFallback() {
        EmptyIndexFallbackProbe p=new EmptyIndexFallbackProbe(Duration.ofSeconds(10));
        assertTrue(p.shouldFallback(()->true));p.observeNonEmpty();
        assertFalse(p.shouldFallback(()->{fail("recovery hit must revoke fallback");return true;}));
    }
    @Test void expiredFallbackIsRechecked() {
        AtomicLong clock=new AtomicLong();EmptyIndexFallbackProbe p=new EmptyIndexFallbackProbe(Duration.ofNanos(10),clock::get);
        assertTrue(p.shouldFallback(()->true));clock.set(10);assertFalse(p.shouldFallback(()->false));
    }
    @Test void concurrentColdRequestsShareOneProbe() throws Exception {
        EmptyIndexFallbackProbe p=new EmptyIndexFallbackProbe(Duration.ofSeconds(10));AtomicInteger calls=new AtomicInteger();
        ExecutorService workers=Executors.newFixedThreadPool(4);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(int i=0;i<4;i++)futures.add(workers.submit(()->p.shouldFallback(()->{
                calls.incrementAndGet();entered.countDown();await(release);return true;
            })));
            assertTrue(entered.await(2,TimeUnit.SECONDS));release.countDown();
            for(Future<Boolean> f:futures)assertTrue(f.get(2,TimeUnit.SECONDS));
            assertEquals(1,calls.get());
        } finally {release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void hitDoesNotWaitForCountAndOldProbeCannotOverwriteItsEvidence() throws Exception {
        EmptyIndexFallbackProbe p=new EmptyIndexFallbackProbe(Duration.ofSeconds(10));
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            Future<Boolean> slow=workers.submit(()->p.shouldFallback(()->{entered.countDown();await(release);return true;}));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            workers.submit(p::observeNonEmpty).get(1,TimeUnit.SECONDS);
            release.countDown();assertFalse(slow.get(2,TimeUnit.SECONDS));
            assertFalse(p.shouldFallback(()->{fail("stale true was published");return true;}));
        } finally {release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void supplierFailureReleasesLockAndDoesNotPublishResult() {
        EmptyIndexFallbackProbe p=new EmptyIndexFallbackProbe(Duration.ofSeconds(10));
        assertThrows(IllegalStateException.class,()->p.shouldFallback(()->{throw new IllegalStateException("fixture");}));
        assertTrue(p.shouldFallback(()->true));
    }
    @Test void monotonicClockWrapIsHandledAndInvalidTtlRejected() {
        AtomicLong clock=new AtomicLong(Long.MAX_VALUE-3);
        EmptyIndexFallbackProbe p=new EmptyIndexFallbackProbe(Duration.ofNanos(10),clock::get);p.observeNonEmpty();
        clock.set(Long.MIN_VALUE+2);assertFalse(p.shouldFallback(()->{fail("only six nanos passed");return true;}));
        assertThrows(IllegalArgumentException.class,()->new EmptyIndexFallbackProbe(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,()->new EmptyIndexFallbackProbe(Duration.ofSeconds(-1)));
    }
    private static void await(CountDownLatch latch) {
        try {assertTrue(latch.await(3,TimeUnit.SECONDS));}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
}
