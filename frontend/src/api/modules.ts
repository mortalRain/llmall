import { get, post, put } from '@/api/http'
import type {
  Address,
  AddressPayload,
  CreateOrderPayload,
  FeedPage,
  FeedQuery,
  Id,
  Item,
  ItemPageQuery,
  LoginPayload,
  LoginResult,
  Order,
  OrderPageQuery,
  PageResult,
  PayApplyPayload,
  Shop,
  ShopPageQuery,
  UserProfile,
  UserVoucher,
  VerifyOrderPayload,
  Voucher,
} from '@/api/types'

/** 认证与当前用户 */
export const authApi = {
  login(payload: LoginPayload): Promise<LoginResult> {
    return post<LoginResult>('/users/login', payload, { skipAuth: true, skipAuthRefresh: true })
  },
  me(): Promise<UserProfile> {
    return get<UserProfile>('/users/me')
  },
  logout(): Promise<void> {
    return post<void>('/users/logout')
  },
}

/** 关注动态流（v2：推拉结合 + 复合游标） */
export const feedApi = {
  getFeed(params: FeedQuery): Promise<FeedPage> {
    return get<FeedPage>('/follows/feed/v2', { params })
  },
}

/** 门店：后端分页参数是 page/size（见 ShopController#pageQuery），不是 pageNo/pageSize */
export const shopApi = {
  page(params: ShopPageQuery): Promise<PageResult<Shop>> {
    return get<PageResult<Shop>>('/shops/page', { params })
  },
  hot(limit = 6): Promise<Shop[]> {
    return get<Shop[]>('/shops/hot', { params: { limit } })
  },
  detail(id: Id): Promise<Shop> {
    return get<Shop>(`/shops/${id}`)
  },
  /**
   * 我任职的门店 id 列表（后端 Long 序列化为字符串）。
   *
   * 【用途】订单页只对"本店员工"展示核销入口。它是展示开关，不是权限边界：
   * 真正的拦截在交易域核销接口（非员工返回 403），前端即使拿到伪造列表也核销不了。
   */
  myStaffShops(): Promise<Id[]> {
    return get<Id[]>('/shops/my/staff')
  },
}

/** 商品：门店套餐优先走 /items/shop/{shopId}，同时保留分页入口 */
export const itemApi = {
  byShop(shopId: Id): Promise<Item[]> {
    return get<Item[]>(`/items/shop/${shopId}`)
  },
  byId(id: Id): Promise<Item> {
    return get<Item>(`/items/${id}`)
  },
  page(params: ItemPageQuery): Promise<PageResult<Item>> {
    return get<PageResult<Item>>('/items/page', { params })
  },
}

/** 优惠券：列表匿名可看，领取 / 我的券必须登录 */
export const voucherApi = {
  list(shopId?: Id): Promise<Voucher[]> {
    return get<Voucher[]>('/vouchers/list', { params: shopId ? { shopId } : undefined })
  },
  my(status?: number): Promise<UserVoucher[]> {
    return get<UserVoucher[]>('/vouchers/my', { params: status ? { status } : undefined })
  },
  receive(id: Id, pw?: string): Promise<Id> {
    return post<Id>(`/vouchers/${id}/receive`, pw ? { pw } : undefined)
  },
}

/** 收货地址 */
export const addressApi = {
  list(): Promise<Address[]> {
    return get<Address[]>('/addresses')
  },
  create(payload: AddressPayload): Promise<Id> {
    return post<Id>('/addresses', payload)
  },
}

/** 订单 */
export const orderApi = {
  page(params: OrderPageQuery): Promise<PageResult<Order>> {
    return get<PageResult<Order>>('/orders', { params })
  },
  detail(id: Id): Promise<Order> {
    return get<Order>(`/orders/${id}`)
  },
  create(payload: CreateOrderPayload): Promise<Id> {
    return post<Id>('/orders', payload)
  },
  cancel(id: Id): Promise<void> {
    return put<void>(`/orders/${id}/cancel`, undefined)
  },
  verify(payload: VerifyOrderPayload): Promise<Id> {
    return post<Id>('/orders/verify', payload)
  },
}

/**
 * 支付：
 * - 订单侧 paymentType=3 表示余额支付；
 * - 支付侧 PayType.BALANCE=5（两套枚举值不同，写 3 会被支付服务拒绝）；
 * - bizOrderNo 是 19 位雪花 id，必须原样传字符串。
 */
export const payApi = {
  apply(payload: PayApplyPayload): Promise<Id> {
    return post<Id>('/pay-orders', payload)
  },
  payByBalance(payOrderId: Id, pw: string): Promise<void> {
    return post<void>(`/pay-orders/${payOrderId}`, { pw })
  },
}
