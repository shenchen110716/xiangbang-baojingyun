package com.xbb.baojing.employment;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

/** Java mirror of the Alembic-owned integration_api_keys table — the
 * authenticated identity for the §7.3 external employment event API.
 * secret_cipher is Fernet ciphertext, never plaintext; it must never reach an
 * HTTP response (mirrors User.passwordHash's @JsonIgnore). */
public class IntegrationApiKey {
    private Integer id;
    private Integer enterpriseId;
    private String name = "";
    private String keyId;
    private String secretCipher;
    private String allowedEmployerIds = "";
    private boolean active = true;
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public Integer getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Integer v) { enterpriseId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String v) { keyId = v; }
    @JsonIgnore
    public String getSecretCipher() { return secretCipher; }
    public void setSecretCipher(String v) { secretCipher = v; }
    public String getAllowedEmployerIds() { return allowedEmployerIds; }
    public void setAllowedEmployerIds(String v) { allowedEmployerIds = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { active = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { createdAt = v; }
}
