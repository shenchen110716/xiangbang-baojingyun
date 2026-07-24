package com.xbb.security;

/** JWT 校验通过后的调用者身份,由 {@link JwtAuthenticationFilter} 放进 SecurityContext。 */
public record AuthenticatedUser(long userId) { }
