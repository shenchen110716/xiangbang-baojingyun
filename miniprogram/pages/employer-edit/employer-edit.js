const app = getApp();
Page({
  data: { id: 0, form: { name: '', credit_code: '', contact: '', phone: '' }, saving: false },
  onLoad(options) { const id = Number(options.id || 0); if (!id) return; this.setData({ id }); app.request('/actual-employers').then((items) => { const item = items.find((row) => row.id === id); if (item) this.setData({ form: { name: item.name || '', credit_code: item.credit_code || '', contact: item.contact || '', phone: item.phone || '' } }); }); },
  input(e) { this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value }); },
  save() {
    const form = this.data.form; if (!form.name.trim()) { wx.showToast({ title: '请填写单位名称', icon: 'none' }); return; }
    this.setData({ saving: true }); app.request(this.data.id ? `/actual-employers/${this.data.id}` : '/actual-employers', { method: this.data.id ? 'PATCH' : 'POST', data: form }).then(() => { wx.showToast({ title: this.data.id ? '已保存' : '已新增' }); setTimeout(() => wx.navigateBack(), 500); }).catch(() => this.setData({ saving: false }));
  }
});
