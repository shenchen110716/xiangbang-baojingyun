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
    // 一家企业可能有多个不共享余额的保司收款账户（EnterprisePremiumAccount 按
    // enterprise+account 而非 enterprise+insurer 记余额），只传 accountType 用户
    // 充值前还要在通用列表里盲选保司，很容易充到别的账户。这里把该卡片关联的保司
    // 带过去，充值页据此预选，充值就一定落到用户点的这张卡对应的账户上。
    const insurer = e.currentTarget.dataset.insurer || '';
    const insurerParam = insurer ? `&insurer=${encodeURIComponent(insurer)}` : '';
    wx.navigateTo({ url: `/pages/recharge-request/recharge-request?enterpriseId=${id}&accountType=${accountType}${insurerParam}` });
  },
  records() {
    const item = this.data.items[0];
    wx.navigateTo({ url: `/pages/recharge-request/recharge-request?enterpriseId=${item ? item.id : 0}&tab=records` });
  },
  // 发票抬头/纳税人识别号现在后端必填（用户反馈 2026-07-29）：小程序端用最近
  // 一次申请（invoices 列表按创建时间倒序，[0] 就是最近一次）当默认值，用户
  // 不输入直接确认时自动复用，不用每次重新打字；小程序端没有下拉选择控件，
  // 有多条历史抬头时只带出最近一条，跟电脑端的多条下拉选择不是同一套体验。
  invoice() {
    const account = this.data.items[0]; if (!account) { wx.showToast({ title: '暂无可开票账户', icon: 'none' }); return; }
    const last = this.data.invoices[0] || {};
    wx.showModal({ title: '发票抬头', editable: true, placeholderText: last.title || account.enterprise_name, success: (titleResult) => { if (!titleResult.confirm) return; const title = String(titleResult.content || last.title || account.enterprise_name).trim(); if (!title) { wx.showToast({ title: '请填写发票抬头', icon: 'none' }); return; }
      wx.showModal({ title: '纳税人识别号', editable: true, placeholderText: last.tax_no || '必填', success: (taxResult) => { if (!taxResult.confirm) return; const tax_no = String(taxResult.content || last.tax_no || '').trim(); if (!tax_no) { wx.showToast({ title: '请填写纳税人识别号', icon: 'none' }); return; }
        wx.showModal({ title: '开票金额', editable: true, placeholderText: '请输入开票金额', success: (amountResult) => { if (!amountResult.confirm) return; const amount = Number(amountResult.content); if (!amount || amount <= 0) { wx.showToast({ title: '请输入有效金额', icon: 'none' }); return; }
          app.request('/invoices', { method: 'POST', data: { enterprise_id: account.id, account: 'premium', amount, title, tax_no, email: '' } }).then(() => { wx.showToast({ title: '发票申请已提交' }); this.load(); });
        } });
      } });
    } });
  },
  // 保司/平台上传发票文件后，这里补上下载入口（用户反馈 2026-07-29：用户端
  // 之前完全没有下载功能，只能看状态文案）。document_download_url 是带短时
  // 签名 token 的 /api/... 绝对路径（跟理赔材料预览同一套模式，见 claim-
  // detail.js 的 preview()），downloadAndOpen 期望的是相对 apiBase 的路径，
  // 所以这里要先把 apiBase 末尾的 /api 去掉再拼接，否则会拼出 /api/api/...。
  downloadInvoice(e) {
    const url = e.currentTarget.dataset.url;
    const name = e.currentTarget.dataset.name || '发票.pdf';
    if (!url) return;
    const fullUrl = /^https?:/.test(url) ? url : `${app.globalData.apiBase.replace(/\/api$/, '')}${url}`;
    const fileType = String(name).split('.').pop().toLowerCase();
    app.downloadAndOpen(fullUrl, { filename: name, fileType, loadingTitle: '正在下载发票' }).catch(() => {});
  },
  onShareAppMessage() { return app.share('/pages/billing/billing', 'from=share'); }
});
