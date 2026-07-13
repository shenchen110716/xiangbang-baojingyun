package com.xbb.baojing.common;

import org.springframework.http.HttpStatus;

/** Mirrors FastAPI's HTTPException(status_code, detail) — the global handler
 * renders it as {"detail": message}, exactly what web/src/api/client.ts expects. */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String detail) {
        super(detail);
        this.status = status;
    }

    public static ApiException notFound(String detail) { return new ApiException(404, detail); }
    public static ApiException badRequest(String detail) { return new ApiException(400, detail); }
    public static ApiException forbidden(String detail) { return new ApiException(403, detail); }
    public static ApiException unauthorized(String detail) { return new ApiException(401, detail); }
    public static ApiException conflict(String detail) { return new ApiException(409, detail); }

    public int getStatus() { return status; }
    public HttpStatus getHttpStatus() { return HttpStatus.valueOf(status); }
}
