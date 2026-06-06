package lkd.namsic.mcp.android;

import lkd.namsic.mcp.android.AndroidUiParser.ParseResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndroidUiParserTest {

    private static final String SAMPLE_XML = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="" class="android.widget.FrameLayout" content-desc=""
                clickable="false" enabled="true" bounds="[0,0][1080,2400]">
            <node index="0" text="로그인" resource-id="com.example.app:id/btn_login"
                  class="android.widget.Button" content-desc="" clickable="true" enabled="true"
                  bounds="[120,1400][960,1520]"/>
            <node index="1" text="" resource-id="com.example.app:id/et_username"
                  class="android.widget.EditText" content-desc="아이디 입력" clickable="true" enabled="true"
                  bounds="[60,800][1020,920]"/>
            <node index="2" text="환영합니다" resource-id="" class="android.widget.TextView"
                  content-desc="" clickable="false" enabled="true" bounds="[0,100][1080,200]"/>
          </node>
        </hierarchy>
        """;

    @Test
    void parseExtractsVisibleNodesWithCenters() {
        ParseResult result = AndroidUiParser.parse(SAMPLE_XML, 200);

        assertEquals(3, result.nodes().size());
        // 빈 FrameLayout 컨테이너는 숨김
        assertEquals(1, result.hiddenCount());
        assertFalse(result.truncated());

        UiNode button = result.nodes().getFirst();
        assertEquals(0, button.index());
        assertEquals("로그인", button.text());
        assertEquals("com.example.app:id/btn_login", button.resourceId());
        assertTrue(button.clickable());
        assertEquals(540, button.centerX());
        assertEquals(1460, button.centerY());

        UiNode editText = result.nodes().get(1);
        assertEquals("아이디 입력", editText.contentDesc());
        assertEquals(540, editText.centerX());
        assertEquals(860, editText.centerY());

        // clickable=false여도 text가 있으면 노출
        UiNode textView = result.nodes().get(2);
        assertEquals("환영합니다", textView.text());
        assertFalse(textView.clickable());
    }

    @Test
    void parseRespectsMaxNodes() {
        ParseResult result = AndroidUiParser.parse(SAMPLE_XML, 2);
        assertEquals(2, result.nodes().size());
        assertTrue(result.truncated());
    }

    @Test
    void parseStripsDumpPrefixNoise() {
        ParseResult result = AndroidUiParser.parse(
            "UI hierchary dumped to: /dev/tty\n" + SAMPLE_XML, 200);
        assertEquals(3, result.nodes().size());
    }

    @Test
    void parseReturnsEmptyOnInvalidXml() {
        assertTrue(AndroidUiParser.parse("<?xml version='1.0'?><hierarchy><node", 200).nodes().isEmpty());
        assertTrue(AndroidUiParser.parse("", 200).nodes().isEmpty());
        assertTrue(AndroidUiParser.parse(null, 200).nodes().isEmpty());
        assertTrue(AndroidUiParser.parse("ERROR: could not get idle state.", 200).nodes().isEmpty());
    }

    @Test
    void parseRejectsDoctypeXml() {
        String xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE hierarchy [<!ENTITY ext SYSTEM "file:///etc/passwd">]>
            <hierarchy><node text="&ext;" clickable="true" bounds="[0,0][10,10]"/></hierarchy>
            """;
        // disallow-doctype-decl로 파싱 자체가 거부 → 빈 결과
        assertTrue(AndroidUiParser.parse(xxe, 200).nodes().isEmpty());
    }

    @Test
    void parseBoundsParsesValidAndRejectsInvalid() {
        assertArrayEquals(new int[]{0, 100, 1080, 200}, AndroidUiParser.parseBounds("[0,100][1080,200]"));
        assertArrayEquals(new int[]{-10, -5, 30, 40}, AndroidUiParser.parseBounds("[-10,-5][30,40]"));
        assertNull(AndroidUiParser.parseBounds(""));
        assertNull(AndroidUiParser.parseBounds(null));
        assertNull(AndroidUiParser.parseBounds("[a,b][c,d]"));
    }

    @Test
    void toCompactListFormatsElements() {
        String output = AndroidUiParser.toCompactList(AndroidUiParser.parse(SAMPLE_XML, 200));
        assertTrue(output.contains("[0] text=\"로그인\" id=btn_login class=Button clickable"), output);
        assertTrue(output.contains("center=(540,1460)"), output);
        assertTrue(output.contains("(1 non-interactive nodes hidden)"), output);
    }

    @Test
    void toCompactListGuidesFallbackWhenEmpty() {
        String output = AndroidUiParser.toCompactList(ParseResult.empty());
        assertTrue(output.contains("android_screenshot"), output);
    }
}
