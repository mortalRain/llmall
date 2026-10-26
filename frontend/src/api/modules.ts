import { del, get, post, put } from '@/api/http'
import type {
  AddShopStaffPayload,
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
  MyShopStaff,
  Order,
  OrderPageQuery,
  PageResult,
  PayApplyPayload,
  Shop,
  ShopPageQuery,
  ShopStaffMember,
  StaffRole,
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
   * 我任职的门店与角色（后端 Long 序列化为字符串）。
   *
   * 【用途】核销台入口对任意角色开放；员工管理入口只对 OWNER/MANAGER 开放。
   * 它是展示开关，不是权限边界：核销/管理都会在服务端再判一次。
   */
  myStaffShops(): Promise<MyShopStaff[]> {
    return get<MyShopStaff[]>('/shops/my/staff')
  },
  /** 门店员工管理：列表 / 新增 / 改角色 / 删除（服务端仅 OWNER/MANAGER 放行） */
  listStaff(shopId: Id): Promise<ShopStaffMember[]> {
    return get<ShopStaffMember[]>(`/shops/${shopId}/staff`)
  },
  addStaff(shopId: Id, payload: AddShopStaffPayload): Promise<void> {
    return post<void>(`/shops/${shopId}/staff`, payload)
  },
  updateStaffRole(shopId: Id, userId: Id, role: StaffRole): Promise<void> {
    return put<void>(`/shops/${shopId}/staff/${userId}`, { role })
  },
  removeStaff(shopId: Id, userId: Id): Promise<void> {
    return del<void>(`/shops/${shopId}/staff/${userId}`)
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
