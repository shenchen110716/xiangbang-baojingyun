const app = getApp();

Page({
  data: {
    filter: 'all',
    filters: [
      { key: 'all', label: '全部' },
      { key: 'pending', label: '待处理' },
      { key: 'done', label: '已完成' }
    ],
    tasks: [],
    visibleTasks: []
  },

  onShow() {
    this.load();
  },

  load() {
    const tasks = app.getTasks();
    this.setData({ tasks }, () => this.applyFilter());
  },

  selectFilter(e) {
    this.setData({ filter: e.currentTarget.dataset.key }, () => this.applyFilter());
  },

  applyFilter() {
    const filter = this.data.filter;
    const visibleTasks = this.data.tasks.filter((item) => filter === 'all' || (filter === 'done' ? item.done : !item.done));
    this.setData({ visibleTasks });
  },

  toggle(e) {
    app.toggleTask(e.currentTarget.dataset.id);
    this.load();
  },

  remove(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除任务',
      content: '确定删除这条测试任务吗？',
      success: (result) => {
        if (!result.confirm) return;
        app.deleteTask(id);
        this.load();
        wx.showToast({ title: '已删除' });
      }
    });
  },

  create() {
    wx.navigateTo({ url: '/pages/create/create' });
  }
});
