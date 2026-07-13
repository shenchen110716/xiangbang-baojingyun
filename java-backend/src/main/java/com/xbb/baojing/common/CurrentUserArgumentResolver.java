package com.xbb.baojing.common;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Any controller method parameter of type User is resolved from the JWT
 * verified by JwtAuthFilter, throwing the same 401/403 ApiException Python's
 * current_user() dependency would have raised. This is what makes adding
 * `User user` to a controller method signature equivalent to FastAPI's
 * `user: User = Depends(current_user)`. */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(User.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        CurrentUserContext.AuthResult result = CurrentUserContext.get();
        if (result == null || result.user() == null) {
            int status = result != null && result.errorStatus() != null ? result.errorStatus() : 401;
            String detail = result != null && result.errorDetail() != null ? result.errorDetail() : "请先登录";
            throw new ApiException(status, detail);
        }
        return result.user();
    }
}
