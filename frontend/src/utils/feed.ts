import type { FeedPage } from '@/api/types'

/** 新后端明确返回是否有下一页；旧后端保留长度判据。 */
export function feedHasMore(page: FeedPage, pageSize: number): boolean {
  if (typeof page.hasMore === 'boolean') {
    return page.hasMore && Boolean(page.nextCt && page.nextCi)
  }
  return Array.isArray(page.items) && page.items.length >= pageSize &&
    Boolean(page.nextLi || page.nextCt || page.nextCi || page.nextAuthorId)
}
