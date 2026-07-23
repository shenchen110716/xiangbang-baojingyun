export type Role = 'admin' | 'enterprise' | 'salesperson'
export type EnterpriseRole = 'owner' | 'project_manager'

export interface User {
  id: number
  username: string
  name: string
  role: Role
  enterprise_id: number | null
  enterprise_role: EnterpriseRole | null
  phone: string
  is_owner: boolean
  active: boolean
}

export interface Enterprise {
  id: number
  name: string
  kind: string
  credit_code: string
  contact: string
  phone: string
  status: string
  agent_id: number | null
  agent_name?: string
  premium_balance: number
  premium_balance_total?: number
  premium_recharged?: number
  premium_consumed?: number
  usage_balance: number
  usage_recharged?: number
  usage_consumed?: number
  usage_available?: number
  usage_fee_daily: number
  alert_days: number
  created_at: string
}

export interface ActualEmployer {
  id: number
  enterprise_id: number
  name: string
  credit_code: string
  contact: string
  phone: string
  status: 'active' | 'paused'
  has_active_people?: boolean
  created_at: string
}

export interface Insurer {
  id: number
  name: string
  contact: string
  phone: string
  status: string
  pending_name: string | null
  pending_contact: string | null
  pending_phone: string | null
  pending_submitted_at: string | null
  created_at: string
}

export interface WorkPosition {
  id: number
  enterprise_id: number
  actual_employer_id: number | null
  actual_employer: string
  actual_employer_name?: string
  name: string
  occupation_class: string
  plan_id: number | null
  plan_name?: string
  creator_name?: string
  status: 'pending' | 'approved' | 'rejected' | 'supplement'
  video_count?: number
  latest_video_status?: string
  review_note?: string
  has_active_people?: boolean
  created_at: string
}

export interface PositionVideo {
  id: number
  position_id: number
  name: string
  url: string
  status: string
  review_note: string
  created_at: string
}

export interface PricingSnapshot {
  insurance_base_price: number
  total_commission_rate: number
  total_commission_amount: number
  policy_floor_price: number
  insurer_settlement_price: number
  profit_amount: number
  minimum_sale_price: number
  commission_mode: 'rebate' | 'price'
  agent_commission_rate: number
  agent_commission_amount: number
  sale_price: number
  platform_margin_amount: number
}

export interface InsurancePlan extends PricingSnapshot {
  id: number
  insurer: string
  insurer_email: string
  name: string
  coverage: string
  occupation_classes: string
  price: number
  commission_rate: number
  profit_amount: number
  payment_mode: string
  billing_mode: 'monthly' | 'daily'
  effective_mode: 'next_day' | 'immediate'
  status: 'active' | 'paused'
  created_at: string
}

export interface PlanTier {
  id: number
  plan_id: number
  occupation_class: string
  price: number
  coverage: string
  status: string
  created_at: string
}

export interface InsuredPerson extends Partial<PricingSnapshot> {
  id: number
  enterprise_id: number
  enterprise_name?: string
  name: string
  phone: string
  id_number: string
  occupation: string
  occupation_class: string
  position_id: number | null
  position_name?: string
  actual_employer_name?: string
  plan_id?: number | null
  plan_name?: string
  insurer?: string
  policy_no?: string
  policy_status?: string
  effective_mode?: 'immediate' | 'next_day' | ''
  billing_mode?: 'daily' | 'monthly' | ''
  status: 'pending' | 'active' | 'stopped'
  policy_id: number | null
  created_at: string
  effective_at?: string | null
  terminated_at?: string | null
}

export interface PolicyMemberHistory {
  id: number
  policy_id: number
  person_id: number
  rate_snapshot_json: string
  effective_at: string
  terminated_at: string | null
  endorsement_no: string
  status: string
  created_at: string
  policy_no: string
  insurer: string
  plan_name: string
  effective_mode?: string
}

export interface Policy extends Partial<PricingSnapshot> {
  id: number
  policy_no: string
  enterprise_id: number
  enterprise_name: string
  plan_id: number
  plan_name: string
  insurer: string
  premium: number
  premium_original?: number
  calculated_premium?: number
  status: string
  start_date: string
  end_date: string
  insured_count: number
  billing_mode: string
  effective_mode: string
  insurance_base_total?: number
  policy_floor_total?: number
  minimum_sale_total?: number
  sale_total?: number
  total_commission_total?: number
  agent_commission_total?: number
  document_url?: string
  document_name?: string
  document_download_url?: string
  created_at: string
}

export interface Claim {
  id: number
  enterprise_id: number
  enterprise_name: string
  person_id: number
  person_name: string
  id_number: string
  position_name: string
  actual_employer_name: string
  policy_id: number | null
  policy_no: string
  plan_name: string
  insurer: string
  claim_no: string
  description: string
  status: string
  amount: number
  accident_at: string
  accident_place: string
  accident_type: string
  injury_part: string
  payee_type: string
  hospital: string
  diagnosis: string
  medical_cost: number
  contact_name: string
  contact_phone: string
  insurer_report_no: string
  current_handler: string
  deadline: string
  approved_amount: number
  paid_at: string
  rejection_reason: string
  review_note: string
  sla_deadline: string
  risk_level: 'normal' | 'attention' | 'high'
  document_count: number
  missing_count: number
  missing_types: string[]
  complete_percent: number
  deadline_days: number | null
  deadline_overdue: boolean
  sla_overdue: boolean
  calculated_risk: string
  created_at: string
}

export interface ClaimDocument {
  id: number
  claim_id: number
  name: string
  url: string
  doc_type: string
  status: string
  review_note: string
  created_at: string
}

export interface ClaimTimelineItem {
  id: number
  claim_id: number
  node: string
  action: string
  note: string
  operator: string
  created_at: string
}

export interface ChecklistItem {
  doc_type: string
  name: string
  required: boolean
  uploaded: boolean
  status: string
  review_note: string
}

export interface AgentCommission extends Partial<PricingSnapshot> {
  id: number
  agent_id: number
  agent_name: string
  enterprise_id: number
  enterprise_name: string
  plan_id: number
  plan_name: string
  insurer: string
  rate: number
  mode: 'rebate' | 'price' | 'markup'
  markup_amount: number
  sale_price: number
  status: string
  insured_count?: number
  agent_commission_unit?: number
  agent_commission_total?: number
  accrued_total_commission?: number
  accrued_agent_commission?: number
  accrued_person_count?: number
  accrual_as_of?: string
  created_at: string
}

export interface AgentMeResponse {
  summary: {
    enterprise_count: number
    product_count: number
    insured_count: number
    total_commission: number
  }
  rows: AgentCommission[]
}

export interface Agent {
  id: number
  username: string
  name: string
  phone: string
  role: string
  active: boolean
  status: string
  enterprise_count: number
  product_count: number
  insured_count: number
  total_commission: number
  created_at: string
}

export interface Operator {
  id: number
  username: string
  name: string
  phone: string
  role: string
  enterprise_id: number | null
  enterprise_name: string
  enterprise_role: EnterpriseRole | null
  is_owner: boolean
  active: boolean
  has_data?: boolean
  created_at: string
}

export interface EmployerScope {
  id: number
  user_id: number
  user_name: string
  enterprise_id: number
  actual_employer_id: number
  actual_employer_name: string
  responsibility_type: 'primary' | 'collaborator'
  assigned_at: string
  revoked_at: string | null
  status: 'active' | 'revoked'
}

export interface Invoice {
  id: number
  enterprise_id: number
  enterprise_name: string
  account: 'premium' | 'usage'
  amount: number
  title: string
  tax_no: string
  email: string
  status: 'pending' | 'approved' | 'issued' | 'rejected'
  created_at: string
}

export interface BillingRow {
  id: number
  enterprise_name: string
  account: string
  account_type?: 'premium' | 'usage'
  account_id?: number
  balance: number
  recharged?: number
  available?: number
  premium_consumed?: number
  status: string
  daily_rate: number
  estimated_daily: number
  monthly_estimate?: number
  active_people: number
  month_person_days: number
  month_accrued: number
  total_person_days: number
  total_accrued: number
  as_of_date: string
}

export interface LedgerEntry {
  id: number
  enterprise_id: number
  account: string
  direction: 'credit' | 'debit'
  amount: number
  business_type: string
  business_id: string
  operator: string
  occurred_at: string
}

export interface LedgerResponse {
  entries: LedgerEntry[]
  reconciliation: Array<{ account: string; cached_balance: number; ledger_balance: number; diff: number }>
}

export interface AuditLogItem {
  id: number
  user_id: number
  operator: string
  action: string
  object_type: string
  object_id: string
  detail: string
  created_at: string
}

export interface DashboardData {
  portal: 'admin' | 'enterprise'
  enterprises: number
  people: number
  active_people: number
  active_policies: number
  pending_enterprises: number
  pending_people: number
  claims_open: number
  premium_accounts: PremiumAccountRow[]
  usage_balance: number
  usage_recharged: number
  usage_consumed: number
  usage_available: number
  pending_terminations_count: number
  balance_alerts: Array<{
    enterprise_id: number
    enterprise_name: string
    account: string
    account_id?: number
    label?: string
    balance: number
    daily_burn: number
    days_left: number
    alert_days: number
    level: 'critical' | 'warning'
  }>
}

export interface PremiumAccountRow {
  account_id: number
  label: string
  insurers: string[]
  balance: number
  recharged?: number
  consumed?: number
  available?: number
}

export interface InsurerAccount {
  id: number
  label: string
  bank_name: string
  account_no: string
  account_holder: string
  status: 'active' | 'paused'
  created_at: string
  insurers: string[]
}

export interface InsurerAccountLink {
  id: number
  insurer: string
  account_id: number
  created_at: string
}

export interface RechargeRequest {
  id: number
  enterprise_id: number
  enterprise_name: string
  account_type: 'premium' | 'usage'
  insurer: string | null
  account_id: number | null
  amount: number
  receipt_file_url: string
  receipt_download_url?: string
  status: 'pending' | 'confirmed' | 'rejected'
  reject_reason: string
  created_by: number
  confirmed_by: number | null
  confirmed_at: string | null
  created_at: string
}

export interface PendingTermination {
  id: number
  enterprise_id: number
  enterprise_name: string
  account_id: number
  account_label: string
  affected_insurers: string
  affected_count: number
  current_affected_count: number
  affected_people: Array<{ id: number; name: string }>
  status: 'pending' | 'confirmed' | 'dismissed'
  confirmed_by: number | null
  confirmed_at: string | null
  dismissed_at: string | null
  created_at: string
}

export interface ScreenProduct {
  plan_id: number
  insurer: string
  product: string
  insured_count: number
  enterprise_count: number
  premium_total: number
  policy_count: number
}

export interface MessageItem {
  id: string
  type: 'warning' | 'todo' | 'danger' | 'success'
  title: string
  content: string
  created_at: string
  path: string
}

export interface EnrollmentSummaryRow {
  plan_id: number
  insurer: string
  insurer_email: string
  product: string
  insured_count: number
  new_count: number
  stop_count: number
}

export interface EnrollmentEmailLog {
  id: number
  enterprise_id: number
  enterprise_name: string
  plan_id: number
  plan_name: string
  insurer: string
  kind: string
  recipient: string
  filename: string
  people_count: number
  request_id: string
  status: string
  data_date: string
  created_at: string
  receipt_status: string
  receipt_note: string
  receipt_at: string | null
}

export interface ReportRow {
  id: string
  name: string
  period: string
  value: number
  detail: string
}

export interface PremiumDetailRow {
  member_id: number
  person_id: number
  person_name: string
  id_number: string
  enterprise_name: string
  agent_id: number | null
  agent_name: string
  actual_employer_name: string
  position_name: string
  occupation_class: string
  policy_no: string
  insurer: string
  plan_name: string
  billing_mode: 'monthly' | 'daily'
  unit_sale_price: number
  unit_policy_floor_price: number
  unit_total_commission: number
  unit_agent_commission: number
  coverage_start: string
  coverage_end: string | null
  period_start: string
  period_end: string
  active_days: number
  premium_amount: number
  settlement_amount: number
  commission_amount: number
  agent_commission_amount: number
}

export interface PremiumDetailReport {
  start_date: string
  end_date: string
  as_of_date: string
  total_premium: number
  total_settlement: number
  total_commission: number
  total_agent_commission: number
  detail_count: number
  enterprise_id: number | null
  insurer: string
  agent_id: number | null
  rows: PremiumDetailRow[]
}

export interface ProviderStatus {
  mode: 'mock' | 'real'
  insurer_api: boolean
  sms: boolean
  email: boolean
  payment: boolean
}

/** v4.2 §13.4 及时率统计卡片。比率在无应办事件时为 null，不是 0 —— 空项目既不完美也不糟糕。 */
export interface TimelinessSummary {
  enrollment_due: number
  enrollment_timely: number
  enrollment_late: number
  enrollment_missing: number
  termination_due: number
  termination_timely: number
  termination_premature: number
  termination_late: number
  termination_missing: number
  enrollment_rate: number | null
  termination_rate: number | null
  composite_rate: number | null
  feedback_due: number
  feedback_timely: number
  feedback_rate: number | null
  operator_attributable_due: number
  operator_attributable_rate: number | null
  coverage_gap_seconds: number
  excess_premium: number
  early_premium: number
}

export interface TimelinessDetail {
  id: number
  employment_fact_id: number
  employment_fact_revision_no: number
  operation_type: string
  enterprise_id: number
  actual_employer_id: number
  person_id: number | null
  responsible_user_id: number | null
  actual_business_at: string | null
  expected_coverage_at: string | null
  actual_coverage_at: string | null
  timeliness_status: string
  delay_seconds: number
  early_seconds: number
  coverage_gap_seconds: number
  excess_premium: number
  early_premium: number
  feedback_status: string
  feedback_deadline_at: string | null
  responsibility_reason: string
  responsibility_evidence: Record<string, unknown>
  product_rule_version: number
  calculation_version: number
  calculated_at: string | null
}

/** v4.2 §5.1 业务员可见的产品视图——白名单，绝不含成本构成。 */
export interface AgentProduct {
  id: number
  insurer: string
  name: string
  coverage: string
  occupation_classes: string
  billing_mode: string
  effective_mode: string
  status: string
  min_sale_price: number
  my_commission_status: string
}

export interface AgentBalances {
  agent_id: number
  estimated_total: number
  pending_settlement: number
  pending_payment: number
  paid: number
}

export interface AgentCommissionSummary {
  agent_id: number
  enterprise_count: number
  product_count: number
  insured_count: number
  estimated_total: number
}

export interface AgentCommissionRow {
  enterprise_id: number | null
  enterprise_name: string
  plan_id: number | null
  plan_name: string
  insurer: string
  mode: string
  status: string
  insured_count: number
  min_sale_price: number
  sale_price: number
  amount: number
  unit_amount: number
  accrual_as_of: string
}

export interface AgentStatementItem {
  id: number
  source_type: string
  plan_id: number | null
  enterprise_id: number | null
  amount: number
  status: string
  adjusts_item_id: number | null
  created_at: string | null
}

export interface AgentStatement {
  id: number
  statement_no: string
  period_start: string
  period_end: string
  currency: string
  total_amount: number
  status: string
  confirmed_at: string | null
  created_at: string | null
  items: AgentStatementItem[]
}

export interface AgentPayment {
  id: number
  amount: number
  channel: string
  transaction_no: string
  paid_at: string | null
  voucher_url: string
  allocated_amount: number
  created_at: string | null
}
