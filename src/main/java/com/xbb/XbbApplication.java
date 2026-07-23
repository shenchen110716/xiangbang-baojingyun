package com.xbb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;

@Modulith
@SpringBootApplication
public class XbbApplication {
    public static void main(String[] args) {
        SpringApplication.run(XbbApplication.class, args);
    }
}
