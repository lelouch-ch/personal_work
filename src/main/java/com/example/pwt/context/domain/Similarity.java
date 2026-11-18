package com.example.pwt.context.domain;

import com.example.pwt.memory.domain.MemoryContent;
import java.util.HashSet;
import java.util.Set;

/**
 * 记忆正文的相似度：字符 2-gram 的 Jaccard 系数（设计文档 11.3 第 6 步“去除语义高度相似的候选”）。
 *
 * <p>为什么必须在服务层做：数据库里的 {@code normalized_hash} 只能判完全相同，
 * 同一件事换一种说法哈希就不同，两类内容会同时进上下文并各自占用预算；
 * 而 {@code MATCH} 分数只反映“与查询串的关系”，两条互不相关的记忆可能拿到相近的分数，
 * 用它两两比较并不能发现重复。所以相似度只能在拿到候选正文后自己算。
 *
 * <p>为什么用字符 2-gram：MVP 不引入分词器与向量依赖（设计文档 19.1 明确 Embedding 暂不使用），
 * 中文按相邻两字切分对短句已足够稳定，且与 InnoDB ngram 索引口径一致。
 *
 * @author pwt
 */
public final class Similarity {

    /**  gram 长度，与 {@code ngram_token_size = 2} 对齐。 */
    private static final int GRAM_SIZE = 2;

    /**
     * 判定为重复的阈值。
     *
     * <p>刻意取得高：去重误判会直接让一条记忆从上下文里消失，Agent 无从得知它存在过，
     * 代价是漏一条补充信息。阈值取高只留“几乎同一句话”的合并，同一主题的不同事实都保留。
     */
    public static final double DUPLICATE_THRESHOLD = 0.85D;

    private Similarity() {
    }

    /**
     * 计算两段文本的相似度。
     *
     * @param left  文本一
     * @param right 文本二
     * @return 0~1，任一为空或两者都不足 2 字时返回 0，交由调用方按“不相似”处理
     */
    public static double jaccard(String left, String right) {
        Set<String> leftGrams = grams(left);
        Set<String> rightGrams = grams(right);
        if (leftGrams.isEmpty() || rightGrams.isEmpty()) {
            return 0.0D;
        }
        Set<String> intersection = new HashSet<>(leftGrams);
        intersection.retainAll(rightGrams);
        Set<String> union = new HashSet<>(leftGrams);
        union.addAll(rightGrams);
        return (double) intersection.size() / union.size();
    }

    /**
     * 判断两段文本是否应视为重复。
     *
     * @param left  文本一
     * @param right 文本二
     * @return true 表示重复
     */
    public static boolean duplicate(String left, String right) {
        return jaccard(left, right) >= DUPLICATE_THRESHOLD;
    }

    private static Set<String> grams(String text) {
        String normalized = MemoryContent.normalize(text);
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + GRAM_SIZE <= normalized.length(); i++) {
            grams.add(normalized.substring(i, i + GRAM_SIZE));
        }
        return grams;
    }
}
