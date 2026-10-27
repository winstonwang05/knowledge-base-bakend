package com.knowledge.base.common.utils;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @description: 雪花算法ID生成器
 * <p>采用Twitter Snowflake算法实现分布式ID生成，保证全局唯一性</p>
 * <p>生成19位数字的Long类型ID</p>
 *
 * <p>ID结构：1位符号位 + 41位时间戳 + 10位机器ID + 12位序列号</p>
 * @author: Winston
 * @date: 2026/10/25 9:27
 * @version: 1.0
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {
    private static final long START_TIMESTAMP = 1704067200000L;

    private static final long DATACENTER_ID_BITS = 5L;

    private static final long WORKER_ID_BITS = 5L;

    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);

    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;

    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private final long datacenterId;

    private final long workerId;

    private long sequence = 0L;

    private long lastTimestamp = -1L;
    // 单例模式，单机状态下全局共享
    private static volatile SnowflakeIdGenerator instance;

    @PostConstruct
    public void init() {
        log.info("SnowflakeIdGenerator initialized with datacenterId: {}, workerId: {}", datacenterId, workerId);
    }

    public SnowflakeIdGenerator() {
        this(1L, 1L);
    }

    public SnowflakeIdGenerator(long datacenterId, long workerId) {
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException(
                    String.format("Datacenter ID can't be greater than %d or less than 0", MAX_DATACENTER_ID));
        }
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException(
                    String.format("Worker ID can't be greater than %d or less than 0", MAX_WORKER_ID));
        }
        this.datacenterId = datacenterId;
        this.workerId = workerId;
    }

    // double-check单例
    public static SnowflakeIdGenerator getInstance(long datacenterId, long workerId) {
        if (instance == null) {
            synchronized (SnowflakeIdGenerator.class) {
                if (instance == null) {
                    instance =new SnowflakeIdGenerator(datacenterId, workerId);
                }
            }
        }
        return instance;


    }


    public static SnowflakeIdGenerator getInstance() {
        return getInstance(1L, 1L);
    }

    // 生成id
    public synchronized long nextId() {
        // 1.获取当前时间戳
        long currentTimestamp = getCurrentTimestamp();
        if (currentTimestamp < lastTimestamp) {
            // 2.如果出现时间回拨，小于5毫秒，则让线程睡差值两倍
            long offset = lastTimestamp - currentTimestamp;
            if (offset < 5) {
                try {
                    Thread.sleep(offset << 1);
                    currentTimestamp = getCurrentTimestamp();
                    if (currentTimestamp < lastTimestamp) {
                        throw new RuntimeException("Clock moved backwards. Refusing to generate id");
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException("Clock moved backwards. Refusing to generate id");
                }
            } else {
                // 3.否则直接抛出异常
                throw new RuntimeException("Clock moved backwards. Refusing to generate id");
            }
        }
        // 如果当前时间戳等于上一次时间戳，递增序列号
        if (currentTimestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                currentTimestamp     = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        // 序列号满直接用下一个时间戳
        lastTimestamp = currentTimestamp;

        return ((currentTimestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;

    }

    /**
     * 死循环获取下一个时间戳
     * @param lastTimestamp
     * @return
     */
    private long waitNextMillis(long lastTimestamp) {
        long timestamp = getCurrentTimestamp();
        while (timestamp <= lastTimestamp) {
            timestamp = getCurrentTimestamp();
        }
        return timestamp;
    }

    private long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

}
