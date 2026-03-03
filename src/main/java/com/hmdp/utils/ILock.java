package com.hmdp.utils;

public interface ILock {
    /**
     * 加锁
     * @param timeoutSec
     * @return
     */
    boolean tryLock(long timeoutSec);

    /**
     * 解锁
     */
    void unlock();
}
