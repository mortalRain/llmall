package com.llmall.shop.cache;

import com.llmall.api.dto.ShopDTO;
import com.llmall.common.exception.CommonException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Coalesces overlapping fallback reads, without retaining results after the load finishes. */
@Component
public class ShopCacheFallback {
    private final Semaphore permits;
    private final long waitMillis;
    private final ConcurrentMap<Long, CompletableFuture<ShopDTO>> loads = new ConcurrentHashMap<>();

    public ShopCacheFallback(@Value("${llmall.shop.cache.fallback-max-concurrency:4}") int maxConcurrent,
                             @Value("${llmall.shop.cache.fallback-wait-millis:500}") long waitMillis) {
        if (maxConcurrent < 1 || maxConcurrent > 64 || waitMillis < 1 || waitMillis > 5000) {
            throw new IllegalArgumentException("Invalid shop fallback limits");
        }
        permits = new Semaphore(maxConcurrent);
        this.waitMillis = waitMillis;
    }

    public ShopDTO load(Long id, Supplier<ShopDTO> source) {
        if (Thread.currentThread().isInterrupted()) {
            throw busy();
        }
        CompletableFuture<ShopDTO> own = new CompletableFuture<>();
        CompletableFuture<ShopDTO> existing = loads.putIfAbsent(id, own);
        if (existing != null) {
            return await(existing);
        }
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire();
            if (!acquired) {
                throw busy();
            }
            ShopDTO result = source.get();
            own.complete(result);
            return result;
        } catch (RuntimeException | Error e) {
            own.completeExceptionally(e);
            throw e;
        } finally {
            if (acquired) {
                permits.release();
            }
            loads.remove(id, own);
        }
    }

    private ShopDTO await(CompletableFuture<ShopDTO> load) {
        try {
            return load.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy();
        } catch (TimeoutException e) {
            throw busy();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Shop fallback load failed", cause);
        }
    }

    private CommonException busy() {
        return new CommonException("门店详情回源繁忙，请稍后重试", 503);
    }
}
