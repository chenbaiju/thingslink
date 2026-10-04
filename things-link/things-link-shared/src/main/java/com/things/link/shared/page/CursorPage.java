package com.things.link.shared.page;

import java.util.List;
import java.util.function.Function;

/**
 * 游标分页结果。
 *
 * <p>架构文档 11.1：<b>列表统一使用游标分页，海量日志和时序点禁止深度 offset。</b>
 *
 * <h2>为什么不用 offset 分页</h2>
 * <ul>
 *   <li><b>性能</b>：{@code OFFSET 100000} 要求数据库扫描并丢弃前十万行。设备消息
 *       日志和时序点动辄千万级，翻到后面的页会直接拖垮数据库</li>
 *   <li><b>正确性</b>：分页期间有新数据写入时，offset 会导致<b>漏读和重复读</b>。
 *       设备消息是持续高频写入的，这不是边缘情况而是常态</li>
 * </ul>
 *
 * <p>游标是不透明字符串，客户端只负责原样回传，不应解析。这样服务端可以在不破坏
 * 兼容性的前提下更换游标的内部结构。
 *
 * @param items      本页数据
 * @param nextCursor 下一页游标。为 null 表示已到末页
 * @param hasMore    是否还有下一页
 * @param <T>        元素类型
 */
public record CursorPage<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore) {

    /**
     * 紧凑构造器：items 规整为不可变列表。
     */
    public CursorPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 构造末页结果。
     *
     * @param items 本页数据
     * @param <T>   元素类型
     * @return 没有下一页的分页结果
     */
    public static <T> CursorPage<T> last(List<T> items) {
        return new CursorPage<>(items, null, false);
    }

    /**
     * 构造有下一页的结果。
     *
     * @param items      本页数据
     * @param nextCursor 下一页游标
     * @param <T>        元素类型
     * @return 分页结果
     */
    public static <T> CursorPage<T> of(List<T> items, String nextCursor) {
        return new CursorPage<>(items, nextCursor, true);
    }

    /**
     * 映射元素类型，通常用于领域对象转 DTO。
     *
     * <p>游标不变 —— 它编码的是数据库层面的位置，与展现层类型无关。
     *
     * @param mapper 元素转换函数
     * @param <R>    目标类型
     * @return 转换后的分页结果
     */
    public <R> CursorPage<R> map(Function<? super T, ? extends R> mapper) {
        return new CursorPage<>(items.stream().map(mapper).map(r -> (R) r).toList(), nextCursor, hasMore);
    }

}
