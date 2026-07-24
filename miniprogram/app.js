const DEFAULT_API_BASE = 'https://xiangbang-baojingyun.onrender.com/api';

App({
  globalData: {
    apiBase: DEFAULT_API_BASE,
    token: '',
    user: null,
    enterprise: null,
    isDevEnv: false
  },

  onLaunch() {
    try {
      const { miniProgram } = wx.getAccountInfoSync();
      this.globalData.isDevEnv = miniProgram.envVersion !== 'release';
    } catch (error) {
      this.globalData.isDevEnv = false;
    }
    //正式版禁止切换服务地址，避免已登录用户被诱导改到攻击者服务器后继续携带 token 请求。
    this.globalData.apiBase = (this.globalData.isDevEnv && wx.getStorageSync('apiBase')) || DEFAULT_API_BASE;
    this.globalData.token = wx.getStorageSync('token') || '';
    this.globalData.user = wx.getStorageSync('user') || null;
  },

  setApiBase(value) {
    if (!this.globalData.isDevEnv) throw new Error('正式版不支持修改服务地址');
    const base = String(value || '').trim().replace(/\/$/, '');
    if (!/^https?:\/\//.test(base)) throw new Error('服务地址必须以 http:// 或 https:// 开头');
    const next = base.endsWith('/api') ? base : `${base}/api`;
    if (next !== this.globalData.apiBase) this.logout(false);
    this.globalData.apiBase = next;
    wx.setStorageSync('apiBase', next);
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
    if (!this.globalData.token) {
      this.logout(true);
      return Promise.reject(new Error('登录已过期，请重新登录'));
    }
    return new Promise((resolve, reject) => {
      wx.uploadFile({
        url: `${this.globalData.apiBase}${path}`,
        filePath,
        name,
        formData,
        header: { Authorization: `Bearer ${this.globalData.token}` },
        timeout: 600000,
        success: (res) => {
          let data = {};
          try { data = JSON.parse(res.data || '{}'); } catch (error) { data = {}; }
          if (res.statusCode >= 200 && res.statusCode < 300) resolve(data);
          else {
            if (res.statusCode === 401) this.logout(true);
            const message = data.detail || (res.statusCode === 413 ? '文件过大，请压缩或拆分后重试' : `文件上传失败（${res.statusCode}）`);
            wx.showToast({ title: message, icon: 'none' });
            reject(new Error(message));
          }
        },
        fail: (detail) => {
          const reason = (detail && detail.errMsg) || '';
          let message = '文件上传失败，请重试';
          if (reason.includes('timeout')) message = '上传超时，请压缩文件或切换网络后重试';
          else if (reason.includes('domain')) message = '上传域名未配置，请联系平台管理员';
          else if (reason.includes('abort')) message = '上传已取消';
          const error = new Error(message);
          wx.showToast({ title: error.message, icon: 'none' });
          reject(error);
        }
      });
    });
  },

  downloadAndOpen(path, options = {}) {
    if (!this.globalData.token) {
      this.logout(true);
      return Promise.reject(new Error('登录已过期，请重新登录'));
    }
    const filename = String(options.filename || '响帮帮文件').replace(/[\\/:*?"<>|]/g, '-');
    const fileType = String(options.fileType || filename.split('.').pop() || '').toLowerCase();
    const url = /^https?:\/\//.test(path) ? path : `${this.globalData.apiBase}${path}`;
    const filePath = `${wx.env.USER_DATA_PATH}/${filename}`;
    wx.showLoading({ title: options.loadingTitle || '正在下载', mask: true });
    return new Promise((resolve, reject) => {
      const fail = (title, content, error) => {
        wx.hideLoading();
        wx.showModal({ title, content, showCancel: false });
        reject(error instanceof Error ? error : new Error(content));
      };
      wx.request({
        url,
        method: 'GET',
        header: { Authorization: `Bearer ${this.globalData.token}` },
        responseType: 'arraybuffer',
        timeout: options.timeout || 60000,
        success: (res) => {
          if (res.statusCode === 401) {
            wx.hideLoading();
            this.logout(true);
            reject(new Error('登录已过期，请重新登录'));
            return;
          }
          if (res.statusCode < 200 || res.statusCode >= 300 || !(res.data instanceof ArrayBuffer) || !res.data.byteLength) {
            fail('下载失败', `文件服务返回异常（${res.statusCode}），请稍后重试。`);
            return;
          }
          wx.getFileSystemManager().writeFile({
            filePath,
            data: res.data,
            success: () => {
              wx.hideLoading();
              wx.openDocument({
                filePath,
                fileType,
                showMenu: true,
                success: () => resolve(filePath),
                fail: (error) => {
                  wx.showModal({ title: '文件已下载', content: '微信无法直接打开该文件，请升级微信或选择其他应用打开。', showCancel: false });
                  reject(error);
                }
              });
            },
            fail: (error) => fail('保存失败', '无法保存文件，请清理微信存储空间后重试。', error)
          });
        },
        fail: (error) => fail('下载失败', (error && error.errMsg || '').includes('timeout') ? '下载超时，请检查网络后重试。' : '无法连接文件服务，请检查网络后重试。', error)
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
  },

  // 次日生效方案的生效/停保时间总是落在自然日边界上，只显示日期；
  // 即时生效方案精确到分钟才有意义（24 小时倒计时），显示完整时间。
  formatCoverageDate(value, effectiveMode) {
    if (!value) return '—';
    const date = new Date(value);
    if (effectiveMode === 'immediate') {
      const pad = (n) => String(n).padStart(2, '0');
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
    }
    const pad = (n) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
  }
});
