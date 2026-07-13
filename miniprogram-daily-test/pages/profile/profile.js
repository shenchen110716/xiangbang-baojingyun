const app = getApp();

Page({
  data: {
    version: '',
    taskCount: 0,
    logCount: 0
  },

  onShow() {
    this.setData({
      version: app.globalData.version,
      taskCount: app.getTasks().length,
      logCount: app.getLogs().length
    });
  },

  reset() {
    wx.showModal({
      title: '恢复演示数据',
      content: '本地创建的任务和记录会被测试数据替换，是否继续？',
      success: (result) => {
        if (!result.confirm) return;
        app.resetDemo();
        this.onShow();
        wx.showToast({ title: '已恢复演示数据', icon: 'success' });
      }
    });
  },

  about() {
    wx.showModal({
      title: '响帮帮日常·测试版',
      content: '用于演示团队日常待办与工作记录。当前版本完全离线，不连接生产数据。',
      showCancel: false
    });
  }
});
