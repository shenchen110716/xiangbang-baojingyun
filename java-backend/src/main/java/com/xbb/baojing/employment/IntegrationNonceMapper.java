package com.xbb.baojing.employment;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntegrationNonceMapper {
    String COLUMNS = "id, key_id as keyId, nonce, seen_at as seenAt";

    @Select("SELECT COUNT(*) FROM integration_nonces WHERE key_id = #{keyId} AND nonce = #{nonce}")
    int countByKeyAndNonce(String keyId, String nonce);

    // 唯一约束 (key_id, nonce) 才是真正拒绝重放的机制；插入失败即视为重放。
    @Insert("INSERT INTO integration_nonces (key_id, nonce, seen_at) VALUES (#{keyId}, #{nonce}, #{seenAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(IntegrationNonce nonce);
}
