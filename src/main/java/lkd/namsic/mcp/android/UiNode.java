package lkd.namsic.mcp.android;

/**
 * uiautomator dump XML의 한 요소를 LLM 친화 형태로 압축한 표현.
 * index는 {@code android_dump_ui}가 노출한 목록 순서이며 {@code android_tap}의 elementIndex 타겟으로 쓰인다.
 */
public record UiNode(
    int index,
    String text,
    String resourceId,
    String contentDesc,
    String className,
    boolean clickable,
    int left,
    int top,
    int right,
    int bottom,
    int centerX,
    int centerY
) {

    /** 한 줄 압축 표현. 빈 필드는 생략해 토큰을 절약한다. */
    String toCompactLine() {
        final StringBuilder sb = new StringBuilder(64);
        sb.append('[').append(this.index).append(']');
        if (!this.text.isEmpty()) {
            sb.append(" text=\"").append(this.text).append('"');
        }
        if (!this.contentDesc.isEmpty()) {
            sb.append(" desc=\"").append(this.contentDesc).append('"');
        }
        if (!this.resourceId.isEmpty()) {
            sb.append(" id=").append(shortResourceId(this.resourceId));
        }
        if (!this.className.isEmpty()) {
            sb.append(" class=").append(shortClassName(this.className));
        }
        if (this.clickable) {
            sb.append(" clickable");
        }
        sb.append(" bounds=[").append(this.left).append(',').append(this.top)
            .append("][").append(this.right).append(',').append(this.bottom).append(']');
        sb.append(" center=(").append(this.centerX).append(',').append(this.centerY).append(')');
        return sb.toString();
    }

    /** "com.app:id/login" → "login" (표시용 — selector 매칭은 전체 resourceId contains로 동작). */
    static String shortResourceId(final String resourceId) {
        final int idx = resourceId.lastIndexOf('/');
        return idx >= 0 && idx < resourceId.length() - 1 ? resourceId.substring(idx + 1) : resourceId;
    }

    /** "android.widget.Button" → "Button". */
    static String shortClassName(final String className) {
        final int idx = className.lastIndexOf('.');
        return idx >= 0 && idx < className.length() - 1 ? className.substring(idx + 1) : className;
    }
}
