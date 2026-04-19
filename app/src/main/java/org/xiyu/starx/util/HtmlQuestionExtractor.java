package org.xiyu.starx.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 WebView 抓取的 outerHTML 中解析学习通题目。
 *
 * 覆盖三种页面模式：
 *   - 考试  : div.singleQuesId.ans-cc-exam
 *   - 随堂   : div.answer-item
 *   - 作业  : div.wid750.singleQuesId / div.pad30
 *
 * 比 OCR 路线快 100 倍、无识别误差。
 */
public final class HtmlQuestionExtractor {

    public enum Type {
        SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE, FILL_BLANK, SHORT_ANSWER, UNKNOWN;

        public static Type fromTitle(String title) {
            if (title == null) return UNKNOWN;
            if (title.contains("单选")) return SINGLE_CHOICE;
            if (title.contains("多选")) return MULTIPLE_CHOICE;
            if (title.contains("判断")) return TRUE_FALSE;
            if (title.contains("填空")) return FILL_BLANK;
            if (title.contains("简答")) return SHORT_ANSWER;
            return UNKNOWN;
        }

        public int legacyCode() {
            switch (this) {
                case SINGLE_CHOICE: return 0;
                case MULTIPLE_CHOICE: return 1;
                case FILL_BLANK: return 2;
                case TRUE_FALSE: return 3;
                case SHORT_ANSWER: return 4;
                default: return -1;
            }
        }
    }

    public static final class Option {
        public final String key;   // A/B/C/D
        public final String text;
        public Option(String key, String text) {
            this.key = key == null ? "" : key.trim();
            this.text = text == null ? "" : text.trim();
        }
    }

    public static final class Question {
        public String stem = "";
        public Type type = Type.UNKNOWN;
        public List<Option> options = Collections.emptyList();
        public int blankCount = 0;
        public int index = -1;

        /** 格式化为 AnswerProvider 所需的 options 字符串（A.xxx\nB.yyy）。 */
        public String optionsAsText() {
            if (options == null || options.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Option opt : options) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(opt.key).append('.').append(opt.text);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
    }

    private HtmlQuestionExtractor() {}

    /**
     * 解析整页 HTML → 题目列表。失败返回空列表。
     */
    public static List<Question> parse(String rawHtml) {
        if (rawHtml == null || rawHtml.isEmpty()) return Collections.emptyList();
        try {
            String html = decodeUnicodeEscapes(rawHtml);
            Document doc = Jsoup.parse(html);
            Elements exam = doc.select("div.singleQuesId.ans-cc-exam");
            Elements quiz = doc.select("div.answer-item");
            Elements work = doc.select("div.wid750.singleQuesId, div.pad30");
            int ne = exam.size(), nq = quiz.size(), nw = work.size();

            Elements pick;
            String mode;
            if (ne > 0 && ne >= nq && ne >= nw) {
                pick = exam; mode = "exam";
            } else if (nq > 0 && nq >= nw) {
                pick = quiz; mode = "quiz";
            } else if (nw > 0) {
                pick = work; mode = "work";
            } else {
                return Collections.emptyList();
            }

            List<Question> out = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Element el : pick) {
                try {
                    Question q;
                    switch (mode) {
                        case "exam": q = parseExam(el); break;
                        case "quiz": q = parseQuiz(el); break;
                        default:     q = parseWork(el); break;
                    }
                    if (q == null || q.stem == null || q.stem.isEmpty()) continue;
                    if (!seen.add(q.stem)) continue;
                    out.add(q);
                } catch (Throwable t) {
                    Logx.w("HtmlQuestionExtractor: skip bad block: " + t.getMessage());
                }
            }
            Logx.i("HtmlQuestionExtractor: mode=" + mode + " parsed=" + out.size());
            return out;
        } catch (Throwable t) {
            Logx.w("HtmlQuestionExtractor: parse failed: " + t.getMessage());
            return Collections.emptyList();
        }
    }

    // ---------- 作业页 ----------
    private static Question parseWork(Element el) {
        Element titleEl = el.selectFirst("h2.titType");
        if (titleEl == null) return null;
        String title = titleEl.text().trim();
        Question q = new Question();
        q.index = extractIndex(title);
        q.type = Type.fromTitle(title);
        Element stemEl = el.selectFirst("div.ans-cc.timuStyle");
        q.stem = stemEl != null ? stemEl.text().trim() : "";
        if (q.type == Type.FILL_BLANK) {
            q.blankCount = extractBlankCount(el);
        } else {
            q.options = extractWorkOptions(el, title);
        }
        return q;
    }

    private static List<Option> extractWorkOptions(Element el, String title) {
        List<Option> list = new ArrayList<>();
        Elements items = el.select("div.Answer, div.clearfix.Answer");
        boolean isJudge = title != null && title.contains("判断");
        for (Element item : items) {
            Element keyEl = item.selectFirst("span.check, span.choose, span.dxcheck");
            if (keyEl == null) continue;
            String key = keyEl.text().trim();
            String text = "";
            if (isJudge) {
                Element p = item.selectFirst("p");
                if (p != null) text = p.text().trim();
            } else {
                Element body = item.selectFirst("div.centerSpan");
                if (body != null) text = body.text().trim();
            }
            list.add(new Option(key, text));
        }
        return list;
    }

    // ---------- 随堂练习 ----------
    private static Question parseQuiz(Element el) {
        Element titleEl = el.selectFirst("div.answer-title");
        if (titleEl == null) return null;
        Element typeEl = titleEl.selectFirst("span.gray");
        Question q = new Question();
        q.type = Type.fromTitle(typeEl != null ? typeEl.text() : "");
        Element stemEl = titleEl.selectFirst("span.html-content-box");
        String stem = stemEl != null ? stemEl.text().trim() : "";
        if (stem.isEmpty()) {
            Element p = titleEl.selectFirst("p");
            if (p != null) stem = p.text().trim();
        }
        q.stem = stem;
        if (q.type == Type.SINGLE_CHOICE || q.type == Type.MULTIPLE_CHOICE || q.type == Type.TRUE_FALSE) {
            List<Option> list = new ArrayList<>();
            for (Element item : el.select("label.option-item")) {
                Element keyEl = item.selectFirst(".option-key, .option-letter, span");
                Element textEl = item.selectFirst(".option-content, .option-text, p");
                String key = keyEl != null ? keyEl.text().trim() : "";
                String text = textEl != null ? textEl.text().trim() : item.text().trim();
                if (!key.isEmpty() || !text.isEmpty()) list.add(new Option(key, text));
            }
            q.options = list;
        }
        return q;
    }

    // ---------- 考试页 ----------
    private static Question parseExam(Element el) {
        Question q = new Question();
        // 题干
        Element stemEl = el.selectFirst("div.mark_name, div.Zy_TItle, div.q-title, .stem");
        String stem = stemEl != null ? stemEl.text().trim() : el.text().trim();
        q.stem = stem;
        // 题型判定
        q.type = Type.fromTitle(stem);
        if (q.type == Type.UNKNOWN) {
            if (el.select("div.singleChoice").size() > 0) q.type = Type.SINGLE_CHOICE;
            else if (el.select("div.mulChoice").size() > 0) q.type = Type.MULTIPLE_CHOICE;
            else if (el.select("div.judgeoption, div.trueOrFalse").size() > 0) q.type = Type.TRUE_FALSE;
        }
        // 选项
        if (q.type == Type.SINGLE_CHOICE || q.type == Type.MULTIPLE_CHOICE || q.type == Type.TRUE_FALSE) {
            List<Option> list = new ArrayList<>();
            String container = q.type == Type.MULTIPLE_CHOICE ? "mulChoice"
                    : q.type == Type.TRUE_FALSE ? "trueOrFalse" : "singleChoice";
            Elements items = el.select("div." + container);
            if (items.isEmpty() && q.type == Type.TRUE_FALSE) {
                items = el.select("div.judgeoption");
            }
            if (items.isEmpty()) {
                items = el.select("div.singleoption, div.mulChoice, div.judgeoption");
            }
            for (Element opt : items) {
                Element keyEl = opt.selectFirst("span.No");
                Element textEl = opt.selectFirst("div.answerInfo");
                String key = keyEl != null ? keyEl.text().trim() : "";
                String text = textEl != null ? textEl.text().trim() : opt.text().trim();
                if (!key.isEmpty() || !text.isEmpty()) list.add(new Option(key, text));
            }
            q.options = list;
        } else if (q.type == Type.FILL_BLANK) {
            q.blankCount = extractBlankCount(el);
        }
        return q;
    }

    // ---------- 工具 ----------
    private static final Pattern INDEX_PAT = Pattern.compile("^(\\d+)");

    private static int extractIndex(String title) {
        if (title == null) return -1;
        Matcher m = INDEX_PAT.matcher(title);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static int extractBlankCount(Element el) {
        Elements blankNum = el.select("input[name^=blankNum]");
        if (!blankNum.isEmpty()) {
            Matcher m = Pattern.compile("(\\d+)").matcher(blankNum.first().val());
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        Elements inputs = el.select("input[name^=blank], textarea[name^=blank]");
        if (!inputs.isEmpty()) return inputs.size();
        Elements ue = el.select("div.ueditor-container");
        if (!ue.isEmpty()) return ue.size();
        Element stemEl = el.selectFirst("div.ans-cc.timuStyle");
        if (stemEl != null) {
            String text = stemEl.text();
            int c = 0;
            for (int i = text.indexOf("____"); i != -1; i = text.indexOf("____", i + 1)) c++;
            if (c > 0) return c;
        }
        return 1;
    }

    /**
     * 将 \\uXXXX 反转义为字面字符。学习通部分页面在返回的 innerHTML 中把中文字符
     * 转成了 \\u 序列，jsoup 解析前先展开。
     */
    private static String decodeUnicodeEscapes(String s) {
        if (s == null || s.indexOf("\\u") < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        Matcher m = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(s);
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            try {
                sb.append((char) Integer.parseInt(m.group(1), 16));
            } catch (NumberFormatException nfe) {
                sb.append(m.group());
            }
            last = m.end();
        }
        sb.append(s, last, s.length());
        return sb.toString();
    }

    /** 把一个 Question 映射为可直接给 LLM 的精简 Map。 */
    public static Map<String, Object> toPromptModel(Question q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stem", q.stem);
        m.put("type", q.type.name().toLowerCase(java.util.Locale.ROOT));
        if (q.options != null && !q.options.isEmpty()) {
            List<Map<String, String>> opts = new ArrayList<>();
            for (Option o : q.options) {
                Map<String, String> om = new LinkedHashMap<>();
                om.put("key", o.key);
                om.put("text", o.text);
                opts.add(om);
            }
            m.put("options", opts);
        }
        if (q.type == Type.FILL_BLANK && q.blankCount > 0) {
            m.put("blanks", q.blankCount);
        }
        return m;
    }
}
