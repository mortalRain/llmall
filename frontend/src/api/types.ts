/**
 * 后端 Long 类型统一序列化为字符串（llmall-common 的 JsonConfig），
 * 因此所有 id 都用 Id 表示，避免 JS 大整数精度丢失。
 */
export type Id = string

export interface PageResult<T> {
  total: number | string
  pages: number | string
  list: T[]
}

export interface UserProfile {
  token?: string | null
  accessToken?: string | null
  refreshToken?: string | null
  userId: Id
  username: string
  nickName: string
  icon?: string | null
  /** 余额，单位：分 */
  balance: number
  /** 积分 */
  credits: number
  level?: number | null
  needChangePayPassword?: boolean
  needChangeLoginPassword?: boolean
  needChangePassword?: boolean
}

export interface LoginResult extends UserProfile {
  accessToken: string
  refreshToken: string
}

export interface LoginPayload {
  username: string
  password: string
}

export interface FeedItem {
  feedId: Id
  blogId: Id
  authorId: Id
  shopId: Id | null
  title: string
  images: string | null
  content: string
  score: number
  liked: number
  comments: number
  status: number
  createTime: string
}

export interface FeedPage {
  items: FeedItem[]
  nextLi: Id | null
  nextCt: string | null
  nextCi: Id | null
  pullApplied?: boolean
  pullTruncated?: boolean
  nextAuthorId: Id | null
}

export interface FeedCursor {
  li?: Id
  ct?: string
  ci?: Id
  ai?: Id
}

export interface FeedQuery {
  size: number
  li?: Id
  ct?: string
  ci?: Id
  ai?: Id
}

export interface Shop {
  id: Id
  name: string
  typeId: Id
  typeName: string
  images: string | null
  area: string
  address: string
  x?: number
  y?: number
  /** 人均价格，单位：分（Long，后端返回字符串） */
  avgPrice: number | string
  sold: number
  comments: number
  /** 评分 x10 */
  score: number
  hotScore: number
  openHours: string
  status: number
  distance?: number | null
}

export interface ShopPageQuery {
  page: number
  size: number
  keyword?: string
  typeId?: Id
}

export interface Item {
  id: Id
  /** 实物商品可能没有门店（自营），门店套餐一定有 */
  shopId: Id | null
  type: number
  name: string
  /** 价格，单位：分 */
  price: number
  stock: number
  image: string | null
  category: string
  brand: string
  spec: string
  sold: number
  commentCount: number
  isAD?: boolean
  status: number
}

/**
 * 后端商品分页参数是 pageNo/pageSize（ItemPageQuery 继承 PageQuery），不是 page/size；
 * 价格区间单位是分（与 item.price 一致），由页面把用户输入的元换算成分再传。
 */
export interface ItemPageQuery {
  pageNo: number
  pageSize: number
  shopId?: Id
  key?: string
  category?: string
  brand?: string
  minPrice?: number
  maxPrice?: number
  type?: number
}

/** GET /items/categories：分类与上架商品数量（count 由 MySQL COUNT 返回，可能是字符串） */
export interface ItemCategory {
  category: string
  count: number | string
}

/** GET /carts 返回项：购物车条目 + 商品最新价格/状态/库存（CartVO） */
export interface CartItem {
  id: Id
  itemId: Id
  num: number
  name: string
  spec: string | null
  /** 加入购物车时的价格，单位：分 */
  price: number
  /** 商品当前价格，单位：分 */
  newPrice?: number | null
  /** 商品最新状态：1 正常 2 下架 3 删除 */
  status?: number
  stock?: number
  image: string | null
  createTime: string | null
}

/** POST /carts 请求体（CartFormDTO） */
export interface AddCartPayload {
  itemId: Id
  name: string
  spec?: string | null
  price: number
  image?: string | null
}

/**
 * GET /blogs/shop/{shopId} 与 GET /blogs/{id} 的返回项：
 * Blog 字段 + 后端批量回填的作者昵称/头像（BlogVO）。
 *
 * 【昵称/头像可能为空或已回退】后端在昵称为空时回退「同城用户{id}」；前端仍保留同样的兜底，
 * 以兼容旧后端或用户服务降级的情况。icon 为空时用昵称首字头像。
 */
export interface Blog {
  id: Id
  shopId: Id
  userId: Id
  title: string
  images: string | null
  content: string
  /** 评分 1~5 */
  score: number
  liked: number
  comments: number
  /** 0 待审核 1 已发布 2 已屏蔽 */
  status: number
  /** 作者昵称；为空时前端回退「同城用户{id}」 */
  nickname?: string | null
  /** 作者头像 URL；为空时前端用昵称首字 + 稳定背景色 */
  icon?: string | null
  createTime: string | null
  updateTime: string | null
}

/** POST /blogs 请求体（BlogFormDTO） */
export interface CreateBlogPayload {
  shopId: Id
  title: string
  content: string
  /** 评分 1~5 */
  score: number
  /** 图片 URL，多张以逗号分隔 */
  images?: string
}


export interface Voucher {
  id: Id
  shopId: Id | null
  title: string
  subTitle: string
  rules: string | null
  /** 售价，单位：分 */
  payValue: number | string
  /** 抵扣金额，单位：分 */
  actualValue: number | string
  type: number
  status: number
  beginTime: string | null
  endTime: string | null
  remainingStock: number | null
  inSeckillTime: boolean | null
  received: boolean
}

export interface UserVoucher {
  id: Id
  voucherId: Id
  title: string
  subTitle: string
  rules: string | null
  actualValue: number | string
  shopId: Id | null
  orderId: Id | null
  /** 1 未使用 2 已使用 3 已过期 */
  status: number
  receiveTime: string | null
  useTime: string | null
  expireTime: string | null
}

export interface Address {
  id: Id
  province: string
  city: string
  town: string
  mobile: string
  street: string
  contact: string
  isDefault: number
  notes: string | null
}

export interface AddressPayload {
  province: string
  city: string
  town: string
  mobile: string
  street: string
  contact: string
  isDefault: number
  notes?: string
}

export interface OrderDetail {
  id: Id
  orderId: Id
  itemId: Id
  name: string
  spec: string
  price: number
  num: number
  image: string | null
}

export interface Order {
  id: Id
  totalFee: number
  paymentType: number
  userId: Id
  status: number
  orderType: number | null
  shopId: Id | null
  voucherId: Id | null
  verifyCode: string | null
  verifyTime: string | null
  details: OrderDetail[]
  createTime: string | null
  payTime: string | null
  consignTime: string | null
  endTime: string | null
  closeTime: string | null
  commentTime: string | null
}

export interface OrderPageQuery {
  pageNo: number
  pageSize: number
  status?: number
}

export interface CreateOrderPayload {
  addressId: Id
  paymentType: number
  details: Array<{ itemId: Id; num: number }>
  userVoucherId?: Id
  clientToken: string
}

export interface VerifyOrderPayload {
  verifyCode: string
  shopId: Id
}

export interface PayApplyPayload {
  bizOrderNo: Id
  amount: number
  payChannelCode: string
  payType: number
  orderInfo: string
}

export interface PayPasswordPayload {
  pw: string
}

/**
 * 门店员工角色：核销三种角色都可；管理员工仅 OWNER/MANAGER；
 * 授予/撤销 OWNER 仅 OWNER。前端只用于展示与禁用按钮，服务端仍强制校验。
 */
export type StaffRole = 'OWNER' | 'MANAGER' | 'STAFF'

/** GET /shops/my/staff 的返回项：我任职的门店 + 我在该店的角色 */
export interface MyShopStaff {
  shopId: Id
  role: StaffRole
}

/** GET /shops/{shopId}/staff 的返回项 */
export interface ShopStaffMember {
  userId: Id
  role: StaffRole
  createTime: string | null
}

/** POST /shops/{shopId}/staff 请求体 */
export interface AddShopStaffPayload {
  userId: Id
  role: StaffRole
}
