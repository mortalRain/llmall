# 同城生活电商平台 · 演示前端

一个**单文件、零依赖、零构建**的前端演示台，用来展示后端三个亮点：
**统一鉴权与身份透传**、**跨域最终一致性**、**评价 → 门店热度 → 榜单排序**。

## 配色与主题（高级浅蓝 / Steel Blue）

配色集中定义在 **`theme.css` 的 `:root`**（CSS 变量）；`index.html` 内的 `<style>` 是它的内联副本，为了"单文件双击即用"。

| 变量 | 值 | 用途 |
|---|---|---|
| `--brand-500` / `--brand-600` | `#6695C4` / `#4E7BA9` | 主色：按钮渐变、强调 |
| `--brand-700` / `--brand-800` | `#3D6489` / `#2E4C6B` | 深色文字（导航激活、统计数字） |
| `--brand-050` / `--brand-100` | `#F3F8FC` / `#E7F0F8` | 浅底（悬停、选中态） |
| `--gold-500` | `#C8912F` | **全站唯一暖色**：只用于评分与热度 |
| `--ink` / `--ink-2` / `--muted` | `#16202E` / `#3A4A5E` / `#6B7C90` | 带蓝调的中性灰，避免灰得发脏 |
| `--bg` / `--line` | `#F5F9FC` / `#E4EDF5` | 页面底色与分隔线 |

**改配色**：改 `theme.css` 的 `:root`，再用下面命令重新注入 `index.html`（保持单文件）：

```powershell
$enc = New-Object System.Text.UTF8Encoding($false)
$fe = 'D:\dsh\frontend\index.html'
$html = [System.IO.File]::ReadAllText($fe, $enc)
$block = "<style>`r`n" + [System.IO.File]::ReadAllText('D:\dsh\frontend\theme.css', $enc) + "</style>"
[System.IO.File]::WriteAllText($fe, [regex]::Replace($html, '(?s)<style>.*?</style>', { param($m) $block }), $enc)
```
## 打开方式（三选一）

| 方式 | 做法 | 说明 |
|---|---|---|
| **最简单** | 直接双击 `index.html` | 浏览器以 `file://` 打开；网关已开 CORS，可直接调接口 |
| 走 nginx | 把本目录丢到 nginx 的 html 下（或用 `frontend-legacy` 里的 nginx.exe） | 与课程前端同一套 nginx 即可 |
| 本地静态服务 | 任意静态服务器指向本目录 | 例如 IDEA 的 "Open in Browser" |

## 前置：后端要跑起来

最少需要这几个服务（推荐先在 IDEA 里只起这些，省内存）：

```
gateway(8080) + llmall-shop-service(8087) + llmall-user-service(8084) + llmall-content-service(8089)
（搜索功能还需要 llmall-search-service(8093)）
中间件：Nacos + MySQL + Redis + RabbitMQ
```

前端默认访问 `http://localhost:8080`（网关）。**如果后端不在这个地址**，点右上角 **「⚙ 接口」** 改成你的地址即可（会记在浏览器 localStorage）。

## 能看到什么（对应三个亮点）

| 页面 | 演示的内容 | 对应亮点 |
|---|---|---|
| 首页 | 门店卡片（评分/评价数/人均/热度徽章）、按热度排序、门店类型筛选 | 数据所有权 + 热度排序读路径 |
| 右侧「门店热度榜」 | Redis ZSet 榜单（1/2/3 名有奖牌配色） | 亮点 C |
| 搜索 | 走 `llmall-search-service`（关键词匹配 + 热度加权排序） | 亮点 C 最后一跳 |
| 附近门店 | Redis GEO 半径查询，卡片上显示距离 | 技术点 |
| 门店详情 | 门店统计（销量/评价数/热度分）+ 套餐（商品域）+ 评价列表 | 跨域聚合 |
| **发布评价** | 提交后 1~3 秒，右侧榜单**自动刷新**并提示 "🔥 榜单已更新" | **亮点 C 的可视化** |
| 登录 | 用户名密码 / 手机验证码双通道，拿 JWT 双令牌 | 亮点 A |
| 我的 | `/users/me`（身份来自网关透传的请求头）+ 积分流水 | 亮点 A + 亮点 B（下单支付后异步发积分） |

## 两个常见问题

**1）「附近门店」是空的？**
Redis GEO 索引需要初始化一次（登录后调用，需带令牌）：
```
POST http://localhost:8080/shops/cache/geo/rebuild
```
也可以在浏览器登录后，用控制台执行：
```js
fetch('http://localhost:8080/shops/cache/geo/rebuild', {
  method:'POST',
  headers:{ authorization: localStorage.getItem('tc_token') }
}).then(r=>r.json()).then(console.log)
```
（当前中心点写死在杭州 `120.1519, 30.3100`，与迁移进来的门店坐标一致；换城市改 `renderNearby()` 里的参数即可。）

**2）页面提示"连接不上后端"？**
说明网关没起或地址不对：先确认 `GatewayApplication` 已启动（8080），再点右上角「⚙ 接口」核对地址。

## 与课程旧前端的关系

`../frontend-legacy/` 是课程自带的 nginx + 静态页（已把课程品牌字样全部替换掉），可作为备用；
它的问题：① 页面是"商城"形态，**演示不了门店/评价/热度榜**；② 它的搜索页调 `/search/list`，与当前 llmall-search-service 的 `/search?keyword=` 不兼容。

本前端是为此项目重写的：**贴合「同城生活电商」主题**，并且**没有课程品牌痕迹**。
