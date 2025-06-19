package com.openclassrooms.tourguide.configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService threadPoolExecutor() {

        int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        return Executors.newFixedThreadPool(threads);
    }
}
