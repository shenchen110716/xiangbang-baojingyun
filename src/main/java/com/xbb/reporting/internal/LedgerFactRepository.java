package com.xbb.reporting.internal;

import com.xbb.reporting.api.ReportingApi;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface LedgerFactRepository extends JpaRepository<LedgerFact, Long> {

    List<LedgerFact> findByDimension(ReportingApi.Dimension dimension);

    Optional<LedgerFact> findByDimensionAndDimensionIdAndSourceAndReferenceId(
            ReportingApi.Dimension dimension, long dimensionId, String source, Long referenceId);
}
