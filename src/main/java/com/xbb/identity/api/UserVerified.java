package com.xbb.identity.api;

import java.time.Instant;

public record UserVerified(long userId, String realName, Instant occurredAt) { }
