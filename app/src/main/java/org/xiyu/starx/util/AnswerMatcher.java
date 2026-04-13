package org.xiyu.starx.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 答案模糊匹配工具
 *
 * - 多答案拆分（#、===、|）
 * - 冗余词过滤（"答案是"、"以下"、"选项" 等）
 * - Jaccard 相似度匹配（阈值 0.6）
 */
public class AnswerMatcher {

    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[#|]|===");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Set<String> REDUNDANT_WORDS = new HashSet<>(Arrays.asList(
            "答案是", "答案为", "答案：", "答案:", "以下", "选项", "正确答案",
            "参考答案", "标准答案", "本题答案", "解析", "故选", "所以",
            "综上所述", "因此", "解答", "分析"
    ));

    /**
     * 拆分多答案字符串
     */
    public static List<String> splitAnswers(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        List<String> results = new ArrayList<>();
        String[] parts = SPLIT_PATTERN.split(raw);
        for (String part : parts) {
            String cleaned = clean(part);
            if (!cleaned.isEmpty()) {
                results.add(cleaned);
            }
        }
        return results;
    }

    /**
     * 清洗文本：去 HTML、去冗余词、去空白
     */
    public static String clean(String text) {
        if (text == null) return "";
        String s = HTML_TAG.matcher(text).replaceAll("");
        s = WHITESPACE.matcher(s).replaceAll("");
        for (String word : REDUNDANT_WORDS) {
            s = s.replace(word, "");
        }
        return s.trim();
    }

    /**
     * 从候选答案列表中找到与题目选项最匹配的
     *
     * @param answerText 题库返回的原始答案（可能含多个）
     * @param options    题目选项列表 (如 ["A.xxx", "B.yyy", "C.zzz", "D.www"])
     * @return 匹配的选项字母列表，如 ["A", "C"]
     */
    public static List<String> matchOptions(String answerText, List<String> options) {
        List<String> matched = new ArrayList<>();
        if (answerText == null || options == null || options.isEmpty()) return matched;

        List<String> answers = splitAnswers(answerText);

        for (String option : options) {
            String optLetter = extractOptionLetter(option);
            String optContent = extractOptionContent(option);
            if (optLetter == null || optContent.isEmpty()) continue;

            for (String answer : answers) {
                // 完全匹配选项字母（如答案直接就是 "A"）
                if (answer.equalsIgnoreCase(optLetter)) {
                    if (!matched.contains(optLetter)) matched.add(optLetter);
                    break;
                }
                // 内容相似度匹配
                if (jaccardSimilarity(answer, optContent) >= SIMILARITY_THRESHOLD) {
                    if (!matched.contains(optLetter)) matched.add(optLetter);
                    break;
                }
                // 包含关系匹配
                if (optContent.contains(answer) || answer.contains(optContent)) {
                    if (!matched.contains(optLetter)) matched.add(optLetter);
                    break;
                }
            }
        }
        return matched;
    }

    /**
     * 从候选选项中找到单个最佳匹配（用于单选题防止误扩成多选）
     *
     * @param answerText 题库返回的原始答案（可能含多个子答案）
     * @param options    题目选项列表
     * @return 最佳匹配的选项字母，如 "A"；无匹配返回 null
     */
    public static String matchBestOption(String answerText, List<String> options) {
        if (answerText == null || options == null || options.isEmpty()) return null;

        List<String> answers = splitAnswers(answerText);
        String bestLetter = null;
        double bestScore = 0;

        for (String option : options) {
            String optLetter = extractOptionLetter(option);
            String optContent = extractOptionContent(option);
            if (optLetter == null || optContent.isEmpty()) continue;

            double optionScore = 0;
            for (String answer : answers) {
                if (answer.equalsIgnoreCase(optLetter)) {
                    optionScore = 1.0;
                    break;
                }
                double sim = jaccardSimilarity(answer, optContent);
                optionScore = Math.max(optionScore, sim);
                if (optContent.contains(answer) || answer.contains(optContent)) {
                    optionScore = Math.max(optionScore, SIMILARITY_THRESHOLD);
                }
            }

            if (optionScore >= SIMILARITY_THRESHOLD && optionScore > bestScore) {
                bestScore = optionScore;
                bestLetter = optLetter;
            }
        }

        return bestLetter;
    }

    /**
     * 判断答案是否与预期内容匹配
     */
    public static boolean isMatch(String expected, String actual) {
        if (expected == null || actual == null) return false;
        String a = clean(expected);
        String b = clean(actual);
        if (a.equalsIgnoreCase(b)) return true;
        if (a.contains(b) || b.contains(a)) return true;
        return jaccardSimilarity(a, b) >= SIMILARITY_THRESHOLD;
    }

    /**
     * Jaccard 字符级相似度
     */
    public static double jaccardSimilarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        String sa = clean(a);
        String sb = clean(b);
        if (sa.isEmpty() && sb.isEmpty()) return 1.0;
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;

        Set<Character> setA = toCharSet(sa);
        Set<Character> setB = toCharSet(sb);

        Set<Character> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        Set<Character> union = new HashSet<>(setA);
        union.addAll(setB);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static Set<Character> toCharSet(String s) {
        Set<Character> set = new HashSet<>();
        for (int i = 0; i < s.length(); i++) {
            set.add(s.charAt(i));
        }
        return set;
    }

    private static String extractOptionLetter(String option) {
        if (option == null || option.isEmpty()) return null;
        char first = option.charAt(0);
        if (first >= 'A' && first <= 'Z') return String.valueOf(first);
        if (first >= 'a' && first <= 'z') return String.valueOf(first).toUpperCase();
        return null;
    }

    private static String extractOptionContent(String option) {
        if (option == null) return "";
        // 去掉 "A." "A、" "A:" "A " 前缀
        String s = option.replaceFirst("^[A-Za-z][.、:：\\s]\\s*", "");
        return clean(s);
    }
}
