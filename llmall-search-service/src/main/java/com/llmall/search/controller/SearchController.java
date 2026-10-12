package com.llmall.search.controller;

import com.llmall.api.dto.ShopDTO;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.utils.UserContext;
import com.llmall.search.domain.po.SearchHistory;
import com.llmall.search.domain.vo.HotWordVO;
import com.llmall.search.domain.vo.SearchEngineStatusVO;
import com.llmall.search.service.ISearchService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "搜索接口")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final ISearchService searchService;

    /**
     * 运维接口总开关（llmall.maintenance.enabled，默认 true）。
     *
     * 全量重建搜索视图是典型的"重活"：执行期间会大量读写 Redis 并扫描商品表，
     * 一旦怀疑线上抖动与它有关，第一反应就是先把它停掉再排查。
     * 做成配置开关（而不是删代码 / 改网关路由）是为了让这个动作只需改一行配置并重启，
     * 不需要发版，也不会误伤同一控制器下的正常搜索接口。
     */
    @Value("${llmall.maintenance.enabled:false}")
    private boolean maintenanceEnabled;

    private void checkMaintenanceEnabled() {
        if (!maintenanceEnabled) {
            throw new ForbiddenException("运维接口已关闭");
        }
    }

    @Operation(summary = "门店检索（关键词 + 类型，按热度排序）")
    @GetMapping
    public PageDTO<ShopDTO> search(@RequestParam(value = "keyword", required = false) String keyword,
                                   @RequestParam(value = "typeId", required = false) Long typeId,
                                   @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                                   @RequestParam(value = "size", required = false, defaultValue = "10") Integer size) {
        // 未登录也可搜索（网关对该路径放行），登录用户会顺带记录搜索历史
        return searchService.search(keyword, typeId, page, size, UserContext.getUser());
    }

    @Operation(summary = "搜索热词 Top N（Redis ZSet）")
    @GetMapping("/hot-words")
    public List<HotWordVO> hotWords(@RequestParam(value = "limit", required = false, defaultValue = "10") Integer limit) {
        return searchService.hotWords(limit);
    }

    @Operation(summary = "搜索建议（前缀匹配）")
    @GetMapping("/suggest")
    public List<String> suggest(@RequestParam("prefix") String prefix,
                                @RequestParam(value = "limit", required = false, defaultValue = "10") Integer limit) {
        return searchService.suggest(prefix, limit);
    }

    @Operation(summary = "我的搜索历史")
    @GetMapping("/history")
    public List<SearchHistory> myHistory(@RequestParam(value = "limit", required = false, defaultValue = "10") Integer limit) {
        return searchService.myHistory(UserContext.getUser(), limit);
    }

    @Operation(summary = "清空我的搜索历史")
    @DeleteMapping("/history")
    public void clearHistory() {
        searchService.clearHistory(UserContext.getUser());
    }

    @Operation(summary = "全量重建搜索视图（首次部署 / 视图损坏时使用）")
    @PostMapping("/view/rebuild")
    public int rebuildView() {
        checkMaintenanceEnabled();
        return searchService.rebuildSearchView();
    }

    @Operation(summary = "门店检索 · 附近优先（给了 x/y 按距离升序；仅 ES 引擎支持，MySQL 回落时退化为普通检索）")
    @GetMapping("/nearby")
    public PageDTO<ShopDTO> searchNearby(@RequestParam(value = "keyword", required = false) String keyword,
                                         @RequestParam(value = "typeId", required = false) Long typeId,
                                         @RequestParam("x") Double x,
                                         @RequestParam("y") Double y,
                                         @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                                         @RequestParam(value = "size", required = false, defaultValue = "10") Integer size) {
        return searchService.searchNearby(keyword, typeId, page, size, x, y, UserContext.getUser());
    }

    @Operation(summary = "ES 索引全量重建（把 MySQL 读模型灌进 ES）")
    @PostMapping("/index/rebuild")
    public int rebuildIndex() {
        checkMaintenanceEnabled();
        return searchService.rebuildEsIndex();
    }

    @Operation(summary = "检索引擎状态与对账（engine / ES 连通性 / 读模型条数 vs 索引文档数）")
    @GetMapping("/engine")
    public SearchEngineStatusVO engineStatus() {
        return searchService.engineStatus();
    }
}
