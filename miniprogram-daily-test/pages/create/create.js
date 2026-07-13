const app = getApp();

Page({
  data: {
    categories: ['人员', '参保', '客户', '理赔', '财务', '其他'],
    priorities: [
      { key: 'high', label: '紧急' },
      { key: 'medium', label: '普通' },
      { key: 'low', label: '稍后' }
    ],
    categoryIndex: 0,
    priorityIndex: 1,
    form: { title: '', note: '', dueDate: '' },
    saving: false
  },

  onLoad() {
    this.setData({ 'form.dueDate': app.today() });
  },

  input(e) {
    this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value });
  },

  categoryChange(e) {
    this.setData({ categoryIndex: Number(e.detail.value) });
  },

  priorityChange(e) {
    this.setData({ priorityIndex: Number(e.detail.value) });
  },

  dateChange(e) {
    this.setData({ 'form.dueDate': e.detail.value });
  },

  save() {
    const title = this.data.form.title.trim();
    if (!title) {
      wx.showToast({ title: '请输入任务名称', icon: 'none' });
      return;
    }
    this.setData({ saving: true });
    app.addTask({
      ...this.data.form,
      title,
      category: this.data.categories[this.data.categoryIndex],
      priority: this.data.priorities[this.data.priorityIndex].key
    });
    wx.showToast({ title: '任务已创建', icon: 'success' });
    setTimeout(() => wx.navigateBack(), 500);
  }
});
