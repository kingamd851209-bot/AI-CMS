package com.xinhongru.config;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @Description 线程池配置 @ClassName EventPoolConfig @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/8/31 8:59
 */
@Slf4j
@Configuration
@EnableAsync
public class EventPoolConfig {
    /** 获取CPU核心数 */
    private static final Integer CPU_CORE_NUM = Runtime.getRuntime().availableProcessors();

    @Bean
    public ExecutorService asyncServiceExecutor() {
        ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        // 设置核心线程数
        threadPoolTaskExecutor.setCorePoolSize(CPU_CORE_NUM * 2);
        // 设置最大线程数
        threadPoolTaskExecutor.setMaxPoolSize(CPU_CORE_NUM * 4);
        // 设置队列大小
        threadPoolTaskExecutor.setQueueCapacity(1000);
        // 设置线程名前缀
        threadPoolTaskExecutor.setThreadNamePrefix("cms-executor-");
        // 设置线程活跃时间(秒)
        threadPoolTaskExecutor.setKeepAliveSeconds(60);
        // 等待所有任务结束后再关闭线程池
        threadPoolTaskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        // 设置拒绝策略
        // ThreadPoolExecutor.AbortPolicy:丢弃任务并抛出RejectedExecutionException异常。
        // ThreadPoolExecutor.DiscardPolicy：也是丢弃任务，但是不抛出异常。
        // ThreadPoolExecutor.DiscardOldestPolicy：丢弃队列最前面的任务，然后重新尝试执行任务（重复此过程）
        // ThreadPoolExecutor.CallerRunsPolicy：由调用线程处理该任务
        threadPoolTaskExecutor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.CallerRunsPolicy());
        // 执行初始化
        threadPoolTaskExecutor.initialize();
        return threadPoolTaskExecutor.getThreadPoolExecutor();
    }
}
