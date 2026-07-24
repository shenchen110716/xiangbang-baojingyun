function ensureOpenid(app) {
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
}

// wx.requestPayment 成功只代表用户在微信侧完成了支付授权，真正的到账入账是异步的
// wechat-notify 回调；两者之间有个时间差。这里在回调 onSuccess 前轮询订单状态几次，
// 尽量等回调落地后再让页面刷新余额/记录，减少"支付成功但页面还是旧余额"的观感。
function pollUntilPaid(app, orderNo, attempts = 5, intervalMs = 1200) {
  return app.request(`/payments/${orderNo}`, { silent: true }).then((status) => {
    if (status.status === 'paid' || attempts <= 1) return status;
    return new Promise((resolve) => setTimeout(resolve, intervalMs)).then(() => pollUntilPaid(app, orderNo, attempts - 1, intervalMs));
  }).catch(() => ({ status: 'unknown' }));
}

// account: 'usage' | 'premium' — 目前仅使用费账户支持微信支付，保费账户仍走银行转账人工审核
function payWithWeChat(app, enterpriseId, account, amount, { onSuccess, onCancel, onError } = {}) {
  wx.showLoading({ title: '正在下单…' });
  ensureOpenid(app)
    .then(() => app.request('/payments', { method: 'POST', data: { enterprise_id: Number(enterpriseId), account, amount, channel: 'jsapi' }, silent: true }))
    .then((order) => {
      wx.hideLoading();
      wx.requestPayment({
        timeStamp: order.timeStamp,
        nonceStr: order.nonceStr,
        package: order.package,
        signType: order.signType || 'RSA',
        paySign: order.paySign,
        success: () => {
          wx.showLoading({ title: '正在确认到账…' });
          pollUntilPaid(app, order.order_no).finally(() => {
            wx.hideLoading();
            wx.showToast({ title: '支付成功' });
            if (onSuccess) onSuccess();
          });
        },
        fail: () => { wx.showToast({ title: '已取消支付', icon: 'none' }); if (onCancel) onCancel(); }
      });
    })
    .catch((error) => {
      wx.hideLoading();
      wx.showToast({ title: error.message || '下单失败，请重试', icon: 'none' });
      if (onError) onError(error);
    });
}

module.exports = { ensureOpenid, payWithWeChat };
