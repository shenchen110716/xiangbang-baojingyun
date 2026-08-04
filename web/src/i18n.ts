/**
 * 后端返回值 → 中文的唯一词典。
 *
 * <p>为什么集中放一处:这些枚举散落在 10 多个页面里,各写各的必然出现
 * 同一个 ACCEPTED 在这页叫「已录用」、那页叫「已通过」。更糟的是漏译不会报错——
 * 界面照常显示,只是显示的是英文,而这种缺陷只能靠人一眼一眼看出来。
 *
 * <p>查不到时**原样返回**而不是显示空白:宁可露出一个英文,也不要让人对着空白猜。
 */

const STATUS: Record<string, string> = {
  // 报名 / 履约
  SUBMITTED: '待处理',
  ACCEPTED: '已录用',
  REJECTED: '已拒绝',
  COMPLETED: '已完成',
  // 岗位
  OPEN: '招聘中',
  CLOSED: '已关闭',
  // 组织
  PENDING: '待审核',
  APPROVED: '已通过',
  // 结算
  VOIDED: '已作废',
  SETTLED: '已结算',
  // 代发
  PAID: '已发放',
  FAILED: '发放失败',
  PROCESSING: '发放中',
  // 协议
  SIGNED: '已签署',
  // 佣金
  PAYABLE: '待支付',
}

const ORG_TYPE: Record<string, string> = {
  ENTERPRISE: '企业',
  FACTORY: '工厂',
  SERVICE_STATION: '服务站',
}

const ACCOUNT: Record<string, string> = {
  USER_FUNDS: '在途资金',
  PLATFORM_REVENUE: '平台收入',
  GUARANTEE_POOL: '担保资金池',
}

const ROLE: Record<string, string> = {
  PLATFORM_ADMIN: '平台管理员',
  PLATFORM_OPS: '平台运维',
}

const FACTOR: Record<string, string> = {
  SMS: '短信验证',
  FACE: '人脸识别',
}

/** 原始返回里的字段名 → 中文。用于把 JSON 渲染成人能读的键值表。 */
const FIELD: Record<string, string> = {
  id: '编号', jobId: '岗位', orgId: '组织', applicationId: '报名单',
  settlementId: '结算单', payoutId: '代发单', userId: '用户',
  applicantUserId: '应聘者', workerUserId: '工人', payeeUserId: '收款人',
  legalRepUserId: '法人代表', brokerUserId: '经纪人',
  title: '标题', description: '描述', name: '名称', content: '内容',
  creditCode: '统一社会信用代码', type: '类型', status: '状态',
  amountCents: '金额', wageCents: '日薪', balanceCents: '余额',
  expectedWageCents: '期望日薪',
  headcount: '名额', filledCount: '已占用', remainingSlots: '剩余名额',
  score: '分数', tags: '标签', comment: '评语', reason: '原因',
  voidReason: '作废原因', createdAt: '创建时间', paidAt: '发放时间',
  signedAt: '签署时间', approvedAt: '审核时间', read: '已读',
  contentHash: '内容摘要', providerRef: '第三方凭证号',
  templateKey: '模板', templateVersion: '模板版本',
  identityFactor: '身份因子', lat: '纬度', lon: '经度',
  mustTags: '必需标签', niceTags: '加分标签',
  count: '数量', domain: '域', eventId: '事件编号',
  retryCount: '重试次数', lastError: '最近错误', payload: '事件内容',
  taxCertNo: '完税凭证号', idempotencyKey: '幂等键',
}

const pick = (dict: Record<string, string>, v: unknown) =>
  typeof v === 'string' && dict[v] ? dict[v] : String(v ?? '—')

export const zhStatus  = (v: unknown) => pick(STATUS, v)
export const zhOrgType = (v: unknown) => pick(ORG_TYPE, v)
export const zhAccount = (v: unknown) => pick(ACCOUNT, v)
export const zhRole    = (v: unknown) => pick(ROLE, v)
export const zhFactor  = (v: unknown) => pick(FACTOR, v)
export const zhField   = (k: string) => FIELD[k] ?? k

/** 状态 → 标签配色。绿=好、黄=进行中、红=坏,不认识的走中性。 */
export function statusTone(v: unknown): '' | 'ok' | 'warn' | 'bad' {
  switch (v) {
    case 'ACCEPTED': case 'COMPLETED': case 'APPROVED': case 'PAID':
    case 'OPEN': case 'SIGNED': case 'SETTLED':
      return 'ok'
    case 'SUBMITTED': case 'PENDING': case 'PROCESSING': case 'PAYABLE':
      return 'warn'
    case 'REJECTED': case 'FAILED': case 'VOIDED':
      return 'bad'
    default:
      return ''
  }
}

/** 以「分」为单位的字段,渲染时要转成元。 */
export const isMoneyField = (k: string) => /Cents$/.test(k)
