package com.xbb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.modulith.Modulith;

@Modulith
@SpringBootApplication
@EnableScheduling
public class XbbApplication {
    public static void main(String[] args) {
        SpringApplication.run(XbbApplication.class, args);
    }
}
