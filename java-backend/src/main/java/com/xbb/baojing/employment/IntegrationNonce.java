package com.xbb.baojing.employment;

import java.time.LocalDateTime;

/** Java mirror of the Alembic-owned integration_nonces table. The
 * (key_id, nonce) unique index is what actually rejects a replay — an
 * "already seen?" lookup in application code would race under concurrency. */
public class IntegrationNonce {
    private Integer id;
    private String keyId;
    private String nonce;
    private LocalDateTime seenAt;

    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String v) { keyId = v; }
    public String getNonce() { return nonce; }
    public void setNonce(String v) { nonce = v; }
    public LocalDateTime getSeenAt() { return seenAt; }
    public void setSeenAt(LocalDateTime v) { seenAt = v; }
}
