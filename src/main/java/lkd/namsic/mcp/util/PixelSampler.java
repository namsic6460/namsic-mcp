package lkd.namsic.mcp.util;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * 스크린샷 BufferedImage에서 좌표별 픽셀 색/영역 평균색을 추출한다.
 * browser_sample_pixels(viewport CSS 픽셀)와 android_sample_pixels(디바이스 픽셀)가 공유한다.
 */
public final class PixelSampler {

    private PixelSampler() {
    }

    public record Point(int x, int y) {
    }

    /** "x1,y1;x2,y2" 형식 파싱. 빈 입력은 빈 리스트. 형식 오류는 IllegalArgumentException. */
    public static List<Point> parsePoints(final String points) {
        final List<Point> result = new ArrayList<>();
        if (points == null || points.isBlank()) {
            return result;
        }
        for (final String token : points.split(";")) {
            final String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final String[] xy = trimmed.split(",");
            if (xy.length != 2) {
                throw new IllegalArgumentException("invalid point '" + trimmed + "' (expected 'x,y')");
            }
            try {
                result.add(new Point(Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())));
            } catch (final NumberFormatException ex) {
                throw new IllegalArgumentException("invalid point '" + trimmed + "' (expected integer x,y)");
            }
        }
        return result;
    }

    /** "x,y,w,h" 형식 파싱. 빈 입력은 null. w/h는 양수여야 한다. */
    public static int[] parseRect(final String rect) {
        if (rect == null || rect.isBlank()) {
            return null;
        }
        final String[] parts = rect.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("invalid rect '" + rect + "' (expected 'x,y,w,h')");
        }
        final int[] vals = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                vals[i] = Integer.parseInt(parts[i].trim());
            } catch (final NumberFormatException ex) {
                throw new IllegalArgumentException("invalid rect '" + rect + "' (expected integers)");
            }
        }
        if (vals[2] <= 0 || vals[3] <= 0) {
            throw new IllegalArgumentException("rect w/h must be positive: " + rect);
        }
        return vals;
    }

    /**
     * points의 각 픽셀 RGBA와 rect(있으면)의 평균 RGB를 JSON 문자열로 반환한다.
     * 이미지 밖 좌표는 IllegalArgumentException.
     */
    public static String sampleToJson(final BufferedImage image, final List<Point> points, final int[] rect) {
        final StringBuilder sb = new StringBuilder();
        sb.append("{\"imageWidth\":").append(image.getWidth())
            .append(",\"imageHeight\":").append(image.getHeight())
            .append(",\"points\":[");
        for (int i = 0; i < points.size(); i++) {
            final Point p = points.get(i);
            if (p.x() < 0 || p.x() >= image.getWidth() || p.y() < 0 || p.y() >= image.getHeight()) {
                throw new IllegalArgumentException("point (" + p.x() + "," + p.y()
                    + ") is outside the image " + image.getWidth() + "x" + image.getHeight());
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"x\":").append(p.x()).append(",\"y\":").append(p.y())
                .append(",\"rgbaHex\":\"").append(rgbaHex(image.getRGB(p.x(), p.y()))).append("\"}");
        }
        sb.append(']');
        if (rect != null) {
            final int x0 = Math.max(0, rect[0]);
            final int y0 = Math.max(0, rect[1]);
            final int x1 = Math.min(image.getWidth(), rect[0] + rect[2]);
            final int y1 = Math.min(image.getHeight(), rect[1] + rect[3]);
            if (x0 >= x1 || y0 >= y1) {
                throw new IllegalArgumentException("rect is outside the image "
                    + image.getWidth() + "x" + image.getHeight());
            }
            long r = 0;
            long g = 0;
            long b = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    final int argb = image.getRGB(x, y);
                    r += (argb >> 16) & 0xFF;
                    g += (argb >> 8) & 0xFF;
                    b += argb & 0xFF;
                }
            }
            final long count = (long) (x1 - x0) * (y1 - y0);
            sb.append(",\"rectAverage\":{\"x\":").append(x0).append(",\"y\":").append(y0)
                .append(",\"w\":").append(x1 - x0).append(",\"h\":").append(y1 - y0)
                .append(",\"rgbHex\":\"")
                .append(String.format("#%02X%02X%02X", r / count, g / count, b / count))
                .append("\"}");
        }
        sb.append('}');
        return sb.toString();
    }

    static String rgbaHex(final int argb) {
        return String.format("#%02X%02X%02X%02X",
            (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }
}
