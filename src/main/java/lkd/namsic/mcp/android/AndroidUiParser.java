package lkd.namsic.mcp.android;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code uiautomator dump} XML을 LLM 친화 압축 목록으로 변환하는 static 유틸.
 * 기기 없이 단위 테스트 가능하도록 순수 함수로만 구성한다.
 */
@Slf4j
final class AndroidUiParser {

    private static final Pattern BOUNDS_PATTERN = Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]");

    /**
     * @param nodes       노출 대상 노드 (clickable이거나 text/content-desc가 있는 것)
     * @param hiddenCount 필터링으로 숨겨진 노드 수
     * @param truncated   maxNodes 초과로 절단됐는지
     */
    public record ParseResult(List<UiNode> nodes, int hiddenCount, boolean truncated) {

        static ParseResult empty() {
            return new ParseResult(List.of(), 0, false);
        }
    }

    private AndroidUiParser() {
    }

    /**
     * dump XML 전체를 파싱한다. 잘못된/빈 XML은 예외 없이 빈 결과를 반환한다
     * (호출자가 "요소 없음 → 스크린샷 폴백" 안내로 처리).
     */
    static ParseResult parse(final String rawXml, final int maxNodes) {
        final String xml = stripPrefixNoise(rawXml);
        if (xml.isEmpty()) {
            return ParseResult.empty();
        }
        final Document document;
        try {
            document = newSecureDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (final Exception ex) {
            log.warn("Failed to parse uiautomator dump XML: {}", ex.getMessage());
            return ParseResult.empty();
        }
        final Element root = document.getDocumentElement();
        if (root == null) {
            return ParseResult.empty();
        }
        final List<UiNode> nodes = new ArrayList<>();
        final int[] hidden = {0};
        final boolean[] truncated = {false};
        collect(root, nodes, hidden, truncated, maxNodes);
        return new ParseResult(List.copyOf(nodes), hidden[0], truncated[0]);
    }

    /** 파싱 결과를 LLM이 바로 다음 행동을 정할 수 있는 압축 텍스트로 변환한다. */
    static String toCompactList(final ParseResult result) {
        if (result.nodes().isEmpty()) {
            return "(no interactive elements found — the screen may be a WebView/Flutter/game surface that does not "
                + "expose its UI tree; use android_screenshot and tap by raw x,y coordinates instead)";
        }
        final StringBuilder sb = new StringBuilder(result.nodes().size() * 80);
        sb.append("UI elements (").append(result.nodes().size()).append(" shown):\n");
        for (final UiNode node : result.nodes()) {
            sb.append(node.toCompactLine()).append('\n');
        }
        if (result.truncated()) {
            sb.append("(truncated at ").append(result.nodes().size())
                .append(" nodes — pass a larger maxNodes to see more)\n");
        }
        if (result.hiddenCount() > 0) {
            sb.append('(').append(result.hiddenCount()).append(" non-interactive nodes hidden)\n");
        }
        sb.append("Tap an element with android_tap using selector (id/text/desc substring) or elementIndex.");
        return sb.toString();
    }

    /** "[0,100][1080,200]" → {0, 100, 1080, 200}. 형식 불일치 시 null. */
    static int[] parseBounds(final String bounds) {
        if (bounds == null) {
            return null;
        }
        final Matcher matcher = BOUNDS_PATTERN.matcher(bounds);
        if (!matcher.matches()) {
            return null;
        }
        return new int[]{
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3)),
            Integer.parseInt(matcher.group(4)),
        };
    }

    /** dump 명령이 XML 앞에 끼워 넣는 잡소리("UI hierchary dumped to: ..." 등)를 제거한다. */
    private static String stripPrefixNoise(final String rawXml) {
        if (rawXml == null || rawXml.isBlank()) {
            return "";
        }
        int start = rawXml.indexOf("<?xml");
        if (start < 0) {
            start = rawXml.indexOf("<hierarchy");
        }
        return start >= 0 ? rawXml.substring(start) : "";
    }

    private static void collect(final Element element, final List<UiNode> out, final int[] hidden,
        final boolean[] truncated, final int maxNodes) {
        if (truncated[0]) {
            return;
        }
        if ("node".equals(element.getTagName())) {
            final String text = element.getAttribute("text").trim();
            final String contentDesc = element.getAttribute("content-desc").trim();
            final boolean clickable = Boolean.parseBoolean(element.getAttribute("clickable"));
            if (clickable || !text.isEmpty() || !contentDesc.isEmpty()) {
                final int[] bounds = parseBounds(element.getAttribute("bounds"));
                if (bounds != null) {
                    if (out.size() >= maxNodes) {
                        truncated[0] = true;
                        return;
                    }
                    out.add(new UiNode(
                        out.size(),
                        text,
                        element.getAttribute("resource-id").trim(),
                        contentDesc,
                        element.getAttribute("class").trim(),
                        clickable,
                        bounds[0], bounds[1], bounds[2], bounds[3],
                        (bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2
                    ));
                }
            } else {
                hidden[0]++;
            }
        }
        final NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child instanceof Element childElement) {
                collect(childElement, out, hidden, truncated, maxNodes);
            }
        }
    }

    /** XXE 방어가 적용된 DocumentBuilder (dump XML은 신뢰 불가 입력으로 취급). */
    private static DocumentBuilder newSecureDocumentBuilder() throws ParserConfigurationException {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }
}
