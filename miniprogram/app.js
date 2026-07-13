const DEFAULT_API_BASE = 'https://eddie-cingular-server-gba.trycloudflare.com/api';

App({
  globalData: {
    apiBase: DEFAULT_API_BASE,
    token: '',
    user: null,
    enterprise: null
  },

  onLaunch() {
    this.globalData.apiBase = wx.getStorageSync('apiBase') || DEFAULT_API_BASE;
    this.globalData.token = wx.getStorageSync('token') || '';
    this.globalData.user = wx.getStorageSync('user') || null;
  },

  setApiBase(value) {
    const base = String(value || '').trim().replace(/\/$/, '');
    if (!/^https?:\/\//.test(base)) throw new Error('服务地址必须以 http:// 或 https:// 开头');
    this.globalData.apiBase = base.endsWith('/api') ? base : `${base}/api`;
    wx.setStorageSync('apiBase', this.globalData.apiBase);
  },

  login(username, password) {
    return this.rawRequest('/auth/login', {
      method: 'POST',
      data: { username, password, portal: 'enterprise' },
      skipAuth: true
    }).then((data) => {
      this.globalData.token = data.access_token;
      wx.setStorageSync('token', data.access_token);
      return this.loadProfile();
    });
  },

  loadProfile() {
    return this.request('/auth/me').then((user) => {
      this.globalData.user = user;
      wx.setStorageSync('user', user);
      return this.request('/enterprises').then((items) => {
        this.globalData.enterprise = items[0] || null;
        return user;
      });
    });
  },

  ensureLogin() {
    if (!this.globalData.token) {
      wx.reLaunch({ url: '/pages/login/login' });
      return Promise.reject(new Error('请先登录'));
    }
    return Promise.resolve(this.globalData.user || this.loadProfile());
  },

  rawRequest(path, options = {}) {
    const header = { 'Content-Type': 'application/json', ...(options.header || {}) };
    if (!options.skipAuth && this.globalData.token) header.Authorization = `Bearer ${this.globalData.token}`;
    return new Promise((resolve, reject) => {
      wx.request({
        url: `${this.globalData.apiBase}${path}`,
        method: options.method || 'GET',
        data: options.data,
        header,
        timeout: options.timeout || 20000,
        success: (res) => {
          if (res.statusCode === 401 && !options.skipAuth) {
            this.logout(true);
            reject(new Error('登录已过期，请重新登录'));
            return;
          }
          if (res.statusCode >= 200 && res.statusCode < 300) resolve(res.data);
          else reject(new Error((res.data && res.data.detail) || `请求失败（${res.statusCode}）`));
        },
        fail: (error) => reject(new Error(error.errMsg && error.errMsg.includes('timeout') ? '请求超时，请检查网络' : '无法连接业务服务，请检查服务地址'))
      });
    });
  },

  request(path, options = {}) {
    return this.rawRequest(path, options).catch((error) => {
      if (!options.silent) wx.showToast({ title: error.message, icon: 'none', duration: 2600 });
      throw error;
    });
  },

  upload(path, filePath, name = 'file', formData = {}) {
    return new Promise((resolve, reject) => {
      wx.uploadFile({
        url: `${this.globalData.apiBase}${path}`,
        filePath,
        name,
        formData,
        header: { Authorization: `Bearer ${this.globalData.token}` },
        timeout: 120000,
        success: (res) => {
          let data = {};
          try { data = JSON.parse(res.data || '{}'); } catch (error) { data = {}; }
          if (res.statusCode >= 200 && res.statusCode < 300) resolve(data);
          else {
            const message = data.detail || '文件上传失败';
            wx.showToast({ title: message, icon: 'none' });
            reject(new Error(message));
          }
        },
        fail: () => {
          const error = new Error('文件上传失败，请重试');
          wx.showToast({ title: error.message, icon: 'none' });
          reject(error);
        }
      });
    });
  },

  logout(navigate = true) {
    this.globalData.token = '';
    this.globalData.user = null;
    this.globalData.enterprise = null;
    wx.removeStorageSync('token');
    wx.removeStorageSync('user');
    if (navigate) wx.reLaunch({ url: '/pages/login/login' });
  },

  share(path, query = '') {
    const suffix = query ? `?${query}` : '';
    return { title: '响帮帮保经云｜企业员工保障服务', path: `${path}${suffix}` };
  },

  statusText(value) {
    return ({ pending: '待审核', active: '在保', stopped: '已停保', paused: '已暂停', approved: '已通过', rejected: '已驳回', supplement: '待补材料', reported: '已报案', collecting: '材料收集中', submitted: '已提交保司', insurer_review: '保司审核中', paid: '已赔付', closed: '已结案' })[value] || value || '未知';
  }
});
