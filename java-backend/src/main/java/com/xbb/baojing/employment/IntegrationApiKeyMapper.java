package com.xbb.baojing.employment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntegrationApiKeyMapper {
    String COLUMNS = "id, enterprise_id as enterpriseId, name, key_id as keyId, " +
            "secret_cipher as secretCipher, allowed_employer_ids as allowedEmployerIds, active, " +
            "created_at as createdAt";

    @Select("SELECT " + COLUMNS + " FROM integration_api_keys WHERE key_id = #{keyId} AND active = true")
    IntegrationApiKey findActiveByKeyId(String keyId);
}
