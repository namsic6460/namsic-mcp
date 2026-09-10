package lkd.namsic.mcp.chrome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * chrome-uia-host.ps1 이 뱉는 W/T 레코드를 창·탭 목록으로 파싱하고 제목 매칭을 수행한다.
 * 스크립트는 단순 전송 계층이므로 매칭·모호성 판정은 전부 여기에 둔다 (단위 테스트 대상).
 */
public record ChromeTabIndex(List<ChromeWindow> windows, List<String> errors) {

    private static final String SEP = "\t";

    public ChromeTabIndex {
        windows = List.copyOf(windows);
        errors = List.copyOf(errors);
    }

    /** 탭 스트립의 탭 하나. {@code name} 은 크롬 접근성 라벨(페이지 제목 + 크롬이 붙이는 접미사)이다. */
    public record ChromeTab(long hwnd, int index, boolean selected, String name) {
    }

    public record ChromeWindow(long hwnd, String visualState, String title, List<ChromeTab> tabs) {

        public ChromeWindow {
            tabs = List.copyOf(tabs);
        }

        public boolean minimized() {
            return "Minimized".equalsIgnoreCase(this.visualState);
        }
    }

    /** 파싱 중 탭을 모으기 위한 가변 누적기. */
    private static final class WindowAcc {

        private final long hwnd;
        private final String visualState;
        private final String title;
        private final List<ChromeTab> tabs = new ArrayList<>();

        private WindowAcc(final long hwnd, final String visualState, final String title) {
            this.hwnd = hwnd;
            this.visualState = visualState;
            this.title = title;
        }

        private ChromeWindow toWindow() {
            return new ChromeWindow(this.hwnd, this.visualState, this.title, this.tabs);
        }
    }

    public static ChromeTabIndex parse(final List<String> lines) {
        final List<ChromeWindow> windows = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        if (lines == null) {
            return new ChromeTabIndex(windows, errors);
        }

        WindowAcc current = null;
        for (final String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            final String[] parts = line.split(SEP, -1);
            final String kind = parts[0];

            if ("W".equals(kind)) {
                if (current != null) {
                    windows.add(current.toWindow());
                    current = null;
                }
                if (parts.length < 4) {
                    errors.add("malformed window record: " + line);
                    continue;
                }
                final Long hwnd = parseLong(parts[1]);
                if (hwnd == null) {
                    errors.add("malformed window handle: " + line);
                    continue;
                }
                current = new WindowAcc(hwnd, parts[2], parts[3]);
                continue;
            }

            if ("T".equals(kind)) {
                if (current == null) {
                    errors.add("tab record before any window record: " + line);
                    continue;
                }
                if (parts.length < 5) {
                    errors.add("malformed tab record: " + line);
                    continue;
                }
                final Integer index = parseInt(parts[2]);
                if (index == null) {
                    errors.add("malformed tab index: " + line);
                    continue;
                }
                current.tabs.add(new ChromeTab(current.hwnd, index, "1".equals(parts[3]), parts[4]));
                continue;
            }

            if ("ERR".equals(kind)) {
                errors.add(parts.length > 1 ? parts[1] : line);
                continue;
            }

            errors.add("unrecognized record: " + line);
        }
        if (current != null) {
            windows.add(current.toWindow());
        }
        return new ChromeTabIndex(windows, errors);
    }

    public List<ChromeTab> allTabs() {
        final List<ChromeTab> all = new ArrayList<>();
        for (final ChromeWindow window : this.windows) {
            all.addAll(window.tabs());
        }
        return all;
    }

    public Optional<ChromeWindow> window(final long hwnd) {
        return this.windows.stream().filter(w -> w.hwnd() == hwnd).findFirst();
    }

    /**
     * 제목으로 탭을 찾는다. 크롬 접근성 라벨은 "페이지 제목 - 그룹명 - 메모리 사용량 - 21.5MB" 꼴이라
     * 완전 일치 → 접두 일치 → 부분 일치 순으로 좁혀야 페이지 제목만 줘도 유일하게 잡힌다.
     * 같은 단계에서 여러 개가 걸리면 전부 돌려주고, 모호성 처리는 호출자가 한다.
     */
    public List<ChromeTab> match(final String query) {
        final String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        final List<ChromeTab> exact = new ArrayList<>();
        final List<ChromeTab> prefix = new ArrayList<>();
        final List<ChromeTab> contains = new ArrayList<>();
        for (final ChromeTab tab : this.allTabs()) {
            final String name = tab.name().toLowerCase(Locale.ROOT);
            if (name.equals(needle)) {
                exact.add(tab);
            } else if (name.startsWith(needle)) {
                prefix.add(tab);
            } else if (name.contains(needle)) {
                contains.add(tab);
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        if (!prefix.isEmpty()) {
            return prefix;
        }
        return contains;
    }

    /** 도구 응답용 사람이 읽는 목록. */
    public String render() {
        final StringBuilder sb = new StringBuilder();
        if (this.windows.isEmpty()) {
            sb.append("No Chrome windows found. Is Chrome running?");
        } else {
            sb.append("Chrome windows (").append(this.windows.size()).append("):\n");
            for (final ChromeWindow window : this.windows) {
                sb.append("- hwnd=").append(window.hwnd())
                    .append(" state=").append(window.visualState())
                    .append(" \"").append(window.title()).append("\"\n");
                for (final ChromeTab tab : window.tabs()) {
                    sb.append("    [").append(tab.index()).append(']')
                        .append(tab.selected() ? " * " : "   ")
                        .append(tab.name()).append('\n');
                }
            }
            sb.append("\n'*' marks the active tab of its window — every other tab is hidden "
                + "(requestAnimationFrame frozen, timers throttled to ~1Hz).\n"
                + "Activate one with chrome_activate_tab, by title or by hwnd + tabIndex.");
        }
        if (!this.errors.isEmpty()) {
            sb.append("\n\nWarnings:");
            for (final String error : this.errors) {
                sb.append("\n- ").append(error);
            }
        }
        return sb.toString();
    }

    private static Long parseLong(final String text) {
        try {
            return Long.valueOf(text.trim());
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseInt(final String text) {
        try {
            return Integer.valueOf(text.trim());
        } catch (final NumberFormatException ex) {
            return null;
        }
    }
}
