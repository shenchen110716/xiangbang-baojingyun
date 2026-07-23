package com.xbb.identity;

import com.xbb.identity.internal.VerificationCodeService;

/**
 * 测试辅助:让测试能拿到验证码。
 * 由测试类经 @Import 显式引入,不用 @Component —— 避免被生产环境的组件扫描捞进去。
 */
public class TestCodeAccessor {

    private final VerificationCodeService codeService;

    public TestCodeAccessor(VerificationCodeService codeService) {
        this.codeService = codeService;
    }

    public String issue(String phone) {
        return codeService.issue(phone);
    }
}
