const app = getApp();

Page({
  data: { current: '', next: '', confirm: '', saving: false, error: '' },
  input(e) { this.setData({ [e.currentTarget.dataset.key]: e.detail.value, error: '' }); },
  submit() {
    const { current, next, confirm } = this.data;
    if (!current) { this.setData({ error: '请输入当前密码' }); return; }
    if (next.length < 6) { this.setData({ error: '新密码至少 6 位' }); return; }
    if (next !== confirm) { this.setData({ error: '两次输入的新密码不一致' }); return; }
    this.setData({ saving: true, error: '' });
    app.request('/auth/password', { method: 'PATCH', data: { current_password: current, new_password: next } })
      .then(() => {
        wx.showToast({ title: '密码已修改' });
        setTimeout(() => wx.navigateBack(), 500);
      })
      .catch((error) => this.setData({ saving: false, error: error.message }));
  }
});
