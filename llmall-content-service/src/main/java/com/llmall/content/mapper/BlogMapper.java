package com.llmall.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.content.domain.po.Blog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface BlogMapper extends BaseMapper<Blog> {

    /**
     * 补拉候选：从一批关注作者的笔记里取最近的一批，只回 id、作者、发布时间三列。
     *
     * 【只回三列】调用方先用这些 id 与收件箱结果归并、去重、截页，再对最终入选的 id 发一次批量查询取详情；
     * 这里若带正文，等于把根本没进任何一页的笔记也整行读出来。
     *
     * 【大V 判定与取帖合并成同一条】子查询按 follow_user_id 分组数粉丝、HAVING 过滤出超阈值作者，
     * 再与 blog 直接 JOIN，判定与取帖不再各占一条 SQL。数粉丝走 idx_followed(follow_user_id)，
     * 取帖走 idx_user(user_id, create_time)。
     *
     * 【游标】ct 与 ci 是合并时间轴上的位置，按二元组严格小于游标取值，所以同一秒内发布的笔记
     * 既不会漏行也不会重行；只给 limit 不给游标时取最新的一批。
     *
     * 【为什么钉索引】不写 hint 时优化器在小数据量下会放弃 idx_user 直接全表扫，
     * 随数据量增长才可能自己改回索引访问；读路径不能依赖优化器的选择随数据量漂移，
     * 因此显式钉住 idx_user，使访问路径与数据量无关。
     *
     * 【本提示的失效条件】命中任一条就该重新评估并撤掉它：
     * 一、单作者已发布笔记量达到十万级而 status = 1 占比很低时，(user_id, create_time) 无法过滤状态，
     * 应改建 (user_id, status, create_time) 后改为钉新索引，而不是继续钉这条；
     * 二、blog 表按作者或时间分区后，索引名与可用性随分区变化，钉死在旧索引名会让 SQL 直接报错；
     * 三、idx_user 被改名、删除或被新索引取代时，FORCE INDEX 会以错误暴露问题，此时应删掉提示而非改索引名；
     * 四、升级到能稳定选中该索引的版本后，可在压测确认计划不变的前提下移除提示。
     *
     * 【作用域】authors 由调用方按批切好，批大小决定这条 SQL 的 IN 参数长度与批数，
     * 因此"每请求几条 SQL"只随关注作者数变化，不随页大小变化。
     */
    @Select("<script>"
            + "SELECT b.id, b.user_id, b.create_time FROM `blog` b FORCE INDEX (idx_user) "
            + "INNER JOIN (SELECT `follow_user_id` AS aid FROM `follow` "
            + "  WHERE `follow_user_id` IN "
            + "  <foreach collection='authors' item='a' open='(' separator=',' close=')'>#{a}</foreach> "
            + "  GROUP BY `follow_user_id` HAVING COUNT(*) &gt; #{threshold}) bigv "
            + "  ON b.user_id = bigv.aid "
            + "WHERE b.status = 1 "
            + "<if test='ct != null'>"
            + "AND (b.create_time &lt; #{ct} OR (b.create_time = #{ct} AND b.id &lt; #{ci})) "
            + "</if>"
            + "ORDER BY b.create_time DESC, b.id DESC LIMIT #{limit}"
            + "</script>")
    List<Blog> selectBigvRecentBlogs(@Param("authors") List<Long> authors,
                                     @Param("threshold") int threshold,
                                     @Param("ct") LocalDateTime ct,
                                     @Param("ci") Long ci,
                                     @Param("limit") int limit);
}
