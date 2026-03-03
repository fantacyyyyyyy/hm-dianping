package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    public SimpleRedisLock(String name, StringRedisTemplate redisTemplate) {
        this.name = name;
        this.stringRedisTemplate = redisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String value = ID_PREFIX + Thread.currentThread().getName();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, value, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }


    @Override
    public void unlock() {
        String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
        String s = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if(ID_PREFIX.equals(s)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
