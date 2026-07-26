-- 劳务协议模板首版入库(§6.2"模板由运营维护、法务审定")。原本硬编码在
-- agreement.AgreementTemplate 里,那里留过一句"真做的时候这个类换成读模板表即可"。
--
-- 占位符用 {名字} 而不是 %d:模板是给运营和法务改的,位置参数一旦顺序错了
-- 要么渲染出张冠李戴的协议、要么直接抛异常,而错处一眼看不出来。
INSERT INTO ops.agreement_template (template_key, version, body, active) VALUES
    ('LABOR_AGREEMENT', 1, '灵活用工劳务协议

甲方(用工单位):组织 #{orgId}
乙方(劳动者):用户 #{workerUserId}

一、岗位:岗位 #{jobId}
二、劳务报酬:每单位 {wage} 元,由甲方按平台结算规则支付
三、双方权利义务依照平台规则及国家相关法律法规执行
四、本协议经乙方电子签署后生效

(关联履约单 #{applicationId})
', TRUE);
