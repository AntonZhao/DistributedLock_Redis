package com.anton;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DistributedLockRedisApplicationTests {

    @Test
    void contextLoads() {
        System.out.println("111");
    }

    @Autowired
    private RedisLock redisLock;

    private String lock1Key = "lock1";

    private String lock1Value = UUID.randomUUID().toString();

//    @BeforeAll
//    public static void resetRedisStatus() {
//        redisLock.flushAll();
//        System.out.println("lalla");
//    }

    // 一个线程拿到锁，另外一个线程会不会立刻拿到锁  lock会阻塞直到等待时间结束
    @Test
    public void shouldWaitWhenOneUsingBlockedLockAndTheOtherOneWantToUse() throws InterruptedException {
        redisLock.flushAll();

        Thread t = new Thread(() -> {
            try {
                redisLock.lock(lock1Key, lock1Value);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        t.start();
        t.join();

        long startTime = System.currentTimeMillis();
        redisLock.lock(lock1Key, lock1Value, 1000);
        System.out.println(System.currentTimeMillis() - startTime);
        assertThat(System.currentTimeMillis() - startTime).isBetween(500L, 1500L);
    }

    // tryLock 拿不到直接返回，所以应该是false
    @Test
    public void shouldReturnFalseWhenOneUsingNonBlockedLockAndTheOtherOneWantToUse() throws InterruptedException {
        redisLock.flushAll();

        Thread t = new Thread(() -> {
            try {
                redisLock.tryLock(lock1Key, lock1Value);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        t.start();
        t.join();

        boolean condition = redisLock.tryLock(lock1Key, lock1Value);
        System.out.println(condition);
        assertFalse(condition);
    }

    @Test
    public void shouldResumeCurrentTaskAfterOtherProcessReleaseLock() throws InterruptedException {
        redisLock.flushAll();

        final boolean[] t1Done = {false};

        Thread t1 = new Thread(() -> {
            try {
                redisLock.lock(lock1Key, lock1Value);
                assertTrue(redisLock.isLocked(lock1Key));
                Thread.sleep(10000);
                t1Done[0] = true;
                redisLock.unlock(lock1Key, lock1Value);
                assertFalse(redisLock.isLocked(lock1Key));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        t1.start();

        Thread.sleep(100); // wait t1 get lock1

        Thread t2 = new Thread(() -> {
            try {
                assertFalse(t1Done[0]);
                redisLock.lock(lock1Key, lock1Value);
                assertTrue(t1Done[0]);
                Thread.sleep(1000);
                redisLock.unlock(lock1Key, lock1Value);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        t2.start();

        t1.join();
        System.out.println("t1可算执行完了");
        t2.join();
    }

    @Test
    public void shouldReturnTrueWhenReleaseOwnLock() throws InterruptedException {
        redisLock.flushAll();

        redisLock.lock(lock1Key, lock1Value);
        assertTrue(redisLock.unlock(lock1Key, lock1Value));
    }

    @Test
    public void shouldReturnFalseWhenReleaseOthersLock() throws InterruptedException {
        redisLock.flushAll();

        redisLock.lock(lock1Key, lock1Value);
        assertFalse(redisLock.unlock(lock1Key, "other lock's value"));
    }



}
