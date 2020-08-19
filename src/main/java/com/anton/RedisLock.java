package com.anton;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;

/**
 * lock -> 调用后一直阻塞到获得锁
 * tryLock -> 尝试是否能获得锁 如果不能获得立即返回
 * lockInterruptibly -> 调用后一直阻塞到获得锁 但是接受中断信号(题主用过Thread#sleep吧)
 *
 *   private static final String XX = "xx";  // 表示当key存在时才set值
 *   private static final String NX = "nx";  // 表示key不存在时才set值
 *   private static final String PX = "px";  // 表示过期时间单位是毫秒
 *   private static final String EX = "ex";  // 表示过期时间单位是秒
 */

public class RedisLock {
    private static final String SET_IF_NOT_EXIST = "NX";
    private static final String SET_WITH_EXPIRE_TIME = "PX";
    private static final String LOCK_PREFIX = "dlock_";
    private static final String LOCK_MSG = "OK";
    private static final Long UNLOCK_MSG = 1L;
    private static final int DEFAULT_EXPIRE_TIME = 60 * 1000;
    private static final long DEFAULT_SLEEP_TIME = 100;

    private JedisPool jedisPool;


    public RedisLock(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    // 尝试获取锁
    public void lock(String key, String value) throws InterruptedException {
        Jedis jedis = jedisPool.getResource();
        while (true) {
            if (setLockToRedis(key, value, jedis)) return;
        }
    }

    // 在一定时间范围内去获取锁
    public void lock(String key, String value, int timeout) throws InterruptedException {
        Jedis jedis = jedisPool.getResource();
        while (timeout >= 0) {
            if (setLockToRedis(key, value, jedis)) return;
            timeout -= DEFAULT_SLEEP_TIME;
        }
    }

    public boolean tryLock(String key, String value) throws InterruptedException {
        Jedis jedis = jedisPool.getResource();
        return setLockToRedis(key, value, jedis);
    }

    private boolean setLockToRedis(String key, String value, Jedis jedis) throws InterruptedException {
        // Redis3.0 需要设置一个 SetParams
        String result = jedis.set(LOCK_PREFIX + key, value, new SetParams().nx().px(DEFAULT_EXPIRE_TIME));
        if (LOCK_MSG.equals(result)) {
            jedis.close();
            return true;
        }
        Thread.sleep(DEFAULT_SLEEP_TIME);
        return false;
    }

    public boolean unlock(String key, String value) {
        Jedis jedis = jedisPool.getResource();

        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        Object result = jedis.eval(script, Collections.singletonList(LOCK_PREFIX + key), Collections.singletonList(value));

        jedis.close();
        return UNLOCK_MSG.equals(result);
    }

    public boolean isLocked(String key) {
        Jedis jedis = jedisPool.getResource();
        String result = jedis.get(LOCK_PREFIX + key);
        jedis.close();
        return result != null;
    }

    public void flushAll() {
        Jedis jedis = jedisPool.getResource();
        jedis.flushAll();
    }

}
