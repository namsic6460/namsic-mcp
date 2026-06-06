package lkd.namsic.mcp.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixelSamplerTest {

    @Test
    void parsePointsParsesSemicolonSeparatedList() {
        List<PixelSampler.Point> points = PixelSampler.parsePoints(" 10,20 ; 30,40 ;");
        assertEquals(2, points.size());
        assertEquals(new PixelSampler.Point(10, 20), points.getFirst());
        assertEquals(new PixelSampler.Point(30, 40), points.get(1));
        assertTrue(PixelSampler.parsePoints(null).isEmpty());
        assertTrue(PixelSampler.parsePoints("  ").isEmpty());
    }

    @Test
    void parsePointsRejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> PixelSampler.parsePoints("10"));
        assertThrows(IllegalArgumentException.class, () -> PixelSampler.parsePoints("10,20,30"));
        assertThrows(IllegalArgumentException.class, () -> PixelSampler.parsePoints("a,b"));
    }

    @Test
    void parseRectParsesAndValidates() {
        assertNull(PixelSampler.parseRect(null));
        assertNull(PixelSampler.parseRect(" "));
        int[] rect = PixelSampler.parseRect("1, 2, 3, 4");
        assertEquals(1, rect[0]);
        assertEquals(2, rect[1]);
        assertEquals(3, rect[2]);
        assertEquals(4, rect[3]);
        assertThrows(IllegalArgumentException.class, () -> PixelSampler.parseRect("1,2,3"));
        assertThrows(IllegalArgumentException.class, () -> PixelSampler.parseRect("1,2,0,4"));
        assertThrows(IllegalArgumentException.class, () -> PixelSampler.parseRect("a,b,c,d"));
    }

    @Test
    void sampleToJsonReturnsPointColorsAndRectAverage() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000); // 불투명 빨강
        image.setRGB(1, 0, 0xFF00FF00); // 불투명 초록

        String json = PixelSampler.sampleToJson(image,
            List.of(new PixelSampler.Point(0, 0), new PixelSampler.Point(1, 0)),
            new int[]{0, 0, 2, 1});

        assertTrue(json.contains("\"imageWidth\":4"), json);
        assertTrue(json.contains("\"rgbaHex\":\"#FF0000FF\""), json);
        assertTrue(json.contains("\"rgbaHex\":\"#00FF00FF\""), json);
        // (255,0,0)과 (0,255,0)의 평균 → (127,127,0)
        assertTrue(json.contains("\"rgbHex\":\"#7F7F00\""), json);
    }

    @Test
    void sampleToJsonRejectsOutOfBoundsPointAndRect() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        assertThrows(IllegalArgumentException.class, () -> PixelSampler.sampleToJson(
            image, List.of(new PixelSampler.Point(4, 0)), null));
        assertThrows(IllegalArgumentException.class, () -> PixelSampler.sampleToJson(
            image, List.of(), new int[]{10, 10, 2, 2}));
    }
}
