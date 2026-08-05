package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShareConversionRepository extends JpaRepository<ShareConversion, Long> {
    Optional<ShareConversion> findByConvertedUserId(long convertedUserId);
    List<ShareConversion> findByShareIdIn(List<Long> shareIds);
}
