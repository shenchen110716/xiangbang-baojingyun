const app = getApp();
Page({
  data: { items: [], loading: true },
  onShow() { this.load(); },
  load() { return app.request('/billing', { silent: true }).then((items) => this.setData({ items: items.map((item) => ({ ...item, balance_text: Number(item.balance || 0).toFixed(2), estimated_text: Number(item.estimated_daily || 0).toFixed(2) })), loading: false })).catch(() => this.setData({ loading: false })); },
  recharge(e) { const id = e.currentTarget.dataset.id, account = e.currentTarget.dataset.account; wx.showModal({ title: account === 'premium' ? '保费账户充值' : '平台使用费充值', editable: true, placeholderText: '请输入充值金额', confirmText: '确认充值', success: (res) => { const amount = Number(res.content); if (!res.confirm) return; if (!amount || amount <= 0) { wx.showToast({ title: '请输入有效金额', icon: 'none' }); return; } app.request(`/enterprises/${id}/recharge`, { method: 'POST', data: { account, amount } }).then(() => { wx.showToast({ title: '充值成功' }); this.load(); }); } }); },
  onShareAppMessage() { return app.share('/pages/billing/billing', 'from=share'); }
});
