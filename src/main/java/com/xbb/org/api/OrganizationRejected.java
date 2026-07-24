package com.xbb.org.api;

import java.time.Instant;

public record OrganizationRejected(long orgId, Instant occurredAt) { }
