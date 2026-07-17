package com.xbb.baojing.employment;

import java.time.LocalDateTime;

/** Java mirror of the Alembic-owned employment_fact_matches table. Kept apart
 * from EmploymentFact so candidate matching noise never pollutes the
 * authoritative fact (§6.3). match_status: matched|pending|ambiguous|rejected.
 * match_method: external_employment_id|identity_hire|employee_no|manual. */
public class EmploymentFactMatch {
    private Integer id;
    private Integer employmentFactId;
    private String matchStatus;
    private String matchMethod;
    private Integer candidatePersonId;
    private Integer matchedPersonId;
    private double confidence = 0;
    private String reason = "";
    private Integer confirmedBy;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public Integer getEmploymentFactId() { return employmentFactId; }
    public void setEmploymentFactId(Integer v) { employmentFactId = v; }
    public String getMatchStatus() { return matchStatus; }
    public void setMatchStatus(String v) { matchStatus = v; }
    public String getMatchMethod() { return matchMethod; }
    public void setMatchMethod(String v) { matchMethod = v; }
    public Integer getCandidatePersonId() { return candidatePersonId; }
    public void setCandidatePersonId(Integer v) { candidatePersonId = v; }
    public Integer getMatchedPersonId() { return matchedPersonId; }
    public void setMatchedPersonId(Integer v) { matchedPersonId = v; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double v) { confidence = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { reason = v; }
    public Integer getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(Integer v) { confirmedBy = v; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime v) { confirmedAt = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
