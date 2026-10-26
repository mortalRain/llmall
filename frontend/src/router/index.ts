import { createRouter, createWebHistory } from 'vue-router'
import { getAccessToken } from '@/utils/session'
import AppLayout from '@/layouts/AppLayout.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/Login.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: AppLayout,
      redirect: '/feed',
      children: [
        {
          path: 'feed',
          name: 'feed',
          component: () => import('@/views/Feed.vue'),
          meta: { title: '关注动态' },
        },
        {
          path: 'mall',
          name: 'mall',
          component: () => import('@/views/Mall.vue'),
          meta: { title: '商城' },
        },
        {
          path: 'item/:id',
          name: 'item-detail',
          component: () => import('@/views/ItemDetail.vue'),
          meta: { title: '商品详情' },
        },
        {
          path: 'cart',
          name: 'cart',
          component: () => import('@/views/Cart.vue'),
          meta: { title: '购物车' },
        },
        {
          path: 'favorites',
          name: 'favorites',
          component: () => import('@/views/Favorites.vue'),
          meta: { title: '我的收藏' },
        },
        {
          path: 'shops',
          name: 'shops',
          component: () => import('@/views/Shops.vue'),
          meta: { title: '逛门店' },
        },
        {
          path: 'shops/:id',
          name: 'shop-detail',
          component: () => import('@/views/ShopDetail.vue'),
          meta: { title: '门店详情' },
        },
        {
          // 写评价独立成页：评价内容是 10~500 字的长文本，弹窗在 375px 下会被键盘/视口挤到不可用。
          // 路由守卫已要求登录；进入后仍会校验登录态，未登录时回跳登录并带回跳地址。
          path: 'shops/:id/review',
          name: 'shop-review',
          component: () => import('@/views/ReviewCreate.vue'),
          meta: { title: '写评价' },
        },
        {
          path: 'checkout',
          name: 'checkout',
          component: () => import('@/views/Checkout.vue'),
          meta: { title: '确认订单' },
        },
        {
          path: 'orders',
          name: 'orders',
          component: () => import('@/views/Orders.vue'),
          meta: { title: '我的订单' },
        },
        {
          // 员工核销台：路由本身只要求登录；"是否员工"由页面用 /shops/my/staff 判断，
          // 真正的核销权限仍由交易域核销接口强制（非本店员工 403）。
          path: 'staff/verify',
          name: 'staff-verify',
          component: () => import('@/views/StaffVerify.vue'),
          meta: { title: '核销台' },
        },
        {
          // 员工管理：路由只要求登录，页面用 /shops/my/staff 判断是否 OWNER/MANAGER；
          // 真正的增删改权限由 shop 服务端强制（STAFF 403、店长不能授 OWNER、最后 OWNER 不可删）。
          path: 'staff/manage',
          name: 'staff-manage',
          component: () => import('@/views/StaffManage.vue'),
          meta: { title: '员工管理' },
        },
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/views/Profile.vue'),
          meta: { title: '我的' },
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/feed',
    },
  ],
  scrollBehavior() {
    return { top: 0, behavior: 'smooth' }
  },
})

router.beforeEach((to) => {
  const loggedIn = Boolean(getAccessToken())

  if (to.meta.public) {
    if (loggedIn && to.name === 'login') {
      return { name: 'feed' }
    }
    return true
  }

  if (!loggedIn) {
    return {
      name: 'login',
      query: { redirect: to.fullPath },
    }
  }

  return true
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : '同城生活'
  document.title = `${title} · LLMall`
})

export default router
