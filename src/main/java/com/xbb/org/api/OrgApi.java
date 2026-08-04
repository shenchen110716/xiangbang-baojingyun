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

    /**
      * 组织详情。含统一社会信用代码与法人代表,只有法人代表本人或平台运维看得到。
      *
      * <p>招聘信息本身是公开的(见 JobApi),但"这家组织的信用代码和法人是谁"不是 ——
      * 那是把人和企业对应起来的东西。
      */
    Optional<OrgView> findById(long orgId, long callerUserId);

    /** 某人作为法人代表的组织列表。 */
    List<OrgView> listByLegalRep(long legalRepUserId);

    /**
     * 待审核组织队列。要 {@link com.xbb.identity.api.Role#PLATFORM_OPS} ——
     * 这是平台的活儿,没有"归属"可查,只能靠角色(见铁律 5)。
     */
    List<OrgView> listPending(long callerUserId);
}
