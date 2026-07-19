const app = getApp();
Page({
  data: { items: [], invoices: [], loading: true },
  onShow() { this.load(); },
  load() { return Promise.all([app.request('/billing', { silent: true }), app.request('/invoices', { silent: true })]).then(([items, invoices]) => this.setData({ items: items.map((item) => ({ ...item, balance_text: Number(item.balance || 0).toFixed(2), estimated_text: Number(item.estimated_daily || 0).toFixed(2), month_accrued_text: Number(item.month_accrued || 0).toFixed(2), total_accrued_text: Number(item.total_accrued || 0).toFixed(2) })), invoices: invoices.map((item) => ({ ...item, amount_text: Number(item.amount || 0).toFixed(2), status_label: ({ pending: '待审核', approved: '已审核', issued: '已开票', rejected: '已驳回' })[item.status] || item.status })), loading: false })).catch(() => this.setData({ loading: false })); },
  recharge(e) {
    const id = e.currentTarget.dataset.id, account = e.currentTarget.dataset.account;
    // 7.18-3：可拍回单让后端 OCR 识别金额，再确认充值；也可手动输入。
    wx.showActionSheet({
      itemList: ['拍回单自动识别金额', '手动输入金额'],
      success: (r) => {
        if (r.tapIndex === 0) this.rechargeByOcr(id, account);
        else if (r.tapIndex === 1) this.rechargeManual(id, account);
      }
    });
  },
  rechargeManual(id, account) {
    wx.showModal({ title: account === 'premium' ? '保费账户充值' : '平台使用费充值', editable: true, placeholderText: '请输入充值金额', confirmText: '确认充值', success: (res) => { if (!res.confirm) return; const amount = Number(res.content); if (!amount || amount <= 0) { wx.showToast({ title: '请输入有效金额', icon: 'none' }); return; } this.doRecharge(id, account, amount); } });
  },
  rechargeByOcr(id, account) {
    wx.chooseMedia({
      count: 1, mediaType: ['image'], sourceType: ['camera', 'album'], sizeType: ['compressed'],
      success: (res) => {
        const filePath = res.tempFiles[0].tempFilePath;
        wx.showLoading({ title: '识别中…' });
        wx.uploadFile({
          url: `${app.globalData.apiBase}/ocr/receipt-amount`, filePath, name: 'file',
          header: { Authorization: `Bearer ${app.globalData.token}` },
          success: (up) => {
            wx.hideLoading();
            let data = {}; try { data = JSON.parse(up.data || '{}'); } catch (e) { data = {}; }
            if (up.statusCode !== 200) { wx.showToast({ title: data.detail || '识别失败，请手动输入', icon: 'none' }); return; }
            const amount = Number(data.amount || 0);
            if (!amount) { wx.showToast({ title: '未识别到金额，请手动输入', icon: 'none' }); return; }
            wx.showModal({
              title: '确认充值金额',
              content: `${data.mock ? '模拟识别' : '识别'}金额 ¥${amount.toFixed(2)}，确认为该单位充值？`,
              confirmText: '确认充值',
              success: (c) => { if (c.confirm) this.doRecharge(id, account, amount); }
            });
          },
          fail: () => { wx.hideLoading(); wx.showToast({ title: '上传失败，请重试', icon: 'none' }); }
        });
      }
    });
  },
  doRecharge(id, account, amount) {
    app.request(`/enterprises/${id}/recharge`, { method: 'POST', data: { account, amount } }).then(() => { wx.showToast({ title: '充值成功' }); this.load(); });
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
