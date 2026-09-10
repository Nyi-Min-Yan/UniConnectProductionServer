package com.unicconnect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UniConnectApplication {

    public static void main(String[] args) {
        SpringApplication.run(UniConnectApplication.class, args);
    }
}