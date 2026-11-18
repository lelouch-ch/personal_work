package com.example.pwt.context;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Context Builder 参数（设计文档 11.4、11.5）。
 *
 * <p>候选数与各段配额放配置而不是写死：这两处是排序质量的主要旋钮，
 * 换模型、换语料或用户反馈变差时都要调，写死就得重新发版。
 *
 * @author pwt
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "pwt.context")
public class ContextProperties {

    /** 默认 Token 预算，与 9.4 {@code build_context} 的 Schema 默认值一致。 */
    private int defaultTokenBudget = 4000;

    /** 允许的 Token 预算下限。 */
    private int minTokenBudget = 500;

    /** 允许的 Token 预算上限，防止一次请求把模型窗口填满。 */
    private int maxTokenBudget = 20000;

    /** 单次召回的候选上限：候选越多，去重与打分的比较次数按平方增长。 */
    private int maxCandidates = 60;

    /**
     * 各记忆类型在“相关记忆”段内的配额，按类型顺序生效（11.5 第 3～6 优先级）。
     *
     * <p>配额存在的意义是防止一类记忆吃满预算：经验类通常条数最多、句子最长，
     * 纯按分数填充会让偏好与决策一条都进不来，而这两类恰恰是用户最在意的稳定信号。
     */
    private List<TypeQuota> typeQuotas = List.of(
        new TypeQuota("fact", 4),
        new TypeQuota("preference", 3),
        new TypeQuota("experience", 4),
        new TypeQuota("decision", 3),
        new TypeQuota("goal", 2));

    /**
     * 某类记忆的条数配额。
     *
     * @param type  记忆类型存储值
     * @param limit 最多注入条数
     */
    public record TypeQuota(String type, int limit) {
    }
}
