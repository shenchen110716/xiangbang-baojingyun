package com.xbb.org.api;

import java.time.Instant;

public record OrganizationApproved(long orgId, Instant occurredAt) { }
