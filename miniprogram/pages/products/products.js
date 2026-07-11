const app=getApp();Page({data:{items:[]},onShow(){app.request('/plans').then(r=>this.setData({items:r.data||[]}));},onShareAppMessage(){return app.share('/pages/products/products','from=share');}});
