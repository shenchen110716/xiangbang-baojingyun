const app = getApp();

Page({
  data: {
    content: '',
    moods: ['顺利', '进行中', '需协助'],
    moodIndex: 0,
    logs: []
  },

  onShow() {
    this.load();
  },

  load() {
    this.setData({ logs: app.getLogs() });
  },

  input(e) {
    this.setData({ content: e.detail.value });
  },

  moodChange(e) {
    this.setData({ moodIndex: Number(e.detail.value) });
  },

  add() {
    const content = this.data.content.trim();
    if (!content) {
      wx.showToast({ title: '写下今天完成的事情', icon: 'none' });
      return;
    }
    const logs = app.addLog(content, this.data.moods[this.data.moodIndex]);
    this.setData({ content: '', logs });
    wx.showToast({ title: '记录已保存', icon: 'success' });
  }
});
