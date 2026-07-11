App({
  globalData: { apiBase: 'https://bunch-continent-contractors-earnings.trycloudflare.com/api', token: '', user: null },
  onLaunch() { this.globalData.token = wx.getStorageSync('token') || ''; },
  login(username='enterprise', password='enterprise123') { return new Promise((resolve,reject)=>wx.request({url:this.globalData.apiBase+'/auth/login',method:'POST',data:{username,password,portal:'enterprise'},success:r=>{if(r.statusCode>=200&&r.statusCode<300){this.globalData.token=r.data.access_token;wx.setStorageSync('token',r.data.access_token);resolve(r.data);}else reject(new Error(r.data?.detail||'登录失败'));},fail:reject})); },
  logout(){this.globalData.token='';this.globalData.user=null;wx.removeStorageSync('token');wx.reLaunch({url:'/pages/login/login'});},
  request(path, options = {}) { const header = Object.assign({}, options.header || {}, this.globalData.token?{ Authorization: `Bearer ${this.globalData.token}` }:{}); return new Promise((resolve, reject) => wx.request({ url: this.globalData.apiBase + path, ...options, header, success:r=>{if(r.statusCode===401){this.logout();reject(new Error('登录已过期'));return;}if(r.statusCode>=200&&r.statusCode<300)resolve(r);else reject(new Error(r.data?.detail||'请求失败'));}, fail: reject })); },
  share(path, query = '') { return { title: '响帮帮保经云｜灵活用工保障服务', path: `${path}?${query}` }; }
});
