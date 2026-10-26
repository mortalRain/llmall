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
  shopId: Id
  type: number
  name: string
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

export interface ItemPageQuery {
  pageNo: number
  pageSize: number
  shopId?: Id
  key?: string
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
