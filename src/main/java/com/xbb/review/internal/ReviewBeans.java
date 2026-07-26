package com.xbb.review.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ReviewBeans {

    @Bean
    CreditCalculator creditCalculator() {
        return new CreditCalculator();
    }
}
