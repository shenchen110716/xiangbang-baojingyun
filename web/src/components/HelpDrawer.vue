<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

type Role = 'admin' | 'enterprise' | 'salesperson' | 'insurer'

interface HelpBlock { h?: string; p?: string; list?: string[] }
interface HelpSection { id: string; title: string; roles: Role[]; blocks: HelpBlock[] }

const props = defineProps<{ role?: Role }>()
const visible = defineModel<boolean>({ required: true })
const auth = useAuthStore()

const ALL: Role[] = ['admin', 'enterprise', 'salesperson', 'insurer']

const currentRole = computed<Role>(() =>
  props.role ?? (auth.user?.role as Role) ?? 'enterprise',
)

const roleLabel = computed(() => ({
  admin: '平台管理员', enterprise: '投保单位', salesperson: '业务员', insurer: '保司',
}[currentRole.value]))

const sections: HelpSection[] = [
  {
    id: 'login', title: '登录与角色', roles: ALL,
    blocks: [
      { p: '登录需选择对应门户，账号角色必须与门户匹配，否则会提示"该账号不是 XX 账号"。' },
      { list: [
        '平台管理员 → 总后台门户',
        '单位主管 / 项目经理 → 投保单位端门户',
        '业务员 → 业务员端门户',
        '保司账号 → 保司端门户',
      ] },
      { p: '同一手机号若同时是多个投保单位的主管，登录后可在应用内一键切换账号。修改密码在右上角头像菜单中。' },
    ],
  },
  {
    id: 'admin-ops', title: '平台端操作指南', roles: ['admin'],
    blocks: [
      { h: '工作台与经营大屏', p: '查看在保人数、待审核、余额预警、待确认停保等关键指标与消耗趋势。' },
      { h: '保险产品与费率', list: [
        '录入产品：保司、保障内容、计费方式（按天/按月）、基础价与职业类别费率',
        '成本价、结算价、利润为内部字段，不向投保单位与业务员暴露',
      ] },
      { h: '投保单位管理', list: [
        '创建单位、维护预警阈值与使用费日单价',
        '为单位开通管理员：第一个管理员自动成为"单位主管(owner)"，后续为"项目经理"',
        '变更负责人：在操作员管理中将某项目经理提升为主管（每单位仅一个在册主管）',
      ] },
      { h: '保司与分账户充值审核', p: '维护保险公司档案；投保单位按保司分账户提交充值申请，平台审核通过后余额入账（保费/使用费账户分别充值）。' },
      { h: '保司主体与登录账号', p: '在"保司主体管理"新建保司档案（名称、联系人、信用代码等），并为其创建保司端登录账号、重置密码；保司账号无自助注册入口，必须由平台开通。' },
      { h: '参停保中心与待确认停保', p: '集中查看参停保记录与保司回执；单位保费欠费时生成待确认停保任务，管理员确认后才真正停保。' },
      { h: '业务员与推广佣金', p: '创建/启停业务员，配置业务员—单位—产品的佣金关系（返佣或加价），一个单位只关联一个业务员。' },
      { h: '参保及时率', p: '基于用工事实与参停保操作计算及时率、反馈率与责任归属；未匹配/争议记录进入数据质量队列，不计入正式口径。' },
      { h: '报表 · 资金 · 审计', p: '报表中心与 Excel 导出、支付下单/回调入账/对账、关键操作审计留痕。' },
    ],
  },
  {
    id: 'ent-ops', title: '投保单位端操作指南', roles: ['enterprise'],
    blocks: [
      { h: '主管与项目经理', list: [
        '单位主管：管理本单位全部用工单位、员工、账户，并在操作员管理中新增项目经理并授权用工单位',
        '项目经理：仅能访问被授权的实际用工单位；未授权时相关列表安全地为空（非报错）',
      ] },
      { h: '用工单位与投保岗位', p: '维护实际用工单位；创建投保岗位并绑定产品方案，可上传岗位视频用于职业定类。' },
      { h: '参保员工：手工与批量', list: [
        '手工添加：填写姓名、手机号、证件、实际用工单位、投保岗位、职业类别与产品方案，提交审核',
        '批量导入：下载 Excel 模板→上传→字段/重复/职业类别校验→可下载错误行→按批次提交',
      ] },
      { h: '参保 / 停保 / 恢复', p: '员工列表支持编辑、参保、停保、恢复及批量操作，可查看参停保记录、批次详情、保单与投保关系。' },
      { h: '双账户与充值', p: '保费账户与使用费账户分别展示余额、日消耗与月消耗预估；按保司分账户提交充值申请等待平台审核。' },
      { h: '劳动关系用工事实反馈', p: '上报实际入职/离职等用工事实，用于与参保记录比对，驱动及时率与责任归属；支持两阶段导入与未匹配人工匹配。' },
      { h: '理赔管理', p: '工伤报案、7 项材料清单、图片/PDF/Office 上传与补件、保司审核与赔付时间线；材料通过短时签名链接下载。' },
    ],
  },
  {
    id: 'sales-ops', title: '业务员端操作指南', roles: ['salesperson'],
    blocks: [
      { p: '业务员端所有数据严格限定为"本人"，身份来自登录令牌，无法通过参数越权查看他人。' },
      { h: '产品中心', p: '查看自己可推广的产品方案，字段按允许清单脱敏，不含保司结算价、平台利润等内部价格。' },
      { h: '我的佣金与结算', list: [
        '佣金汇总与明细：按投保单位、保司、产品筛选，汇总总额与明细求和一致',
        '结算单：查看历史结算单及明细并导出',
      ] },
      { h: '余额与打款', p: '展示预估佣金、待结算、待打款与已打款金额，查看打款记录与分配情况。' },
    ],
  },
  {
    id: 'insurer-ops', title: '保司端操作指南', roles: ['insurer'],
    blocks: [
      { p: '保司端数据严格限定为"本保司"：只能看到与本保司关联的产品、保单、参保员工、理赔与结算记录，看不到其他保司或与本保司无关的企业数据。' },
      { h: '岗位核保', p: '审核投保单位提交的岗位定级视频，通过/驳回并给出职业类别，只能处理关联到本保司产品的岗位。' },
      { h: '参保管理', p: '查看本保司名下的参保员工名单，可对异常参保记录添加标注说明，便于后续核赔时核对。' },
      { h: '理赔管理', p: '审核本保司相关的理赔案件材料，核定赔付金额或驳回，操作范围仅限本保司理赔单。' },
      { h: '财务管理', p: '查看结算相关数据：可见保司结算价，但看不到平台内部利润、业务员佣金等字段，这些对保司角色统一脱敏。' },
      { h: '发票管理', p: '查看与本保司相关的开票记录。' },
      { h: '基本信息', p: '维护本保司的联系人、邮箱、地址等基本资料；修改登录密码在此页完成。' },
    ],
  },
  {
    id: 'rules', title: '核心业务规则', roles: ALL,
    blocks: [
      { h: '双账户与使用费门禁', p: '使用费余额不足会实时锁定参保/停保写操作；充值到账后自动解锁，无需人工干预。' },
      { h: '保障期与待确认停保', p: '停保以当前实际有效的保障期为权威；保费欠费生成管理员待确认停保任务，确认后才截断保障期，并发确认至多执行一次。' },
      { h: '数据范围与隔离', p: '平台端可见全平台，单位端仅本租户，项目经理再收敛到被授权用工单位，业务员仅本人，保司端仅限与本保司关联的产品/保单/理赔/结算记录；越权读写被拒或强制归属自身。' },
    ],
  },
  {
    id: 'faq', title: '常见问题', roles: ALL,
    blocks: [
      { list: [
        '登录偶发一次 500、重试即恢复：免费实例冷启动的瞬时错误，非缺陷',
        '提示"该账号不是 XX 账号"：门户与角色不匹配，请用对应门户登录',
        '参保/停保被拒：使用费账户余额不足，充值到账后自动恢复',
        '项目经理看不到数据：尚未被主管授权对应实际用工单位，请联系主管',
        '理赔材料无法直接用网址打开：出于安全仅通过短时签名链接下载，链接会过期需重新获取',
        '保司账号无法登录/没有账号：保司端不支持自助注册，需联系平台管理员在"保司主体管理"里开通',
      ] },
    ],
  },
]

const visibleSections = computed(() =>
  sections.filter((s) => s.roles.includes(currentRole.value)),
)

function scrollTo(id: string) {
  document.getElementById(`help-sec-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<template>
  <el-drawer v-model="visible" title="系统帮助 · 操作指南" size="46%" :append-to-body="true">
    <div class="help-body">
      <div class="help-meta">当前身份：{{ roleLabel }} · 以下为与您角色相关的操作说明</div>
      <nav class="help-toc">
        <a v-for="s in visibleSections" :key="s.id" href="javascript:void(0)" @click="scrollTo(s.id)">{{ s.title }}</a>
      </nav>
      <section v-for="s in visibleSections" :id="`help-sec-${s.id}`" :key="s.id" class="help-sec">
        <h3>{{ s.title }}</h3>
        <template v-for="(b, i) in s.blocks" :key="i">
          <h4 v-if="b.h">{{ b.h }}</h4>
          <p v-if="b.p">{{ b.p }}</p>
          <ul v-if="b.list">
            <li v-for="(item, j) in b.list" :key="j">{{ item }}</li>
          </ul>
        </template>
      </section>
      <p class="help-foot">如需完整版说明，可向管理员索取《响帮帮无忧保 · 系统使用说明》PDF。</p>
    </div>
  </el-drawer>
</template>

<style scoped>
.help-body { font-size: 13px; line-height: 1.7; color: #303133; }
.help-meta { color: #909399; font-size: 12px; margin-bottom: 12px; }
.help-toc { display: flex; flex-wrap: wrap; gap: 8px; padding-bottom: 14px; margin-bottom: 14px; border-bottom: 1px solid #ebeef5; }
.help-toc a { font-size: 12px; color: #409eff; background: #ecf5ff; padding: 3px 10px; border-radius: 12px; text-decoration: none; }
.help-toc a:hover { background: #d9ecff; }
.help-sec { margin-bottom: 18px; scroll-margin-top: 8px; }
.help-sec h3 { font-size: 15px; color: #0b3d5c; margin: 6px 0 8px; padding-bottom: 4px; border-bottom: 2px solid #409eff; }
.help-sec h4 { font-size: 13px; color: #145a8a; margin: 12px 0 4px; }
.help-sec p { margin: 4px 0; }
.help-sec ul { margin: 4px 0; padding-left: 18px; }
.help-sec li { margin: 3px 0; }
.help-foot { margin-top: 20px; padding-top: 12px; border-top: 1px solid #ebeef5; color: #909399; font-size: 12px; }
</style>
