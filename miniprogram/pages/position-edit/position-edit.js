const app = getApp();
Page({
  data: { id: 0, form: { actual_employer: '', actual_employer_id: null, name: '' }, employers: [], employerIndex: 0, videos: [], item: null, saving: false, uploading: false, loading: true },
  onLoad(options) { this.setData({ id: Number(options.id || 0) }); this.load(); },
  load() {
    Promise.all([app.request('/actual-employers'), this.data.id ? app.request('/positions') : Promise.resolve([]), this.data.id ? app.request(`/positions/${this.data.id}/videos`) : Promise.resolve([])])
      .then(([employers, positions, videos]) => {
        const activeEmployers = employers.filter((row) => row.status === 'active');
        const item = positions.find((row) => row.id === this.data.id) || null;
        // 有 id 但列表里找不到对应岗位，说明这不是本企业的岗位（越权/脏链接），
        // 跟请求 403 一样处理：不要渲染半成品表单。
        if (this.data.id && !item) { this.setData({ loading: false }); wx.showToast({ title: '该岗位不存在或无权查看', icon: 'none' }); wx.navigateBack(); return; }
        const employerIndex = Math.max(0, activeEmployers.findIndex((row) => item && row.id === item.actual_employer_id)); const form = item ? { actual_employer: item.actual_employer_name || item.actual_employer, actual_employer_id: item.actual_employer_id, name: item.name } : { actual_employer: (activeEmployers[0] && activeEmployers[0].name) || '', actual_employer_id: (activeEmployers[0] && activeEmployers[0].id) || null, name: '' };
        const itemWithLabel = item ? { ...item, status_label: app.statusText(item.status) } : null;
        const videosWithLabel = (videos || []).map((v) => ({ ...v, status_label: app.statusText(v.status) }));
        this.setData({ employers: activeEmployers, item: itemWithLabel, videos: videosWithLabel, employerIndex, form, loading: false });
      })
      .catch(() => {
        this.setData({ loading: false });
        if (this.data.id) { wx.showToast({ title: '加载失败或无权查看该岗位', icon: 'none' }); wx.navigateBack(); }
      });
  },
  input(e) { this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value }); },
  employerChange(e) { const employerIndex = Number(e.detail.value), employer = this.data.employers[employerIndex]; this.setData({ employerIndex, 'form.actual_employer_id': employer.id, 'form.actual_employer': employer.name }); },
  save() { const form = this.data.form; if (!form.name.trim() || !form.actual_employer_id) { wx.showToast({ title: '请填写岗位并选择实际工作单位', icon: 'none' }); return; } const creating = !this.data.id; this.setData({ saving: true }); const request = app.request(creating ? '/positions' : `/positions/${this.data.id}`, { method: creating ? 'POST' : 'PATCH', data: form }); request.then((item) => { if (creating) { this.setData({ id: item.id, saving: false }, () => { wx.showToast({ title: '请继续上传岗位视频', icon: 'none' }); this.load(); this.uploadVideo(); }); } else { wx.showToast({ title: '已保存' }); this.setData({ saving: false }); this.load(); } }).catch(() => this.setData({ saving: false })); },
  uploadVideo() {
    if (!this.data.id) { wx.showToast({ title: '请先保存岗位', icon: 'none' }); return; }
    wx.chooseMedia({
      count: 1,
      mediaType: ['video'],
      sourceType: ['album', 'camera'],
      maxDuration: 60,
      success: (res) => {
        const file = res.tempFiles && res.tempFiles[0];
        if (!file || !file.tempFilePath) { wx.showToast({ title: '未获取到视频文件，请重新选择', icon: 'none' }); return; }
        if (file.size && file.size > 100 * 1024 * 1024) { wx.showToast({ title: '视频不能超过 100MB', icon: 'none' }); return; }
        const extensionMatch = file.tempFilePath.match(/\.(mp4|mov|m4v)(?:\?|$)/i);
        const fileExt = extensionMatch ? extensionMatch[1].toLowerCase() : 'mp4';
        this.setData({ uploading: true });
        // app.upload() already surfaces a toast for both the server-rejected
        // and network-failure cases internally, so this .catch is only
        // resetting local state, not re-showing the message.
        app.upload(`/positions/${this.data.id}/videos/upload`, file.tempFilePath, 'file', { file_ext: fileExt })
          .then(() => { wx.showToast({ title: '视频已提交' }); this.setData({ uploading: false }); this.load(); })
          .catch(() => this.setData({ uploading: false }));
      },
      // wx.chooseMedia 没有 fail 回调时，用户取消选择、拒绝相机/相册权限、或开发者工具
      // 模拟器本身不支持真实取景，都会在没有任何提示的情况下"什么也不做"——
      // 看起来就像"上传失败"，实际上根本没有发起过网络请求。
      fail: (error) => {
        const message = (error && error.errMsg) || '';
        if (message.includes('cancel')) return;
        wx.showToast({ title: message.includes('auth') ? '请在小程序设置中允许访问相机/相册' : '选择视频失败，请重试', icon: 'none' });
      }
    });
  }
});
