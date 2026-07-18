const app = getApp();
Page({
  data: { url: '' },
  onLoad(options) {
    const id = options.id;
    const base = String(app.globalData.apiBase || '').replace(/\/api\/?$/, '');
    // 复用平台端已生成的在保/参保证明页（web），小程序内以 web-view 打开、可分享/截图。
    this.setData({ url: `${base}/certificate/person/${id}` });
  }
});
