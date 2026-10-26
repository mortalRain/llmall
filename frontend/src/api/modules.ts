import { del, get, post, put } from '@/api/http'
import type {
  AddCartPayload,
  AddShopStaffPayload,
  Address,
  AddressPayload,
  Blog,
  CartItem,
  CreateBlogPayload,
  CreateOrderPayload,
  FeedPage,
  FeedQuery,
  Id,
  Item,
  ItemCategory,
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
  /** 批量查门店（商城商品卡片回填店铺名用）；ids 逗号分隔由 Spring 转成 List<Long> */
  batch(ids: Id[]): Promise<Shop[]> {
    if (!ids.length) return Promise.resolve([])
    return get<Shop[]>('/shops/batch', { params: { ids: ids.join(',') } })
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

/**
 * 门店收藏：四个接口都是多段路径，网关不会按只读白名单放行，未登录返回 401。
 * 收藏/取消都是幂等的，前端可以安全重试。
 */
export const favoriteApi = {
  status(shopId: Id): Promise<boolean> {
    return get<boolean>(`/shops/${shopId}/favorite`)
  },
  add(shopId: Id): Promise<void> {
    return post<void>(`/shops/${shopId}/favorite`)
  },
  remove(shopId: Id): Promise<void> {
    return del<void>(`/shops/${shopId}/favorite`)
  },
  /** 我的收藏：返回门店 DTO 列表（后端已按收藏时间倒序） */
  my(): Promise<Shop[]> {
    return get<Shop[]>('/shops/my/favorites')
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
  /** 批量查商品（结算页按购物车行取最新价格/库存）；最多 100 个，由后端兜底 */
  byIds(ids: Id[]): Promise<Item[]> {
    if (!ids.length) return Promise.resolve([])
    return get<Item[]>('/items', { params: { ids: ids.join(',') } })
  },
  page(params: ItemPageQuery): Promise<PageResult<Item>> {
    return get<PageResult<Item>>('/items/page', { params })
  },
  categories(type?: number): Promise<ItemCategory[]> {
    return get<ItemCategory[]>('/items/categories', {
      params: type ? { type } : undefined,
    })
  },
}

/** 购物车：GET/POST/PUT/DELETE 全部要求登录 */
export const cartApi = {
  list(): Promise<CartItem[]> {
    return get<CartItem[]>('/carts')
  },
  add(payload: AddCartPayload): Promise<void> {
    return post<void>('/carts', payload)
  },
  /** 只改数量；归属校验在服务端按当前登录人做，前端传的 userId 不参与 UPDATE */
  updateNum(id: Id, num: number): Promise<void> {
    return put<void>('/carts', { id, num })
  },
  remove(id: Id): Promise<void> {
    return del<void>(`/carts/${id}`)
  },
}

/** 门店评价：公开读接口，返回 PageResult（total/pages/list） */
export const blogApi = {
  byShop(shopId: Id, page = 1, size = 5): Promise<PageResult<Blog>> {
    return get<PageResult<Blog>>(`/blogs/shop/${shopId}`, { params: { page, size } })
  },
  detail(id: Id): Promise<Blog> {
    return get<Blog>(`/blogs/${id}`)
  },
  /** 发布评价：后端返回新评价 id（Long 已序列化为字符串） */
  create(payload: CreateBlogPayload): Promise<Id> {
    return post<Id>('/blogs', payload)
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
