const app = getApp();

// §13.2 项目负责人只读：无批次确认、无授权关系维护。接口本身已按授权范围过滤，
// 此页只负责展示，不再自行推断权限。
const STATUS_LABEL = {
  timely: '及时', early: '提前', late: '延迟', missing: '漏办',
  premature: '提前停保', pending: '未到期', unmatched: '待匹配', conflict: '冲突',
};
const REASON_LABEL = {
  normal: '正常',
  source_feedback_late: '源头反馈晚',
  operator_processing_late: '操作员处理晚',
  system_processing_late: '系统处理晚',
  insurer_confirmation_late: '保司确认晚',
  unassigned_responsibility: '当时无负责人',
};

// null 表示没有应办事件：显示「—」而不是 0%，空项目既不完美也不糟糕。
function rate(value) {
  return value === null || value === undefined ? '—' : `${value}%`;
}

function days(seconds) {
  return !seconds ? '0' : (seconds / 86400).toFixed(1);
}

Page({
  data: { cards: [], items: [], loading: true, filter: '' },

  onShow() {
    this.load();
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh());
  },

  onFilterChange(event) {
    this.setData({ filter: event.detail.value }, () => this.load());
  },

  load() {
    this.setData({ loading: true });
    const query = this.data.filter ? `?operation_type=${this.data.filter}` : '';
    return Promise.all([
      app.request(`/timeliness/summary${query}`, { silent: true }),
      app.request(`/timeliness/details${query}`, { silent: true }),
    ])
      .then(([summary, details]) => {
        this.setData({
          cards: [
            { label: '参保及时率', value: rate(summary.enrollment_rate), hint: `应参保 ${summary.enrollment_due || 0}` },
            { label: '停保及时率', value: rate(summary.termination_rate), hint: `应停保 ${summary.termination_due || 0}` },
            { label: '综合及时率', value: rate(summary.composite_rate), hint: '按业务事件计' },
            { label: '反馈及时率', value: rate(summary.feedback_rate), hint: `已判定 ${summary.feedback_due || 0}` },
            { label: '保障缺口', value: `${days(summary.coverage_gap_seconds)} 天`, hint: '' },
            { label: '额外保费', value: `¥${summary.excess_premium || 0}`, hint: '' },
          ],
          items: (details.items || []).map((row) => ({
            ...row,
            type_text: row.operation_type === 'enrollment' ? '参保' : '停保',
            status_text: STATUS_LABEL[row.timeliness_status] || row.timeliness_status,
            reason_text: REASON_LABEL[row.responsibility_reason] || row.responsibility_reason,
            delay_text: `${days(row.delay_seconds)} 天`,
            gap_text: `${days(row.coverage_gap_seconds)} 天`,
          })),
          loading: false,
        });
      })
      .catch(() => this.setData({ loading: false }));
  },

  onShareAppMessage() {
    return app.share('/pages/timeliness/timeliness', 'from=share');
  },
});
