package com.xbb.baojing.common;

/** Holds the result of authenticating the current request's JWT, resolved once
 * by JwtAuthFilter and read (possibly never, for public endpoints) by
 * CurrentUserArgumentResolver — mirrors FastAPI's Depends(current_user) being
 * opt-in per route rather than a blanket filter-level 401. */
public final class CurrentUserContext {
    private static final ThreadLocal<AuthResult> HOLDER = new ThreadLocal<>();

    public record AuthResult(User user, Integer errorStatus, String errorDetail) {
        static AuthResult ok(User user) { return new AuthResult(user, null, null); }
        static AuthResult fail(int status, String detail) { return new AuthResult(null, status, detail); }
    }

    public static void set(AuthResult result) { HOLDER.set(result); }
    public static AuthResult get() { return HOLDER.get(); }
    public static void clear() { HOLDER.remove(); }

    private CurrentUserContext() {}
}
