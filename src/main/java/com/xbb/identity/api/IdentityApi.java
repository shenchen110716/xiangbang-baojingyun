package com.xbb.identity.api;

import java.util.Optional;

public interface IdentityApi {

    record LoginResult(long userId, String token, boolean newUser) { }

    record UserView(long id, String phone, boolean verified, String inviteCode) { }

    LoginResult loginByPhone(String phone, String code);

    Optional<UserView> findVerifiedUser(long userId);
}
