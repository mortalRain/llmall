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
