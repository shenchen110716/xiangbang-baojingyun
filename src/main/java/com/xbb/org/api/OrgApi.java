package com.xbb.org.api;

import com.xbb.org.internal.Organization;
import java.util.Optional;

public interface OrgApi {

    record OrgView(long id, Organization.Type type, String name, String creditCode,
                    long legalRepUserId, Organization.Status status) { }

    long submit(Organization.Type type, String name, String creditCode, long legalRepUserId);

    void approve(long orgId, long callerUserId);

    void reject(long orgId, long callerUserId);

    Optional<OrgView> findById(long orgId);
}
