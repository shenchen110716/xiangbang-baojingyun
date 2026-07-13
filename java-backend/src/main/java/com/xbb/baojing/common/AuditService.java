package com.xbb.baojing.common;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuditService {
    private final AuditLogMapper mapper;

    public AuditService(AuditLogMapper mapper) { this.mapper = mapper; }

    public void log(User user, String action, String objectType, String objectId) {
        log(user, action, objectType, objectId, "");
    }

    public void log(User user, String action, String objectType, String objectId, String detail) {
        AuditLog entry = new AuditLog();
        entry.setUserId(user.getId());
        entry.setAction(action);
        entry.setObjectType(objectType);
        entry.setObjectId(objectId);
        entry.setDetail(detail == null ? "" : detail);
        entry.setCreatedAt(LocalDateTime.now());
        mapper.insert(entry);
    }
}
