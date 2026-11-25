package com.memora;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MemoraBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(MemoraBackendApplication.class, args);
    }
}
