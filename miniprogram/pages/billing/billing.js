const app = getApp();
Page({
  data: { items: [], invoices: [], loading: true },
  onShow() { this.load(); },
  load() { return Promise.all([app.request('/billing', { silent: true }), app.request('/invoices', { silent: true })]).then(([items, invoices]) => this.setData({ items: items.map((item) => ({ ...item, balance_text: Number(item.balance || 0).toFixed(2), estimated_text: Number(item.estimated_daily || 0).toFixed(2), month_accrued_text: Number(item.month_accrued || 0).toFixed(2), total_accrued_text: Number(item.total_accrued || 0).toFixed(2) })), invoices: invoices.map((item) => ({ ...item, amount_text: Number(item.amount || 0).toFixed(2), status_label: ({ pending: '待审核', approved: '已审核', issued: '已开票', rejected: '已驳回' })[item.status] || item.status })), loading: false })).catch(() => this.setData({ loading: false })); },
  // 充值支持微信支付（快捷）或银行转账+回单OCR（保费账户仅此一种）；统一跳转到充值页，
  // 与电脑后台"账户充值"页保持一致的能力，而不是这里用弹窗简化处理。
  recharge(e) {
    const id = e.currentTarget.dataset.id;
    const accountType = e.currentTarget.dataset.account === 'premium' ? 'premium' : 'usage';
    wx.navigateTo({ url: `/pages/recharge-request/recharge-request?enterpriseId=${id}&accountType=${accountType}` });
  },
  records() {
    const item = this.data.items[0];
    wx.navigateTo({ url: `/pages/recharge-request/recharge-request?enterpriseId=${item ? item.id : 0}&tab=records` });
  },
  invoice() {
    const account = this.data.items[0]; if (!account) { wx.showToast({ title: '暂无可开票账户', icon: 'none' }); return; }
    wx.showModal({ title: '发票抬头', editable: true, placeholderText: account.enterprise_name, success: (titleResult) => { if (!titleResult.confirm) return; const title = String(titleResult.content || account.enterprise_name).trim(); if (!title) { wx.showToast({ title: '请填写发票抬头', icon: 'none' }); return; }
      wx.showModal({ title: '开票金额', editable: true, placeholderText: '请输入开票金额', success: (amountResult) => { if (!amountResult.confirm) return; const amount = Number(amountResult.content); if (!amount || amount <= 0) { wx.showToast({ title: '请输入有效金额', icon: 'none' }); return; }
        app.request('/invoices', { method: 'POST', data: { enterprise_id: account.id, account: 'premium', amount, title, tax_no: '', email: '' } }).then(() => { wx.showToast({ title: '发票申请已提交' }); this.load(); });
      } });
    } });
  },
  onShareAppMessage() { return app.share('/pages/billing/billing', 'from=share'); }
});
