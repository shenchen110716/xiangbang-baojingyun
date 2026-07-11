const app = getApp();

Page({
  data: {
    username: 'enterprise',
    password: '',
    apiBase: '',
    showServer: false,
    loading: false,
    error: ''
  },
  onLoad() {
    this.setData({ apiBase: app.globalData.apiBase });
    if (app.globalData.token) {
      app.loadProfile().then(() => wx.switchTab({ url: '/pages/home/home' })).catch(() => {});
    }
  },
  input(e) { this.setData({ [e.currentTarget.dataset.field]: e.detail.value }); },
  toggleServer() { this.setData({ showServer: !this.data.showServer }); },
  submit() {
    if (!this.data.username.trim() || !this.data.password) {
      this.setData({ error: '请输入账号和密码' });
      return;
    }
    this.setData({ loading: true, error: '' });
    try { app.setApiBase(this.data.apiBase); }
    catch (error) { this.setData({ loading: false, error: error.message }); return; }
    app.login(this.data.username.trim(), this.data.password)
      .then(() => wx.switchTab({ url: '/pages/home/home' }))
      .catch((error) => this.setData({ loading: false, error: error.message }));
  }
});
