const app = getApp();
Page({
  data: { apiBase: '', providers: {}, saving: false },
  onLoad() { this.setData({ apiBase: app.globalData.apiBase }); app.request('/providers/status', { silent: true }).then((providers) => this.setData({ providers })).catch(() => {}); },
  input(e) { this.setData({ apiBase: e.detail.value }); },
  save() { this.setData({ saving: true }); try { app.setApiBase(this.data.apiBase); wx.showToast({ title: '服务地址已保存' }); this.setData({ apiBase: app.globalData.apiBase, saving: false }); } catch (error) { wx.showToast({ title: error.message, icon: 'none' }); this.setData({ saving: false }); } },
  health() { wx.showLoading({ title: '检查中' }); app.rawRequest('/health', { skipAuth: true }).then(() => { wx.hideLoading(); wx.showToast({ title: '服务连接正常' }); }).catch((error) => { wx.hideLoading(); wx.showToast({ title: error.message, icon: 'none' }); }); }
});
