package com.example.pwt.context.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 上下文检索串：把任务描述解析成可安全送入 {@code MATCH ... AGAINST(... IN BOOLEAN MODE)} 的词表
 * （设计文档 11.3 第 3 步、8.4 第 15 条）。
 *
 * <p>只有 Context Builder 自己组装的串才允许用布尔模式：组装前必须剔除运算符字符，
 * 否则输入自带的 {@code + - ( ) > < ~ * " @} 会静默改变检索语义。
 * 直接转发用户原文的接口（工作记录关键词检索）仍用自然语言模式。
 *
 * <p>中文按滑动 2-gram 展开成词，而不是把整句当一个词：布尔模式下 ngram 索引会把长度 ≥ 2
 * 的检索词当作短语做相邻匹配，整句入查询等于要求原文出现完全连续的同一段文字，
 * 实测换个说法召回就是 0。展开成 2-gram 后召回退化为“出现任意相邻两字”，
 * 相关度分数由命中词数自然形成梯度。
 *
 * @param booleanQuery 组装后的布尔模式查询串，可能为空串表示无法组装
 * @param likeTerms    {@code LIKE} 兜底用的词表，按判别力倒序
 * @author pwt
 */
public record ContextQuery(String booleanQuery, List<String> likeTerms) {

    /**
     * 布尔模式运算符与全文检索特殊字符，剔除后才允许进入查询串；
     * {@code %} 与 {@code _} 只对 {@code LIKE} 有意义，一并清掉，两个通道共用一份清洗结果。
     */
    private static final Pattern OPERATORS =
        Pattern.compile("[+\\-()<>~*\"@_%!&/\\\\'`.,;:?\\[\\]{}|=$#^～（）【】、，。：；？！“”‘’《》]");

    /** 词元切分：按空白与非“字母/数字/文字”字符切。 */
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** 参与检索的最短词长，与 {@code ngram_token_size = 2} 对齐。 */
    private static final int MIN_TERM_LENGTH = 2;

    /** 单个 ASCII 词参与检索的最大长度，防止超长标识符挤占词表名额。 */
    private static final int MAX_ASCII_TERM_LENGTH = 32;

    /** 布尔查询串的最大词数，控制检索成本与查询串长度。 */
    private static final int MAX_TERMS = 24;

    /** {@code LIKE} 兜底最多组合的词数：词越多全表扫描成本越高，够用即可。 */
    private static final int MAX_LIKE_TERMS = 5;

    /** 无意义双字连接词：命中它们不携带信息，却会挤占词表名额并抬高噪声分数。 */
    private static final Set<String> STOP_BIGRAMS = Set.of("的了", "了我", "我们", "你们", "他们", "一个",
        "进行", "可以", "这个", "那个", "什么", "怎么", "如何", "需要", "已经", "因为", "所以", "但是",
        "然后", "一起", "一些", "东西");

    public ContextQuery {
        booleanQuery = booleanQuery == null ? "" : booleanQuery;
        likeTerms = likeTerms == null ? List.of() : List.copyOf(likeTerms);
    }

    /**
     * 解析任务描述。
     *
     * @param task 任务描述
     * @return 检索串，输入为空时两个词表都为空
     */
    public static ContextQuery parse(String task) {
        if (task == null || task.isBlank()) {
            return new ContextQuery("", List.of());
        }
        String cleaned = OPERATORS.matcher(task).replaceAll(" ");
        return new ContextQuery(buildBoolean(cleaned), buildLikeTerms(cleaned));
    }

    /**
     * 是否能组装出可用的布尔查询串。
     *
     * <p>整句只有一个字（如“锁”）时组不出长度 ≥ 2 的词，全文检索必然空转，
     * 此时由调用方直接走 {@code LIKE} 兜底（设计文档 8.5 的单字退化要求）。
     *
     * @return true 表示可走全文检索
     */
    public boolean usableForFullText() {
        return !booleanQuery.isEmpty();
    }

    /**
     * 转义 {@code LIKE} 通配符。清洗阶段已剔除 {@code %} 与 {@code _}，这里只作为二次保险：
     * 兜底词也可能来自调用方给出的其它字段。
     *
     * @param term 原词
     * @return 可安全用于 {@code LIKE ... ESCAPE '\\'} 的模式
     */
    public static String escapeLike(String term) {
        if (term == null) {
            return "";
        }
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String buildBoolean(String cleaned) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : tokenize(cleaned)) {
            for (String segment : splitByScript(token)) {
                if (isAsciiWord(segment)) {
                    if (segment.length() >= MIN_TERM_LENGTH && segment.length() <= MAX_ASCII_TERM_LENGTH) {
                        terms.add(segment);
                    }
                } else {
                    addBigrams(segment, terms);
                }
            }
            if (terms.size() >= MAX_TERMS) {
                break;
            }
        }
        if (terms.size() <= MAX_TERMS) {
            return String.join(" ", terms);
        }
        return String.join(" ", new ArrayList<>(terms).subList(0, MAX_TERMS));
    }

    /**
     * 兜底词表：优先取长度 ≥ 2 的片段，按长度倒序——越长的片段判别力越强，
     * 越不容易把无关记录整片捞出来。全句拆不出这样的词时（典型是单字查询，如“锁”）
     * 退化为单字词：{@code ngram_token_size = 2} 下单字永远命中不了索引，
     * 不走 {@code LIKE} 就会静默返回空结果，与“用户确实没记过”不可区分。
     *
     * @param cleaned 已剔除运算符的原文
     * @return 词表
     */
    private static List<String> buildLikeTerms(String cleaned) {
        List<String> tokens = tokenize(cleaned);
        List<String> usable = tokens.stream()
            .filter(token -> token.length() >= MIN_TERM_LENGTH)
            .sorted(Comparator.comparingInt(String::length).reversed())
            .limit(MAX_LIKE_TERMS)
            .toList();
        if (!usable.isEmpty()) {
            return usable;
        }
        return tokens.stream().limit(MAX_LIKE_TERMS).toList();
    }

    /**
     * 按文字种类切分词元：{@code 使用feign上传} → {@code [使用, feign, 上传]}。
     *
     * <p>必须切：ngram 索引里 ASCII 词与中文 2-gram 是两套词条，混在一个词元里两头都命中不了。
     *
     * @param token 词元
     * @return 同种文字构成的片段，保持原顺序
     */
    private static List<String> splitByScript(String token) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean ascii = isAsciiLetterOrDigit(token.charAt(0));
        for (int i = 0; i < token.length(); i++) {
            char character = token.charAt(i);
            if (!Character.isSurrogate(character) && isAsciiLetterOrDigit(character) != ascii) {
                segments.add(current.toString());
                current.setLength(0);
                ascii = !ascii;
            }
            current.append(character);
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        return segments;
    }

    /**
     * 把非 ASCII 片段展开为滑动 2-gram。
     *
     * <p>按码点而不是按 char 滑窗：代理对（emoji 等增补平面字符）如果按 char 切，
     * 会拆出半个字符组成的非法词，检索时永远命中不了。
     *
     * @param segment 片段
     * @param terms   词表，就地追加
     */
    private static void addBigrams(String segment, Set<String> terms) {
        int[] codePoints = segment.codePoints().toArray();
        if (codePoints.length < MIN_TERM_LENGTH) {
            return;
        }
        for (int i = 0; i + MIN_TERM_LENGTH <= codePoints.length; i++) {
            String bigram = new String(codePoints, i, MIN_TERM_LENGTH);
            if (!STOP_BIGRAMS.contains(bigram)) {
                terms.add(bigram);
            }
        }
    }

    private static List<String> tokenize(String cleaned) {
        List<String> tokens = new ArrayList<>();
        for (String token : SEPARATORS.split(cleaned)) {
            if (!token.isBlank()) {
                tokens.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private static boolean isAsciiWord(String segment) {
        return !segment.isEmpty() && segment.chars().allMatch(value -> isAsciiLetterOrDigit((char) value));
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return character < 0x80 && Character.isLetterOrDigit(character);
    }
}
