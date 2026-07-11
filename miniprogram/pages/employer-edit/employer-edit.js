const app = getApp();
Page({
  data: { form: { name: '', credit_code: '', contact: '', phone: '' }, saving: false },
  input(e) { this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value }); },
  save() {
    const form = this.data.form; if (!form.name.trim()) { wx.showToast({ title: '请填写单位名称', icon: 'none' }); return; }
    this.setData({ saving: true }); app.request('/actual-employers', { method: 'POST', data: form }).then(() => { wx.showToast({ title: '已新增' }); setTimeout(() => wx.navigateBack(), 500); }).catch(() => this.setData({ saving: false }));
  }
});
