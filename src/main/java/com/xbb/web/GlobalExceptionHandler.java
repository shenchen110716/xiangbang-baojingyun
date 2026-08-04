package com.xbb.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 三个域的 Controller(Identity/Org/Job)原本各自复制了一份
 * IllegalArgumentException/IllegalStateException 的 @ExceptionHandler
 * (审计报告点名的重复)。这里统一收口,顺便补上两类之前完全没处理、
 * 会直接漏成裸 500 的异常:数据库唯一约束冲突(并发注册/绑定撞车)、
 * 乐观锁冲突(并发审核撞车)。
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    /**
     * 权限不足是 403,不能混进 IllegalStateException 的 409——
     * 调用方要能区分"业务状态不对"和"你没这个权限"。
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, String>> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<Map<String, String>> concurrentModification(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(409).body(Map.of("error", "数据刚被其他请求修改,请刷新后重试"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> constraintViolation(DataIntegrityViolationException e) {
        // 并发请求抢占了同一个唯一约束(手机号/身份证号/统一社会信用代码等)——
        // 具体是哪个字段留给客户端根据业务上下文自行判断,这里只保证不是裸 500。
        return ResponseEntity.status(409).body(Map.of("error", "数据冲突,可能是重复提交,请重试"));
    }

    /**
     * 请求体字段校验失败(@Valid)。
     *
     * <p>不处理它的后果不是"消息不好看",而是**返回 401**:
     * 未处理的异常会被 Spring Boot 转发到 /error,而 ERROR 分派上 JWT 过滤器不运行
     * (OncePerRequestFilter 默认只跑 REQUEST 分派),于是那次请求变成未认证,
     * 被 anyRequest().authenticated() 挡成 401。
     *
     * <p>前端见 401 会清掉登录态 —— 也就是说**用户少填一个必填字段就被踢下线**,
     * 而且看到的提示是"登录已失效"。线上打真实端点才发现:
     * 无理由改参数返回的是 401 不是 400。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> validationFailed(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst().orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /** 请求体缺失或不是合法 JSON。同样会走 /error,同样要在这里截住。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求内容格式不正确"));
    }

    /** 控制器显式抛的状态异常(如取码端点的 404)。 */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> statusException(ResponseStatusException e) {
        String reason = e.getReason();
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("error", reason == null ? "" : reason));
    }
}
