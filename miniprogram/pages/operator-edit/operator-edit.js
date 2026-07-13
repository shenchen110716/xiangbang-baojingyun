const app = getApp();

Page({
  data: { form: { name: '', username: '', password: '', phone: '' }, saving: false },
  input(e) { this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value }); },
  save() {
    const form = this.data.form;
    if (!form.name.trim() || !form.username.trim() || form.password.length < 6) { wx.showToast({ title: '请填写姓名、账号和至少 6 位密码', icon: 'none' }); return; }
    this.setData({ saving: true });
    app.request('/operators', { method: 'POST', data: form }).then(() => { wx.showToast({ title: '操作员已创建' }); setTimeout(() => wx.navigateBack(), 500); }).catch(() => this.setData({ saving: false }));
  }
});
