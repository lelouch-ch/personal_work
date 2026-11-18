package com.example.pwt.memory.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 冲突检测（设计文档 6.5「同一事实存在相反值……时标记冲突」）。
 *
 * <p>MVP 用可解释的确定性规则，不依赖模型：近似召回由全文检索完成，本类只判断
 * “召回到的候选是否与新记忆互相矛盾”。规则可单测、可复现，误判交给人工审核兜底
 * （6.3 第 6 条：冲突不直接覆盖）。
 *
 * <p>两条规则：
 * <ol>
 *   <li>相反极性：去掉否定词后完全相同，且只有一方带否定词，判为相反。
 *       例：“偏好先给结论” 与 “偏好不要先给结论”。</li>
 *   <li>同题异值：共享足够长的前缀，尾部差异段都含数字且不相等，判为同一事实的不同取值。
 *       例：“框架是 spring boot 3.1” 与 “框架是 spring boot 3.2”。</li>
 * </ol>
 *
 * <p>已知局限：不处理跨句否定、双重否定与英文 not 的作用域，这类情况会漏判而不是误判，
 * 漏判的后果是两条记忆并存，由人工审核发现。
 *
 * @author pwt
 */
public final class ConflictDetector {

    /** 否定词，按长度倒序匹配，避免“不要”被“要”提前判定。 */
    private static final String[] NEGATIONS = {"禁止", "严禁", "不要", "不能", "不可", "不必", "不是", "不", "没", "无", "非", "别", "莫"};

    /** 规则二要求的最小公共前缀长度，过短说明两句其实在讲不同的事。 */
    private static final int MIN_COMMON_PREFIX = 4;

    private ConflictDetector() {
    }

    /**
     * 在候选列表中找出与新内容冲突的记忆。
     *
     * @param newContent      新记忆内容
     * @param existingContents 已召回的既有记忆内容，顺序即返回索引基准
     * @return 冲突结果，无冲突时为空列表
     */
    public static List<Conflict> detect(String newContent, List<String> existingContents) {
        List<Conflict> conflicts = new ArrayList<>();
        String left = MemoryContent.normalize(newContent);
        if (left.isEmpty() || existingContents == null) {
            return conflicts;
        }
        for (int index = 0; index < existingContents.size(); index++) {
            String right = MemoryContent.normalize(existingContents.get(index));
            if (right.isEmpty() || left.equals(right)) {
                // 完全相同属于重复而非冲突，由 normalizedHash 去重处理
                continue;
            }
            if (oppositePolarity(left, right)) {
                conflicts.add(new Conflict(index, existingContents.get(index),
                    "与既有记忆极性相反：" + existingContents.get(index)));
            } else if (sameSubjectDifferentValue(left, right)) {
                conflicts.add(new Conflict(index, existingContents.get(index),
                    "与既有记忆指向同一事实但取值不同：" + existingContents.get(index)));
            }
        }
        return conflicts;
    }

    /**
     * 规则一：相反极性。
     *
     * @param left  已规范化内容
     * @param right 已规范化内容
     * @return true 表示相反
     */
    static boolean oppositePolarity(String left, String right) {
        boolean leftNegated = containsNegation(left);
        boolean rightNegated = containsNegation(right);
        if (leftNegated == rightNegated) {
            return false;
        }
        return stripNegations(left).equals(stripNegations(right));
    }

    /**
     * 规则二：同题异值。
     *
     * @param left  已规范化内容
     * @param right 已规范化内容
     * @return true 表示冲突
     */
    static boolean sameSubjectDifferentValue(String left, String right) {
        int prefix = commonPrefixLength(left, right);
        if (prefix < MIN_COMMON_PREFIX) {
            return false;
        }
        String leftTail = left.substring(prefix);
        String rightTail = right.substring(prefix);
        if (leftTail.isEmpty() || rightTail.isEmpty() || leftTail.equals(rightTail)) {
            return false;
        }
        return hasDigit(leftTail) && hasDigit(rightTail);
    }

    private static boolean containsNegation(String text) {
        for (String negation : NEGATIONS) {
            if (text.contains(negation)) {
                return true;
            }
        }
        return false;
    }

    private static String stripNegations(String text) {
        String result = text;
        for (String negation : NEGATIONS) {
            result = result.replace(negation, "");
        }
        return result.replace(" ", "");
    }

    private static int commonPrefixLength(String left, String right) {
        int max = Math.min(left.length(), right.length());
        int index = 0;
        while (index < max && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }

    private static boolean hasDigit(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (Character.isDigit(text.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一条冲突结果。
     *
     * @param index           在输入列表中的下标
     * @param existingContent 被冲突的既有内容
     * @param reason          冲突原因，写入审核留痕
     * @author pwt
     */
    public record Conflict(int index, String existingContent, String reason) {
    }
}
