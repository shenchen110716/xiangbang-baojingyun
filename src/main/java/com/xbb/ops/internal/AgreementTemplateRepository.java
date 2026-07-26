package com.xbb.ops.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgreementTemplateRepository extends JpaRepository<AgreementTemplateRecord, Long> {

    Optional<AgreementTemplateRecord> findByTemplateKeyAndActiveTrue(String templateKey);

    Optional<AgreementTemplateRecord> findByTemplateKeyAndVersion(String templateKey, int version);

    Optional<AgreementTemplateRecord> findFirstByTemplateKeyOrderByVersionDesc(String templateKey);
}
