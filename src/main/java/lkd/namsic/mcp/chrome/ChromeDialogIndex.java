package lkd.namsic.mcp.chrome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * chrome-uia-host.ps1 이 뱉는 D/X/B 레코드를 다이얼로그 목록으로 파싱하고 버튼 선택을 수행한다.
 * 스크립트는 단순 전송 계층이므로 버튼 선택·모호성 판정은 전부 여기에 둔다 (단위 테스트 대상).
 */
public record ChromeDialogIndex(List<ChromeDialog> dialogs, List<String> errors) {

    private static final String SEP = "\t";

    public ChromeDialogIndex {
        dialogs = List.copyOf(dialogs);
        errors = List.copyOf(errors);
    }

    public record DialogButton(int index, boolean enabled, String name) {
    }

    /** 크롬 창 하나에 떠 있는 다이얼로그 하나. {@code texts} 는 제목을 뺀 본문 라벨들이다. */
    public record ChromeDialog(long hwnd, int index, String title, List<String> texts, List<DialogButton> buttons) {

        public ChromeDialog {
            texts = List.copyOf(texts);
            buttons = List.copyOf(buttons);
        }

        /**
         * 수락 버튼. 크롬은 버튼 행을 [수락, 취소] 순으로 둔다 (confirm 실측: 확인/취소).
         * 버튼 이름은 크롬 UI 언어를 따르므로 이름이 아니라 위치로 고른다.
         */
        public Optional<DialogButton> acceptButton() {
            return this.buttons.isEmpty() ? Optional.empty() : Optional.of(this.buttons.getFirst());
        }

        /** 취소 버튼. alert 처럼 버튼이 하나뿐이면 그 버튼이 곧 닫기다. */
        public Optional<DialogButton> cancelButton() {
            return this.buttons.isEmpty() ? Optional.empty() : Optional.of(this.buttons.getLast());
        }

        /** 이름으로 버튼을 찾는다. 완전 일치가 있으면 그것만, 없으면 부분 일치 전부. */
        public List<DialogButton> matchButtons(final String query) {
            final String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) {
                return List.of();
            }
            final List<DialogButton> exact = new ArrayList<>();
            final List<DialogButton> contains = new ArrayList<>();
            for (final DialogButton button : this.buttons) {
                final String name = button.name().toLowerCase(Locale.ROOT);
                if (name.equals(needle)) {
                    exact.add(button);
                } else if (name.contains(needle)) {
                    contains.add(button);
                }
            }
            return exact.isEmpty() ? contains : exact;
        }

        public String describe() {
            final StringBuilder sb = new StringBuilder();
            sb.append("- hwnd=").append(this.hwnd)
                .append(" dialogIndex=").append(this.index)
                .append(" \"").append(this.title).append("\"\n");
            for (final String text : this.texts) {
                sb.append("    ").append(text).append('\n');
            }
            sb.append("    buttons:");
            for (final DialogButton button : this.buttons) {
                sb.append(" [").append(button.name()).append(button.enabled() ? "]" : " (disabled)]");
            }
            return sb.append('\n').toString();
        }
    }

    /** 파싱 중 본문·버튼을 모으기 위한 가변 누적기. */
    private static final class DialogAcc {

        private final long hwnd;
        private final int index;
        private final String title;
        private final List<String> texts = new ArrayList<>();
        private final List<DialogButton> buttons = new ArrayList<>();

        private DialogAcc(final long hwnd, final int index, final String title) {
            this.hwnd = hwnd;
            this.index = index;
            this.title = title;
        }

        private ChromeDialog toDialog() {
            return new ChromeDialog(this.hwnd, this.index, this.title, this.texts, this.buttons);
        }
    }

    public static ChromeDialogIndex parse(final List<String> lines) {
        final List<ChromeDialog> dialogs = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        if (lines == null) {
            return new ChromeDialogIndex(dialogs, errors);
        }

        DialogAcc current = null;
        for (final String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            final String[] parts = line.split(SEP, -1);
            final String kind = parts[0];

            if ("D".equals(kind)) {
                if (current != null) {
                    dialogs.add(current.toDialog());
                    current = null;
                }
                if (parts.length < 4) {
                    errors.add("malformed dialog record: " + line);
                    continue;
                }
                final Long hwnd = parseLong(parts[1]);
                final Integer index = parseInt(parts[2]);
                if (hwnd == null || index == null) {
                    errors.add("malformed dialog address: " + line);
                    continue;
                }
                current = new DialogAcc(hwnd, index, parts[3]);
                continue;
            }

            if ("X".equals(kind)) {
                if (current == null) {
                    errors.add("text record before any dialog record: " + line);
                    continue;
                }
                if (parts.length < 4) {
                    errors.add("malformed text record: " + line);
                    continue;
                }
                current.texts.add(parts[3]);
                continue;
            }

            if ("B".equals(kind)) {
                if (current == null) {
                    errors.add("button record before any dialog record: " + line);
                    continue;
                }
                if (parts.length < 6) {
                    errors.add("malformed button record: " + line);
                    continue;
                }
                final Integer index = parseInt(parts[3]);
                if (index == null) {
                    errors.add("malformed button index: " + line);
                    continue;
                }
                current.buttons.add(new DialogButton(index, "1".equals(parts[4]), parts[5]));
                continue;
            }

            if ("ERR".equals(kind)) {
                errors.add(parts.length > 1 ? parts[1] : line);
                continue;
            }

            errors.add("unrecognized record: " + line);
        }
        if (current != null) {
            dialogs.add(current.toDialog());
        }
        return new ChromeDialogIndex(dialogs, errors);
    }

    /** hwnd / dialogIndex 로 좁힌 다이얼로그들. null 인 조건은 무시한다. */
    public List<ChromeDialog> select(final Long hwnd, final Integer dialogIndex) {
        return this.dialogs.stream()
            .filter(dialog -> hwnd == null || dialog.hwnd() == hwnd)
            .filter(dialog -> dialogIndex == null || dialog.index() == dialogIndex)
            .toList();
    }

    /** 도구 응답용 사람이 읽는 목록. */
    public String render() {
        final StringBuilder sb = new StringBuilder();
        if (this.dialogs.isEmpty()) {
            sb.append("No Chrome dialog is open.\n"
                + "A dialog raised by a background tab is not shown until that tab is selected: if a browser "
                + "tool is hanging, bring the tab it was driving to the front with chrome_activate_tab "
                + "and check again.");
        } else {
            sb.append("Chrome dialogs (").append(this.dialogs.size()).append("):\n");
            for (final ChromeDialog dialog : this.dialogs) {
                sb.append(dialog.describe());
            }
            sb.append("\nPress a button with chrome_dismiss_dialog (action=accept presses the first button, "
                + "action=cancel the last).");
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
