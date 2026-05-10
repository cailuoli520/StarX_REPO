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
        SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE, FILL_BLANK, SHORT_ANSWER,
        READING_COMPREHENSION, CLOZE, UNKNOWN;

        public static Type fromTitle(String title) {
            if (title == null) return UNKNOWN;
            String text = title.replaceAll("\\s+", "");
            if (text.contains("阅读理解")) return READING_COMPREHENSION;
            if (text.contains("完形填空") || text.contains("完型填空")) return CLOZE;
            if (text.contains("单选")) return SINGLE_CHOICE;
            if (text.contains("多选")) return MULTIPLE_CHOICE;
            if (text.contains("判断")) return TRUE_FALSE;
            if (text.contains("填空")) return FILL_BLANK;
            if (text.contains("简答") || text.contains("名词解释") || text.contains("论述")
                    || text.contains("问答") || text.contains("简述") || text.contains("解答")
                    || text.contains("材料分析") || text.contains("案例分析")) return SHORT_ANSWER;
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
        public String itemId = null;

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
            // 章节测验（mworkspecial 页面）：div.Py-mian1.singleQuesId
            Elements chapter = doc.select("div.Py-mian1.singleQuesId");
            int ne = exam.size(), nq = quiz.size(), nw = work.size(), nc = chapter.size();

            Elements pick;
            String mode;
            if (nc > 0 && nc >= ne && nc >= nq && nc >= nw) {
                pick = chapter; mode = "chapter";
            } else if (ne > 0 && ne >= nq && ne >= nw) {
                pick = exam; mode = "exam";
            } else if (nq > 0 && nq >= nw) {
                pick = quiz; mode = "quiz";
            } else if (nw > 0) {
                pick = work; mode = "work";
            } else {
                // 通用 fallback：匹配新版 UI（"1. 单选题" 这种简洁页面）
                List<Question> generic = parseGeneric(doc);
                Logx.i("HtmlQuestionExtractor: mode=generic parsed=" + generic.size()
                        + " (exam=0 quiz=0 work=0, html.len=" + html.length() + ")");
                if (generic.isEmpty()) {
                    // 诊断：解析失败时简要 dump
                    try {
                        Element body = doc.body();
                        String bodyText = body != null ? body.text() : "";
                        if (bodyText.length() > 200) bodyText = bodyText.substring(0, 200);
                        Logx.i("HtmlQuestionExtractor[DBG] bodyText=" + bodyText);
                    } catch (Throwable ignored) {}
                }
                return generic;
            }

            List<Question> out = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Element el : pick) {
                try {
                    List<Question> parsed = new ArrayList<>();
                    switch (mode) {
                        case "exam":
                            parsed.addAll(parseExamQuestions(el));
                            break;
                        case "quiz": {
                            Question q = parseQuiz(el);
                            if (q != null) parsed.add(q);
                            break;
                        }
                        case "chapter": {
                            Question q = parseChapter(el);
                            if (q != null) parsed.add(q);
                            break;
                        }
                        default: {
                            Question q = parseWork(el);
                            if (q != null) parsed.add(q);
                            break;
                        }
                    }
                    for (Question q : parsed) {
                        if (q == null || q.stem == null || q.stem.isEmpty()) continue;
                        String key = q.stem + "|" + (q.itemId == null ? "" : q.itemId);
                        if (!seen.add(key)) continue;
                        out.add(q);
                    }
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
        q.itemId = readQuestionItemId(el);
        q.index = extractIndex(title);
        q.type = Type.fromTitle(title);
        Element stemEl = el.selectFirst("div.ans-cc.timuStyle");
        q.stem = stemEl != null ? stemEl.text().trim() : "";
        if (q.type == Type.FILL_BLANK) {
            q.blankCount = extractBlankCount(el);
        } else {
            q.options = extractWorkOptions(el, title);
        }
        normalizeQuestionShape(q, el, title);
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
        q.itemId = readQuestionItemId(el);
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
        normalizeQuestionShape(q, el, typeEl != null ? typeEl.text() : "");
        return q;
    }

    // ---------- 章节测验页（mworkspecial / Py-mian1.singleQuesId） ----------
    private static final Pattern CHAPTER_TITLE_PAT =
            Pattern.compile("^\\s*(\\d+)\\.\\s*\\[([^\\]]+)\\]\\s*(.*)$", Pattern.DOTALL);

    private static Question parseChapter(Element el) {
        Element titleEl = el.selectFirst("div.Py-m1-title, .Py-m1-title, div.fontLabel");
        if (titleEl == null) return null;
        String raw = titleEl.text().trim();
        Question q = new Question();
        q.itemId = readQuestionItemId(el);
        String stem = raw;
        Matcher m = CHAPTER_TITLE_PAT.matcher(raw);
        if (m.find()) {
            try { q.index = Integer.parseInt(m.group(1)); } catch (Throwable ignored) {}
            q.type = Type.fromTitle(m.group(2));
            String body = m.group(3);
            if (body != null) stem = body.trim();
        } else {
            q.index = extractIndex(raw);
            q.type = Type.fromTitle(raw);
        }
        // 若题干里含"（）"或"____"，保留原状即可
        q.stem = stem;

        if (q.type == Type.FILL_BLANK) {
            q.blankCount = Math.max(1, extractBlankCount(el));
        } else if (q.type == Type.SHORT_ANSWER) {
            q.options = Collections.emptyList();
        } else {
            // 选项：li.clearfix.more-choose-item（或 div.more-choose-item）
            Elements items = el.select("li.more-choose-item, div.more-choose-item, .more-choose-item");
            List<Option> list = new ArrayList<>();
            int idx = 0;
            for (Element item : items) {
                Element descEl = item.selectFirst("div.choose-desc, .choose-desc, .workTextWrap");
                String text = descEl != null ? descEl.text().trim() : item.text().trim();
                // 尝试找显式字母（有些模板里 span.num 会带 "A"）
                Element keyEl = item.selectFirst(".num, .option-letter, .before");
                String key = keyEl != null ? keyEl.text().trim().replaceAll("[^A-Za-z]", "") : "";
                if (key.isEmpty()) key = String.valueOf((char) ('A' + idx));
                if (!text.isEmpty()) list.add(new Option(key, text));
                idx++;
            }
            q.options = list;
        }
        normalizeQuestionShape(q, el, raw);
        return q;
    }

    // ---------- 考试页 ----------
    private static List<Question> parseExamQuestions(Element el) {
        List<Question> children = parseCompositeChildren(el);
        if (!children.isEmpty()) return children;
        Question q = parseExam(el);
        if (q == null) return Collections.emptyList();
        return Collections.singletonList(q);
    }

    private static List<Question> parseCompositeChildren(Element el) {
        Elements slides = el.select(".readingComprehension .swiper-slide[data-itemid],"
                + ".clozeQuestion .swiper-slide[data-itemid],"
                + ".wanxingtiankong .swiper-slide[data-itemid],"
                + ".swiper-slide.slidescroll[data-itemid],"
                + ".child-question-wrapper[data-itemid]");
        if (slides.isEmpty()) return Collections.emptyList();

        List<Element> ordered = new ArrayList<>(slides);
        Collections.sort(ordered, (a, b) -> Boolean.compare(!isVisibleCompositeChild(a), !isVisibleCompositeChild(b)));

        String parentType = readExamTypeName(el);
        String passage = extractCompositePassage(el);
        List<Question> out = new ArrayList<>();
        for (Element slide : ordered) {
            Question q = new Question();
            q.itemId = readQuestionItemId(slide);
            Element stemEl = slide.selectFirst("h3.tit, .tit, .stem, .q-title, .mark_name, .Zy_TItle");
            String childStem = compactText(stemEl != null ? stemEl.text() : slide.text());
            if (childStem.isEmpty()) continue;
            q.stem = passage.isEmpty() ? childStem : passage + "\n" + childStem;
            q.index = extractIndex(childStem);
            q.type = inferExamLeafType(slide, childStem, parentType);
            q.options = extractExamOptions(slide, q.type);
            normalizeQuestionShape(q, slide, childStem + " " + parentType);
            out.add(q);
        }
        return out;
    }

    private static Question parseExam(Element el) {
        Question q = new Question();
        q.itemId = readQuestionItemId(el);
        // 题干
        Element stemEl = el.selectFirst("div.mark_name, div.Zy_TItle, div.q-title, .stem, .answerCon > .pad30 > .tit");
        String stem = stemEl != null ? stemEl.text().trim() : el.text().trim();
        q.stem = stem;
        // 题型判定
        String typeTitle = readExamTypeName(el);
        q.type = inferExamLeafType(el, stem, typeTitle);
        // 选项
        if (q.type == Type.SINGLE_CHOICE || q.type == Type.MULTIPLE_CHOICE || q.type == Type.TRUE_FALSE) {
            q.options = extractExamOptions(el, q.type);
        } else if (q.type == Type.FILL_BLANK) {
            q.blankCount = extractBlankCount(el);
        }
        normalizeQuestionShape(q, el, typeTitle + " " + stem);
        return q;
    }

    private static Type inferExamChoiceType(Element el, Type fallback) {
        if (el.select("div.mulChoice, div.child-mulChoice").size() > 0) return Type.MULTIPLE_CHOICE;
        if (el.select("div.judgeoption, div.trueOrFalse, div.child-judgeoption, div.child-trueOrFalse").size() > 0) return Type.TRUE_FALSE;
        if (el.select("div.singleChoice, div.child-singleChoice, div.singleoption").size() > 0) return Type.SINGLE_CHOICE;
        return fallback == null ? Type.UNKNOWN : fallback;
    }

    private static Type inferExamLeafType(Element el, String childTitle, String parentTitle) {
        Type childHint = Type.fromTitle(childTitle);
        if (isConcreteLeafType(childHint)) return childHint;

        Type domChoice = inferExamChoiceType(el, Type.UNKNOWN);
        if (domChoice != Type.UNKNOWN) return domChoice;

        if (looksFillLike(el, childTitle)) return Type.FILL_BLANK;
        if (hasWritableAnswer(el)) return Type.SHORT_ANSWER;

        Type parentHint = Type.fromTitle(parentTitle);
        return isConcreteLeafType(parentHint) ? parentHint : Type.UNKNOWN;
    }

    private static boolean isConcreteLeafType(Type type) {
        return type == Type.SINGLE_CHOICE
                || type == Type.MULTIPLE_CHOICE
                || type == Type.TRUE_FALSE
                || type == Type.FILL_BLANK
                || type == Type.SHORT_ANSWER;
    }

    private static List<Option> extractExamOptions(Element el, Type type) {
        List<Option> list = new ArrayList<>();
        Elements items;
        if (type == Type.MULTIPLE_CHOICE) {
            items = el.select("div.mulChoice, div.child-mulChoice");
        } else if (type == Type.TRUE_FALSE) {
            items = el.select("div.judgeoption, div.trueOrFalse, div.child-judgeoption, div.child-trueOrFalse");
        } else if (type == Type.SINGLE_CHOICE) {
            items = el.select("div.singleChoice, div.child-singleChoice, div.singleoption");
        } else {
            items = el.select("div.singleChoice, div.mulChoice, div.judgeoption, div.trueOrFalse,"
                    + "div.child-singleChoice, div.child-mulChoice, div.child-judgeoption, div.child-trueOrFalse, div.singleoption");
        }
        if (items.isEmpty()) {
            items = el.select("li.fl_l, li.clearfix, .answerBg, .option-item, .option_li, .optionItem,"
                    + "label.option, label.option-item, .choose_item, li.more-choose-item, .more-choose-item");
        }
        int optionIndex = 0;
        for (Element opt : items) {
            Element keyEl = opt.selectFirst("span.No, span.num, span.check, span.choose, span.dxcheck");
            Element textEl = opt.selectFirst("div.answerInfo, cc, .answerInfo, .choose-desc, .workTextWrap");
            String key = keyEl != null ? keyEl.text().trim().replaceAll("[^A-Za-z]", "") : "";
            if (key.isEmpty()) key = opt.attr("name").trim().replaceAll("[^A-Za-z]", "");
            String text = textEl != null ? textEl.text().trim() : opt.text().trim();
            if (key.isEmpty()) key = extractLeadingLetter(text);
            if (!key.isEmpty() && text.startsWith(key)) text = text.substring(key.length()).trim();
            text = stripLeadingLetter(text);
            if (key.isEmpty() && (type == Type.SINGLE_CHOICE || type == Type.MULTIPLE_CHOICE || type == Type.TRUE_FALSE)) {
                key = String.valueOf((char) ('A' + optionIndex));
            }
            if (!key.isEmpty() || !text.isEmpty()) list.add(new Option(key, text));
            optionIndex++;
        }
        return list;
    }

    private static String readQuestionItemId(Element el) {
        if (el == null) return null;
        String id = el.attr("data-itemid").trim();
        if (!id.isEmpty()) return id;
        id = el.attr("data").trim();
        if (!id.isEmpty()) return id;
        Element dataItem = el.selectFirst("[data-itemid]");
        if (dataItem != null) {
            id = dataItem.attr("data-itemid").trim();
            if (!id.isEmpty()) return id;
        }
        Element questionId = el.selectFirst("input[name=questionId], input#questionId");
        if (questionId != null) {
            id = questionId.attr("value").trim();
            if (!id.isEmpty()) return id;
        }
        return null;
    }

    private static String readExamTypeName(Element el) {
        if (el == null) return "";
        Element type = el.selectFirst("input[name^=typeName]");
        return type != null ? type.attr("value").trim() : "";
    }

    private static boolean isVisibleCompositeChild(Element el) {
        if (el == null) return false;
        String style = el.attr("style").toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        if (style.contains("display:none")) return false;
        if (style.contains("display:block") || style.contains("height:100%")) return true;
        String cls = el.className();
        return cls != null && (cls.contains(" cur") || cls.startsWith("cur ") || cls.equals("cur"));
    }

    private static String extractCompositePassage(Element el) {
        Element passageEl = el.selectFirst(".answerCon.readAnswerCon .tit, .answerCon.readAnswerCon, .readAnswerCon .tit");
        if (passageEl == null) {
            Element typeName = el.selectFirst("input[name^=typeName][value*=阅读], input[name^=typeName][value*=完]");
            if (typeName != null) passageEl = el.selectFirst(".answerCon > .pad30 > .tit, .answerCon .tit");
        }
        return compactText(passageEl != null ? passageEl.text() : "");
    }

    private static String compactText(String s) {
        if (s == null) return "";
        return s.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void normalizeQuestionShape(Question q, Element el, String titleHint) {
        if (q == null || el == null) return;
        Type hinted = Type.fromTitle(titleHint);
        if (q.type == Type.UNKNOWN && isConcreteLeafType(hinted)) q.type = hinted;

        if (q.type == Type.UNKNOWN) {
            Type domChoice = inferExamChoiceType(el, Type.UNKNOWN);
            if (domChoice != Type.UNKNOWN) q.type = domChoice;
        }

        if (q.type == Type.READING_COMPREHENSION || q.type == Type.CLOZE) {
            if (q.options != null && !q.options.isEmpty()) {
                q.type = inferExamChoiceType(el, Type.UNKNOWN);
            } else if (looksFillLike(el, titleHint)) {
                q.type = Type.FILL_BLANK;
            } else if (hasWritableAnswer(el)) {
                q.type = Type.SHORT_ANSWER;
            }
        }

        if ((q.type == Type.UNKNOWN || q.type == Type.SHORT_ANSWER) && looksFillLike(el, titleHint)) {
            q.type = Type.FILL_BLANK;
        } else if (q.type == Type.UNKNOWN && hasWritableAnswer(el)) {
            q.type = Type.SHORT_ANSWER;
        }

        if ((q.type == Type.UNKNOWN || q.type == Type.SINGLE_CHOICE) && q.options != null && !q.options.isEmpty()) {
            Type inferred = inferOptionOnlyType(q.options);
            if (q.type == Type.UNKNOWN || inferred == Type.TRUE_FALSE) q.type = inferred;
        }

        if (q.type == Type.READING_COMPREHENSION || q.type == Type.CLOZE) {
            q.type = Type.UNKNOWN;
        }

        if (q.type == Type.FILL_BLANK) {
            q.blankCount = Math.max(1, extractBlankCount(el));
            q.options = Collections.emptyList();
        } else if (q.type == Type.SHORT_ANSWER) {
            q.options = Collections.emptyList();
        }
    }

    private static Type inferOptionOnlyType(List<Option> options) {
        if (options == null || options.isEmpty()) return Type.UNKNOWN;
        if (options.size() == 2) {
            String joined = (options.get(0).text + options.get(1).text).replaceAll("\\s+", "");
            if (joined.contains("对") || joined.contains("错")
                    || joined.toLowerCase(java.util.Locale.ROOT).contains("true")
                    || joined.toLowerCase(java.util.Locale.ROOT).contains("false")) {
                return Type.TRUE_FALSE;
            }
        }
        return Type.SINGLE_CHOICE;
    }

    private static boolean looksFillLike(Element el, String titleHint) {
        String text = titleHint == null ? "" : titleHint;
        if (text.contains("____") || text.contains("填空")) return true;
        return el.select("input[name^=blank], textarea[name^=blank], input[name^=blankNum],"
                + ".completionList, [blankobj], .blank, [data-itemid*=blank]").size() > 0;
    }

    private static boolean hasWritableAnswer(Element el) {
        return el.select(".editorItem, .jdt, .editorContainer, .edui-editor, div.ueditor-container,"
                + "textarea[name^=answer], textarea.zsh_area, textarea[name=editorValue],"
                + "input[name^=answer], [contenteditable=true], iframe[id^=ueditor]").size() > 0;
    }

    // ---------- 工具 ----------
    private static final Pattern INDEX_PAT = Pattern.compile("^(\\d+)");

    /**
     * 通用 fallback：识别"1. 单选题 / 单选 / 判断"这种新版简洁 UI 页面。
     *
     * 策略：
      * 1. 扫描所有包含常见题型名的小容器（带或不带"N. xxx"前缀），视为题号标记。
     * 2. 从该标记向后取同级/紧邻的长文本块当题干，向后取带 A-Z 字母或 radio/checkbox 的节点当选项。
     * 3. 去重 + 按题干非空过滤。
     */
    private static List<Question> parseGeneric(Document doc) {
        List<Question> out = new ArrayList<>();
        java.util.Set<String> seenStems = new java.util.HashSet<>();
        Elements all = doc.select("body *");
        Pattern typeMarker = Pattern.compile("(?:^|[\\s\\.])(单选题|多选题|判断题|填空题|简答题|名词解释|论述题|问答题|阅读理解|完形填空|完型填空|材料分析|案例分析|单选|多选|判断|填空|简答|论述)");
        for (Element candidate : all) {
            String own = candidate.ownText();
            if (own == null || own.length() > 40 || own.length() < 2) continue;
            Matcher m = typeMarker.matcher(own);
            if (!m.find()) continue;
            Question q = new Question();
            q.type = Type.fromTitle(own);
            q.index = extractIndex(own);
            Element root = climbToQuestionRoot(candidate);
            Element stemEl = findStemUnder(root, candidate);
            String stem = stemEl != null ? stemEl.text().trim() : "";
            if (stem.isEmpty() || stem.length() < 4) continue;
            if (!seenStems.add(stem)) continue;
            q.stem = stem;
            if (q.type == Type.SINGLE_CHOICE || q.type == Type.MULTIPLE_CHOICE || q.type == Type.TRUE_FALSE
                    || q.type == Type.UNKNOWN) {
                q.options = findGenericOptions(root);
                if (q.type == Type.UNKNOWN && !q.options.isEmpty()) {
                    q.type = q.options.size() == 2 ? Type.TRUE_FALSE : Type.SINGLE_CHOICE;
                }
            } else if (q.type == Type.FILL_BLANK) {
                q.blankCount = Math.max(1, extractBlankCount(root));
            }
            normalizeQuestionShape(q, root, own + " " + stem);
            out.add(q);
        }
        return out;
    }

    /** 从给定节点向上爬到合理的题块容器（同级有选项的最近祖先）。 */
    private static Element climbToQuestionRoot(Element start) {
        Element cur = start;
        for (int depth = 0; depth < 6 && cur != null && cur.parent() != null; depth++) {
            Element p = cur.parent();
            // 如果父节点里已经能看到选项特征（含 A-D 字母 / label / radio），就停在父节点
            if (p.select("label, input[type=radio], input[type=checkbox]").size() >= 2) return p;
            String ptxt = p.text();
            if (ptxt != null) {
                int letters = 0;
                for (char c : new char[]{'A', 'B', 'C', 'D'}) {
                    if (ptxt.indexOf(c + ".") >= 0 || ptxt.indexOf(c + "、") >= 0 || ptxt.indexOf(c + " ") >= 0) letters++;
                }
                if (letters >= 2) return p;
            }
            cur = p;
        }
        return start.parent() != null ? start.parent() : start;
    }

    /** 在题块里找最可能的题干节点（长中文句、带问号或以数字开头）。 */
    private static Element findStemUnder(Element root, Element typeMarker) {
        Elements candidates = root.select("p, div, h1, h2, h3, span, section");
        Element best = null;
        int bestScore = 0;
        for (Element e : candidates) {
            if (e == typeMarker) continue;
            String t = e.ownText();
            if (t == null || t.length() < 6) continue;
            if (t.length() > 400) continue;
            int score = 0;
            score += Math.min(20, t.length() / 4);
            if (t.contains("?") || t.contains("？")) score += 6;
            if (t.startsWith("根据") || t.startsWith("下列") || t.startsWith("关于") || t.startsWith("以下")) score += 4;
            // 避免选到"下一题 / 答题卡 / 上一题"等 UI 按钮
            if (t.contains("下一题") || t.contains("上一题") || t.contains("答题卡") || t.contains("提交")) continue;
            // 避免选到含有太多 ABCD 前缀的节点（那应该是选项聚合）
            int letters = 0;
            for (char c : new char[]{'A', 'B', 'C', 'D'}) {
                if (t.indexOf(c + ".") >= 0) letters++;
                if (t.indexOf(c + "、") >= 0) letters++;
            }
            if (letters >= 2) continue;
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    /** 在题块里收集选项：A/B/C/D 前缀 / label / radio / checkbox 任一命中即可。 */
    private static List<Option> findGenericOptions(Element root) {
        List<Option> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        // 优先走 label + input
        Elements labels = root.select("label:has(input[type=radio]), label:has(input[type=checkbox])");
        for (Element l : labels) {
            String t = l.text().trim();
            if (t.isEmpty()) continue;
            String key = extractLeadingLetter(t);
            String txt = stripLeadingLetter(t);
            if (!seen.add(txt.isEmpty() ? t : txt)) continue;
            list.add(new Option(key, txt.isEmpty() ? t : txt));
        }
        if (!list.isEmpty()) return list;
        // 退化：找文本节点带 "A." / "A、" 等前缀的
        Elements nodes = root.select("li, p, div, span");
        for (Element n : nodes) {
            String t = n.ownText().trim();
            if (t.length() < 2 || t.length() > 300) continue;
            if (!LEADING_LETTER.matcher(t).find()) continue;
            String key = extractLeadingLetter(t);
            String txt = stripLeadingLetter(t);
            if (txt.isEmpty()) continue;
            if (!seen.add(txt)) continue;
            list.add(new Option(key, txt));
            if (list.size() >= 8) break;
        }
        return list;
    }

    private static final Pattern LEADING_LETTER = Pattern.compile("^\\s*([A-Za-z])[\\.、:：\\s]");

    private static String extractLeadingLetter(String text) {
        Matcher m = LEADING_LETTER.matcher(text);
        return m.find() ? m.group(1).toUpperCase(java.util.Locale.ROOT) : "";
    }

    private static String stripLeadingLetter(String text) {
        return LEADING_LETTER.matcher(text).replaceFirst("").trim();
    }

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
