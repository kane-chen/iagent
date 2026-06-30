// src/main/java/com/finance/util/HttpUtils.java
package io.invest.iagent.utils;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP工具类，实现SEC 10 req/s速率限制
 */
public class HttpUtils {
    private static final AtomicLong LAST_REQUEST_TIME = new AtomicLong(0);
    private static final long REQ_INTERVAL_MS = 100; // 10 req/s = 每100ms一个请求

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    /**
     * 带速率限制的HTTP GET请求
     */
    public static Response get(Request request) throws IOException {
        // 速率限制：确保两次请求间隔至少100ms
        long currentTime = System.currentTimeMillis();
        long lastTime = LAST_REQUEST_TIME.get();
        long sleepTime = REQ_INTERVAL_MS - (currentTime - lastTime);
        if (sleepTime > 0) {
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("请求被中断", e);
            }
        }
        LAST_REQUEST_TIME.set(System.currentTimeMillis());

        return CLIENT.newCall(request).execute();
    }

    /**
     * 获取SEC请求的User-Agent（从环境变量读取）
     */
    public static String getSecUserAgent() {
        String userAgent = System.getenv("SEC_USER_AGENT");
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalStateException("SEC_USER_AGENT环境变量未设置（格式：Company/contact@email.com）");
        }
        return userAgent;
    }
}



