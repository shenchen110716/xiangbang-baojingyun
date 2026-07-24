const app = getApp();

Page({
  data: {
    username: '',
    password: '',
    apiBase: '',
    showServer: false,
    isDevEnv: false,
    loading: false,
    error: ''
  },
  onLoad(options) {
    this.setData({ apiBase: app.globalData.apiBase, isDevEnv: app.globalData.isDevEnv });
    if (options.switch === '1') this.setData({ username: '', password: '', error: '请输入要切换的操作员账号和密码' });
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
    if (this.data.isDevEnv) {
      try { app.setApiBase(this.data.apiBase); }
      catch (error) { this.setData({ loading: false, error: error.message }); return; }
    }
    app.login(this.data.username.trim(), this.data.password)
      .then(() => wx.switchTab({ url: '/pages/home/home' }))
      .catch((error) => this.setData({ loading: false, error: error.message }));
  }
});
