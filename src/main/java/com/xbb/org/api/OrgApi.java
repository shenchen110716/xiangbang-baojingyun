package com.xbb.org.api;

import com.xbb.org.internal.Organization;
import java.util.List;
import java.util.Optional;

public interface OrgApi {

    record OrgView(long id, com.xbb.org.api.OrgType type, String name, String creditCode,
                    long legalRepUserId, Organization.Status status) { }

    long submit(com.xbb.org.api.OrgType type, String name, String creditCode, long legalRepUserId);

    void approve(long orgId, long callerUserId);

    void reject(long orgId, long callerUserId);

    Optional<OrgView> findById(long orgId);

    /** 某人作为法人代表的组织列表。 */
    List<OrgView> listByLegalRep(long legalRepUserId);

    /**
     * 待审核组织队列。要 {@link com.xbb.identity.api.Role#PLATFORM_OPS} ——
     * 这是平台的活儿,没有"归属"可查,只能靠角色(见铁律 5)。
     */
    List<OrgView> listPending(long callerUserId);
}
