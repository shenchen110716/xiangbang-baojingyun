package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

interface BrokerRepository extends JpaRepository<Broker, Long> { 
    /** 某人的直接下级。降级时要把他们上提一层。 */
    List<Broker> findByParentUserId(long parentUserId);

    /** 某服务站下的业务员。 */
    List<Broker> findByStationOrgIdOrderByUserIdAsc(long stationOrgId);

    /**
     * 降级候选:活跃时间早于阈值、**有上级**(根业务员豁免)、且当前是 ACTIVE。
     * 老系统的条件是 `lastActiveTime <= 阈值 AND parBrokerId <> 0`,这里等价,
     * 多一个 status 过滤是因为我们不物理删除,已降级的不该被反复处理。
     */
    List<Broker> findByLastActiveAtBeforeAndParentUserIdIsNotNullAndStatus(
            Instant threshold, Broker.Status status);
}
