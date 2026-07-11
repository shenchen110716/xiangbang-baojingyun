const app = getApp();
Page({
  data: { items: [], enterpriseId: 0, date: '', loading: true, sendingId: 0 },
  onLoad() { this.setData({ date: this.today() }); },
  onShow() { this.load(); },
  today() { const date = new Date(); return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`; },
  load() { return Promise.all([app.request('/enterprises'), app.request(`/enrollment/summary?date=${this.data.date}`)]).then(([enterprises, items]) => this.setData({ enterpriseId: (enterprises[0] && enterprises[0].id) || 0, items, loading: false })).catch(() => this.setData({ loading: false })); },
  dateChange(e) { this.setData({ date: e.detail.value, loading: true }); this.load(); },
  send(e) { const planId = Number(e.currentTarget.dataset.plan), kind = e.currentTarget.dataset.kind; wx.showModal({ title: kind === 'enrollment' ? '发送参保名单' : '发送停保名单', content: '名单将发送至对应保险公司，请确认员工信息已核对。', success: (res) => { if (!res.confirm) return; this.setData({ sendingId: planId }); app.request(`/enrollment/send?enterprise_id=${this.data.enterpriseId}&plan_id=${planId}&kind=${kind}`, { method: 'POST' }).then((data) => { wx.showToast({ title: data.message || '已发送', icon: 'none' }); this.setData({ sendingId: 0 }); this.load(); }).catch(() => this.setData({ sendingId: 0 })); } }); },
  email(e) { const planId = Number(e.currentTarget.dataset.plan), kind = e.currentTarget.dataset.kind; app.request(`/enrollment/email?enterprise_id=${this.data.enterpriseId}&plan_id=${planId}&kind=${kind}`, { method: 'POST' }).then((data) => wx.showToast({ title: data.message || '邮件已发送', icon: 'none' })); },
  importFile() { wx.navigateTo({ url: '/pages/import/import' }); },
  onShareAppMessage() { return app.share('/pages/batches/batches', 'from=share'); }
});
