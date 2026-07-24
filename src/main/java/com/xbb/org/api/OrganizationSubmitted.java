package com.xbb.org.api;

import java.time.Instant;

public record OrganizationSubmitted(long orgId, long legalRepUserId, Instant occurredAt) { }
