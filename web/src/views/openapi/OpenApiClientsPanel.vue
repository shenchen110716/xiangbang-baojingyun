<script setup lang="ts">
// 开放平台密钥管理面板（story-p0-foundation AC11）。
// 从投保单位列表「更多 → 开放平台」进入，以企业为单位管理 AppKey：
// 签发（secret 明文只显示一次）、启停、scope/IP 白名单/过期时间调整、
// 调用日志查询。签发/启停落 audit_logs（后端 openapi_admin.py）。
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as openapiAdmin from '@/api/openapiAdmin'
import type { OpenApiClient, OpenApiRequestLogItem } from '@/api/types'
import { formatDateTime } from '@/utils/format'

const props = defineProps<{ enterpriseId: number }>()

// 与 backend/services/openapi/auth.py 的 KNOWN_SCOPES 保持一致
const SCOPE_OPTIONS = [
  { value: 'insured:read', label: '在保人员查询' },
  { value: 'insured:read_sensitive', label: '在保人员含敏感字段' },
  { value: 'declaration:write', label: '参停保申报' },
  { value: 'account:read', label: '账户余额查询' },
  { value: 'recharge:write', label: '充值申请' },
  { value: 'policy:read', label: '保单查询' },
  { value: 'webhook:manage', label: 'Webhook 管理' },
  { value: 'probe:read', label: '联调探针' },
]

const loading = ref(true)
const list = ref<OpenApiClient[]>([])

async function load() {
  loading.value = true
  try {
    list.value = await openapiAdmin.listOpenApiClients(props.enterpriseId)
  } finally {
    loading.value = false
  }
}
onMounted(load)

// ---- 签发 ----
const issueVisible = ref(false)
const issueForm = reactive({
  name: '',
  env: 'prod',
  scopes: [] as string[],
  ipAllowlistText: '',
  expires_at: '' as string,
})
const issuedSecret = ref('')
const issuedKeyId = ref('')

function openIssue() {
  Object.assign(issueForm, { name: '', env: 'prod', scopes: [], ipAllowlistText: '', expires_at: '' })
  issuedSecret.value = ''
  issuedKeyId.value = ''
  issueVisible.value = true
}

async function submitIssue() {
  try {
    const result = await openapiAdmin.issueOpenApiClient({
      enterprise_id: props.enterpriseId,
      name: issueForm.name,
      env: issueForm.env,
      scopes: issueForm.scopes,
      ip_allowlist: issueForm.ipAllowlistText.split('\n').map((s) => s.trim()).filter(Boolean),
      expires_at: issueForm.expires_at || null,
    })
    issuedSecret.value = result.secret
    issuedKeyId.value = result.key_id
    ElMessage.success('密钥已签发')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

async function copySecret() {
  await navigator.clipboard.writeText(issuedSecret.value)
  ElMessage.success('已复制 AppSecret')
}

// ---- 启停 ----
async function toggleStatus(item: OpenApiClient) {
  const disabling = item.status === 'active'
  if (disabling) {
    try {
      await ElMessageBox.confirm(
        `确定停用密钥「${item.key_id}」吗？停用后该密钥的所有调用立即失败。`,
        '停用确认', { type: 'warning' },
      )
    } catch { return }
  }
  try {
    await openapiAdmin.updateOpenApiClient(item.id, { status: disabling ? 'disabled' : 'active' })
    ElMessage.success(disabling ? '已停用' : '已启用')
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- 编辑（scope / IP 白名单 / 过期时间）----
const editVisible = ref(false)
const editingClient = ref<OpenApiClient | null>(null)
const editForm = reactive({ scopes: [] as string[], ipAllowlistText: '', expires_at: '' })

function openEdit(item: OpenApiClient) {
  editingClient.value = item
  Object.assign(editForm, {
    scopes: [...item.scopes],
    ipAllowlistText: item.ip_allowlist.join('\n'),
    expires_at: item.expires_at ?? '',
  })
  editVisible.value = true
}

async function submitEdit() {
  if (!editingClient.value) return
  try {
    await openapiAdmin.updateOpenApiClient(editingClient.value.id, {
      scopes: editForm.scopes,
      ip_allowlist: editForm.ipAllowlistText.split('\n').map((s) => s.trim()).filter(Boolean),
      expires_at: editForm.expires_at || '',
    })
    ElMessage.success('已保存')
    editVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

// ---- 调用日志 ----
const logsVisible = ref(false)
const logsTarget = ref<OpenApiClient | null>(null)
const logs = ref<OpenApiRequestLogItem[]>([])

async function openLogs(item: OpenApiClient) {
  logsTarget.value = item
  logsVisible.value = true
  logs.value = await openapiAdmin.listOpenApiRequestLogs({ key_id: item.key_id, limit: 100 })
}
</script>

<template>
  <div v-loading="loading" class="openapi-clients-panel">
    <div class="toolbar">
      <el-button type="primary" size="small" @click="openIssue">＋ 签发密钥</el-button>
    </div>
    <el-table :data="list" size="small">
      <el-table-column label="AppKey / 名称" min-width="200">
        <template #default="{ row }">
          <div class="mono">{{ row.key_id }}</div>
          <small class="muted">{{ row.name || '未命名' }}</small>
        </template>
      </el-table-column>
      <el-table-column label="环境" width="80">
        <template #default="{ row }">
          <el-tag :type="row.env === 'sandbox' ? 'warning' : 'success'" size="small">
            {{ row.env === 'sandbox' ? '沙箱' : '生产' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="scope" min-width="160">
        <template #default="{ row }">
          <span v-if="row.scopes.length" class="muted">{{ row.scopes.join(', ') }}</span>
          <span v-else class="muted">（无）</span>
        </template>
      </el-table-column>
      <el-table-column label="状态/过期" width="160">
        <template #default="{ row }">
          <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">
            {{ row.status === 'active' ? '启用' : '停用' }}
          </el-tag>
          <div class="muted">{{ row.expires_at ? `过期 ${formatDateTime(row.expires_at)}` : '长期有效' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">配置</el-button>
          <el-button link type="primary" size="small" @click="openLogs(row)">调用日志</el-button>
          <el-button link :type="row.status === 'active' ? 'danger' : 'success'" size="small" @click="toggleStatus(row)">
            {{ row.status === 'active' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="issueVisible" title="签发开放平台密钥" width="520px">
      <template v-if="!issuedSecret">
        <el-form :model="issueForm" label-width="110px">
          <el-form-item label="密钥名称"><el-input v-model="issueForm.name" placeholder="如：ERP 对接" /></el-form-item>
          <el-form-item label="环境">
            <el-select v-model="issueForm.env" style="width: 100%">
              <el-option label="生产（prod）" value="prod" />
              <el-option label="沙箱（sandbox，仅限「沙箱」类型企业）" value="sandbox" />
            </el-select>
          </el-form-item>
          <el-form-item label="scope 授权">
            <el-checkbox-group v-model="issueForm.scopes">
              <el-checkbox v-for="s in SCOPE_OPTIONS" :key="s.value" :value="s.value">{{ s.label }}</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item label="IP 白名单">
            <el-input v-model="issueForm.ipAllowlistText" type="textarea" :rows="3" placeholder="每行一个 IP 或 CIDR，留空不限" />
          </el-form-item>
          <el-form-item label="过期时间">
            <el-date-picker v-model="issueForm.expires_at" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空长期有效" style="width: 100%" />
          </el-form-item>
        </el-form>
      </template>
      <template v-else>
        <el-alert type="warning" :closable="false" title="AppSecret 只显示这一次，请立即保存；关闭后无法再次查看，只能重新签发。" />
        <div class="secret-box">
          <div><span class="muted">AppKey：</span><span class="mono">{{ issuedKeyId }}</span></div>
          <div><span class="muted">AppSecret：</span><span class="mono">{{ issuedSecret }}</span></div>
        </div>
      </template>
      <template #footer>
        <template v-if="!issuedSecret">
          <el-button @click="issueVisible = false">取消</el-button>
          <el-button type="primary" @click="submitIssue">签发</el-button>
        </template>
        <template v-else>
          <el-button type="primary" @click="copySecret">复制 AppSecret</el-button>
          <el-button @click="issueVisible = false">我已保存，关闭</el-button>
        </template>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" :title="`密钥配置 · ${editingClient?.key_id ?? ''}`" width="520px">
      <el-form :model="editForm" label-width="110px">
        <el-form-item label="scope 授权">
          <el-checkbox-group v-model="editForm.scopes">
            <el-checkbox v-for="s in SCOPE_OPTIONS" :key="s.value" :value="s.value">{{ s.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="IP 白名单">
          <el-input v-model="editForm.ipAllowlistText" type="textarea" :rows="3" placeholder="每行一个 IP 或 CIDR，留空不限" />
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker v-model="editForm.expires_at" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空长期有效" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="logsVisible" :title="`调用日志 · ${logsTarget?.key_id ?? ''}`" width="860px">
      <el-table :data="logs" size="small" max-height="480">
        <el-table-column prop="request_id" label="request_id" width="150" show-overflow-tooltip />
        <el-table-column prop="method" label="方法" width="70" />
        <el-table-column prop="path" label="路径" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status < 400 ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latency_ms" label="耗时(ms)" width="90" />
        <el-table-column prop="ip" label="来源 IP" width="130" />
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.openapi-clients-panel {
  display: grid;
  gap: 12px;
}
.toolbar {
  display: flex;
  justify-content: flex-end;
}
.muted {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  word-break: break-all;
}
.secret-box {
  margin-top: 12px;
  padding: 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  display: grid;
  gap: 8px;
}
</style>
