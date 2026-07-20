const app = getApp();
Page({
  data: { items: [], invoices: [], loading: true },
  onShow() { this.load(); },
  load() { return Promise.all([app.request('/billing', { silent: true }), app.request('/invoices', { silent: true })]).then(([items, invoices]) => this.setData({ items: items.map((item) => ({ ...item, balance_text: Number(item.balance || 0).toFixed(2), estimated_text: Number(item.estimated_daily || 0).toFixed(2), month_accrued_text: Number(item.month_accrued || 0).toFixed(2), total_accrued_text: Number(item.total_accrued || 0).toFixed(2) })), invoices: invoices.map((item) => ({ ...item, amount_text: Number(item.amount || 0).toFixed(2), status_label: ({ pending: '待审核', approved: '已审核', issued: '已开票', rejected: '已驳回' })[item.status] || item.status })), loading: false })).catch(() => this.setData({ loading: false })); },
  recharge(e) {
    const id = e.currentTarget.dataset.id;
    // 平台服务费在线缴纳：微信支付（JSAPI），支付成功由后端 wechat-notify 回调自动到账。
    wx.showModal({
      title: '平台使用费缴纳', editable: true, placeholderText: '请输入缴纳金额', confirmText: '微信支付',
      success: (res) => {
        if (!res.confirm) return;
        const amount = Number(res.content);
        if (!amount || amount <= 0) { wx.showToast({ title: '请输入有效金额', icon: 'none' }); return; }
        this.payWithWeChat(id, amount);
      }
    });
  },
  ensureOpenid() {
    if (app.globalData.user && app.globalData.user.wx_openid) return Promise.resolve(app.globalData.user.wx_openid);
    return new Promise((resolve, reject) => {
      wx.login({
        success: (loginRes) => {
          if (!loginRes.code) { reject(new Error('微信登录失败，请重试')); return; }
          app.request('/wechat/bind-openid', { method: 'POST', data: { code: loginRes.code }, silent: true })
            .then((r) => {
              app.globalData.user = { ...(app.globalData.user || {}), wx_openid: r.wx_openid };
              wx.setStorageSync('user', app.globalData.user);
              resolve(r.wx_openid);
            })
            .catch(reject);
        },
        fail: () => reject(new Error('微信登录失败，请重试'))
      });
    });
  },
  payWithWeChat(enterpriseId, amount) {
    wx.showLoading({ title: '正在下单…' });
    this.ensureOpenid()
      .then(() => app.request('/payments', { method: 'POST', data: { enterprise_id: Number(enterpriseId), account: 'usage', amount, channel: 'jsapi' }, silent: true }))
      .then((order) => {
        wx.hideLoading();
        wx.requestPayment({
          timeStamp: order.timeStamp,
          nonceStr: order.nonceStr,
          package: order.package,
          signType: order.signType || 'RSA',
          paySign: order.paySign,
          success: () => { wx.showToast({ title: '支付成功' }); this.load(); },
          fail: () => { wx.showToast({ title: '已取消支付', icon: 'none' }); }
        });
      })
      .catch((error) => { wx.hideLoading(); wx.showToast({ title: error.message || '下单失败，请重试', icon: 'none' }); });
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
