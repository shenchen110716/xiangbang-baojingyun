package com.xbb.mall.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface MallOrderRepository extends JpaRepository<MallOrder, Long> {

    Optional<MallOrder> findByVoucherCode(String voucherCode);
}
