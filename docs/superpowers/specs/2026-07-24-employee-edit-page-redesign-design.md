# 新增/编辑参保员工页面重设计 Design

## 目标

小程序"新增/编辑参保员工"页面（`miniprogram/pages/employee-edit/`）视觉密度高、信息层级不清晰：标题、锁定提示条（橙）、岗位摘要卡（蓝）、三入口 chip、表单字段、提交按钮全部堆叠展示，缺乏分组。本次只做视觉/布局重排，不改字段名、状态、提交/校验逻辑。

## 范围

**这次做**：
1. 合并锁定提示条和岗位摘要卡为一张卡片。
2. 三入口（手工添加/拍照添加/批量导入）从胶囊 chip 改成等宽分段控件样式。
3. 生效时间/停保时间收进可展开的"更多选项"区域。
4. 统一表单分组间距，姓名/身份证号保持当前视觉权重，手机号 label 加"选填"提示。

**这次不做**：任何字段名/状态字段/提交请求/校验规则的变更；拍照批量列表内部（每行卡片）的结构不变；批量导入的跳转逻辑不变。

## 组件设计

### 1. 顶部卡片合并

原来两块（`wx:if="{{locked}}"` 的橙色提示条 + `wx:if="{{selectedPosition}}"` 的蓝色摘要卡）合并成一张卡片，只在 `locked` 为真时显示（未锁定时没有已选岗位，不需要这张卡）：

```html
<view wx:if="{{locked}}" class="position-summary">
  <view class="row between">
    <text class="strong">{{selectedPosition.actual_employer_name}}</text>
    <text class="tag tag-success">{{selectedPosition.occupation_class}}</text>
  </view>
  <view class="list-meta">岗位：{{selectedPosition.name}}｜审核状态：已通过</view>
  <view wx:if="{{addedCount}}" class="list-meta" style="margin-top:6rpx">已添加 {{addedCount}} 人，可继续添加</view>
</view>
```

`.position-summary` 用现有 `.card-plain` 的浅蓝背景（`#f4f7ff`）为基础，不新引入配色。

### 2. 三入口改为等宽分段控件

外层浅灰底胶囊容器（`.segmented`），内部三个等宽按钮，选中项白底+轻阴影：

```html
<view wx:if="{{!id}}" class="segmented">
  <view class="segmented-item {{addMode==='manual'?'active':''}}" data-mode="manual" bindtap="setAddMode">手工添加</view>
  <view class="segmented-item {{addMode==='photo'?'active':''}}" data-mode="photo" bindtap="setAddMode">拍照添加</view>
  <view class="segmented-item" data-mode="import" bindtap="setAddMode">批量导入</view>
</view>
```

样式（新增到 `employee-edit.wxss`）：
```css
.segmented{display:flex;background:#f0f2f6;border-radius:16rpx;padding:6rpx;margin:24rpx 0}
.segmented-item{flex:1;text-align:center;padding:16rpx 0;font-size:26rpx;color:#666;border-radius:12rpx}
.segmented-item.active{background:#fff;color:#1d4ed8;font-weight:600;box-shadow:0 4rpx 12rpx rgba(26,39,84,.08)}
```

`setAddMode(e)` 逻辑不变（`data-mode` 读取方式一致，只是外层容器和子元素的 class 名变了）。

### 3. 生效时间/停保时间收进"更多选项"

只在手工添加模式（`id || addMode==='manual'`）的表单区域内，姓名/身份证号/手机号/日结方式字段之后，加一个可展开区块：

```html
<view class="more-options-toggle" bindtap="toggleMoreOptions">
  <text>更多选项（生效/停保时间）</text>
  <text class="chevron">{{moreOptionsExpanded?'▴':'▾'}}</text>
</view>
<view wx:if="{{moreOptionsExpanded}}">
  <!-- 原有生效时间/停保时间两个 label.field，原样保留 -->
</view>
```

新增页面状态 `moreOptionsExpanded`（纯 UI 展开状态，不影响提交数据）：
- 新增模式（`!id`）默认 `false`（收起）——多数新增场景不需要手动设置这两个时间。
- 编辑模式（`id` 存在）默认 `true`（展开）——编辑时修改生效/停保时间是常见操作，不应该藏一层。

`onLoad` 里已经区分了 `id` 是否存在，加一行 `moreOptionsExpanded: !!id` 到 `setData` 里即可；`toggleMoreOptions()` 是新增的一个纯前端切换方法：
```js
toggleMoreOptions() { this.setData({ moreOptionsExpanded: !this.data.moreOptionsExpanded }); },
```

### 4. 表单分组细节

- 手机号 label 从"手机号"改成"手机号（选填）"，颜色不变（不新增样式类，直接改文案）。
- 姓名、身份证号、日结方式区块间距统一用现有 `.field` 的默认 margin，不额外调整。
- 提交按钮（`保存员工信息`/`保存并继续添加下一人`/`提交参保审核`）位置和文案逻辑完全不变。

### 5. 拍照添加区（无结构变化）

批量待确认列表的卡片样式、"确认提交 N 人"按钮位置均不变，只是外层容器统一用新的分段控件间距规范（`margin-top:16rpx`，与合并后的顶部卡片保持一致的呼吸感）。

## 测试

无自动化测试基础设施，验证方式同前几次：`node -c employee-edit.js` 语法检查 + WeChat DevTools 手工验证清单：
- 新增模式（无 `id`）：顶部无岗位摘要卡（除非 locked），分段控件三选一切换正常，"更多选项"默认收起、点击展开生效/停保时间字段。
- 从首页岗位卡片进入（locked=true）：合并后的岗位摘要卡显示正确（企业/职业类别/岗位名/审核状态/已添加人数），无重复的橙色提示条。
- 编辑模式（有 `id`）：无分段控件，"更多选项"默认展开，生效/停保时间字段可见可编辑。
- 拍照添加/批量导入两个入口功能不受影响（拍照添加走原有批量 OCR 流程；批量导入直接跳转）。
