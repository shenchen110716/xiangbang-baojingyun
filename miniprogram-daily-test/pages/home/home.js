const app = getApp();

Page({
  data: {
    dateText: '',
    greeting: '',
    tasks: [],
    previewTasks: [],
    total: 0,
    pending: 0,
    done: 0,
    progress: 0
  },

  onShow() {
    this.load();
  },

  load() {
    const now = new Date();
    const tasks = app.getTasks();
    const done = tasks.filter((item) => item.done).length;
    const pending = tasks.length - done;
    const progress = tasks.length ? Math.round(done / tasks.length * 100) : 0;
    const hour = now.getHours();
    const greeting = hour < 11 ? '早上好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好';
    this.setData({
      dateText: `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日`,
      greeting,
      tasks,
      previewTasks: tasks.filter((item) => !item.done).slice(0, 3),
      total: tasks.length,
      pending,
      done,
      progress
    });
  },

  toggle(e) {
    app.toggleTask(e.currentTarget.dataset.id);
    wx.showToast({ title: '状态已更新', icon: 'success' });
    this.load();
  },

  openTasks() {
    wx.switchTab({ url: '/pages/tasks/tasks' });
  },

  createTask() {
    wx.navigateTo({ url: '/pages/create/create' });
  },

  openLogs() {
    wx.switchTab({ url: '/pages/logs/logs' });
  }
});
