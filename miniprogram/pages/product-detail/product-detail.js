const app = getApp();

// 签名下载链接是相对路径（/api/...），拼上 apiBase 的站点部分变成绝对地址
function absoluteUrl(path) { return app.globalData.apiBase.replace(/\/api$/, '') + path; }

Page({
  data: { id: 0, item: null, tiers: [], loading: true, imageUrl: '', imageLoading: false },
  onLoad(options) {
    const id = Number(options.id); this.setData({ id });
    Promise.all([app.request('/plans'), app.request(`/plan-tiers?plan_id=${id}`)])
      .then(([plans, tiers]) => {
        const item = plans.find((row) => row.id === id) || null;
        this.setData({ item, tiers, loading: false });
        if (item && item.has_image) this.loadImage();
      })
      .catch(() => this.setData({ loading: false }));
  },
  loadImage() {
    app.request(`/plans/${this.data.id}/image-link`, { silent: true })
      .then((link) => this.setData({ imageUrl: absoluteUrl(link.url) }))
      .catch(() => {});
  },
  previewImage() {
    // 签名链接只有5分钟有效期，预览/下载都现取新链接，避免停留过久后点开已过期
    app.request(`/plans/${this.data.id}/image-link`)
      .then((link) => wx.previewImage({ urls: [absoluteUrl(link.url)] }));
  },
  saveImage() {
    this.setData({ imageLoading: true });
    app.request(`/plans/${this.data.id}/image-link`)
      .then((link) => new Promise((resolve, reject) => {
        wx.downloadFile({ url: absoluteUrl(link.url), success: resolve, fail: reject });
      }))
      .then((res) => new Promise((resolve, reject) => {
        if (res.statusCode !== 200 || !res.tempFilePath) { reject(new Error('下载失败')); return; }
        wx.saveImageToPhotosAlbum({ filePath: res.tempFilePath, success: resolve, fail: reject });
      }))
      .then(() => { this.setData({ imageLoading: false }); wx.showToast({ title: '已保存到相册' }); })
      .catch((error) => {
        this.setData({ imageLoading: false });
        const message = (error && error.errMsg) || '';
        if (message.includes('cancel')) return;
        wx.showToast({ title: message.includes('auth') ? '请在设置中允许保存到相册' : '保存失败，请重试', icon: 'none' });
      });
  },
  positions() { wx.navigateTo({ url: '/pages/positions/positions' }); },
  onShareAppMessage() { return app.share('/pages/product-detail/product-detail', `id=${this.data.id}`); }
});
