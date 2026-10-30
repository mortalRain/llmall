package com.llmall.common.concurrent;

import com.llmall.common.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UserContextTaskDecoratorTest {
    private final UserContextTaskDecorator decorator = new UserContextTaskDecorator();

    @AfterEach
    void cleanup() { UserContext.removeUser(); }

    @Test
    void capturedIdentityIsInstalledAndWorkerIdentityRestored() {
        UserContext.setUser(1L);
        Runnable task = decorator.decorate(() -> assertEquals(1L, UserContext.getUser()));
        UserContext.setUser(2L);
        task.run();
        assertEquals(2L, UserContext.getUser());
    }

    @Test
    void anonymousTaskCannotInheritWorkerIdentity() {
        Runnable task = decorator.decorate(() -> assertNull(UserContext.getUser()));
        UserContext.setUser(2L);
        task.run();
        assertEquals(2L, UserContext.getUser());
    }

    @Test
    void exceptionRestoresIdentityAndPropagates() {
        UserContext.setUser(1L);
        Runnable task = decorator.decorate(() -> { throw new IllegalStateException("failure"); });
        UserContext.setUser(2L);
        assertThrows(IllegalStateException.class, task::run);
        assertEquals(2L, UserContext.getUser());
    }

    @Test
    void nestedTaskRestoresOuterContextAndLeavesEmptyWorkerEmpty() {
        UserContext.setUser(1L);
        Runnable task = decorator.decorate(() -> {
            decorator.decorate(() -> UserContext.setUser(9L)).run();
            assertEquals(1L, UserContext.getUser());
        });
        UserContext.removeUser();
        task.run();
        assertNull(UserContext.getUser());
    }

    @Test
    void saturatedCallerRunsDoesNotClearRequestIdentity() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            pool.execute(() -> {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            pool.execute(() -> { }); // Fill the only queue slot, forcing the next task onto the caller.
            UserContext.setUser(1L);
            Thread caller = Thread.currentThread();
            pool.execute(decorator.decorate(() -> {
                assertSame(caller, Thread.currentThread());
                assertEquals(1L, UserContext.getUser());
            }));
            assertEquals(1L, UserContext.getUser());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
