package com.xbb.identity.api;

import java.time.Instant;

public record UserRegistered(long userId, String phone, String inviteCode, Instant occurredAt) { }
