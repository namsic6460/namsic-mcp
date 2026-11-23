# 크롬 탭 제어용 UI Automation 상주 호스트.
#
# stdin 으로 탭 구분 명령을 한 줄씩 받아, 응답 줄들을 stdout 에 쓰고 마지막에 <<<END>>> 를 출력한다.
# Add-Type / 어셈블리 로드 비용을 프로세스 수명 동안 한 번만 지불하기 위해 상주 방식으로 동작한다.
#
# 명령:
#   PING                                  -> PONG
#   LIST                                  -> W/T 레코드 (아래 형식)
#   ACTIVATE <hwnd> <tabIndex> <toFront>  -> OK <탭 이름> | ERR <사유>
#   DIALOGS                               -> D/X/B 레코드 (아래 형식)
#   DIALOG_CLICK <hwnd> <dialogIndex> <buttonIndex> -> OK <버튼 이름> | ERR <사유>
#   EXIT                                  -> 종료
#
# 레코드 형식 (탭 구분):
#   W <hwnd> <windowVisualState> <windowTitle>
#   T <hwnd> <tabIndex> <selected 0|1> <tabName>
#   D <hwnd> <dialogIndex> <dialogTitle>
#   X <hwnd> <dialogIndex> <text>                         (다이얼로그 본문 라벨)
#   B <hwnd> <dialogIndex> <buttonIndex> <enabled 0|1> <buttonName>
#
# 탭 매칭/모호성 판정과 버튼 선택은 전부 Java 쪽(ChromeTabIndex, ChromeDialogIndex)에 있다
# — 이 스크립트는 단순 전송 계층이다.

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class ChromeA11y {
    [DllImport("user32.dll")]
    private static extern IntPtr SendMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    // WM_GETOBJECT(0x003D) + OBJID_CLIENT(0xFFFFFFFC): 크롬은 접근성 클라이언트가 붙기 전까지
    // UIA 트리를 만들지 않는다. 이 메시지로 트리 생성을 유도하지 않으면 탭 스트립이 보이지 않는다.
    public static void Wake(IntPtr hWnd) {
        SendMessage(hWnd, 0x003D, IntPtr.Zero, new IntPtr(unchecked((int)0xFFFFFFFC)));
    }
}
'@

$AE = [System.Windows.Automation.AutomationElement]
$CT = [System.Windows.Automation.ControlType]
$TS = [System.Windows.Automation.TreeScope]
# ControlView 는 크롬 탭 스트립까지 내려가지 못한다 (중간 Pane 이 걸러짐) — RawView 를 써야 한다.
$TW = [System.Windows.Automation.TreeWalker]::RawViewWalker
$SIP = [System.Windows.Automation.SelectionItemPattern]::Pattern
$WP = [System.Windows.Automation.WindowPattern]::Pattern
$WVS = [System.Windows.Automation.WindowVisualState]
$IVP = [System.Windows.Automation.InvokePattern]::Pattern

# 변수명 주의: PowerShell 변수는 대소문자를 구분하지 않는다. 루프 변수 $tab 과 충돌하므로
# 구분자 변수를 $TAB 으로 두면 안 된다.
$SEP = "`t"
$STRIP_MAX_DEPTH = 8
$TAB_ITEM_MAX_DEPTH = 8
$DIALOG_MAX_DEPTH = 10
$DIALOG_CONTENT_MAX_DEPTH = 10
$DIALOG_ROOT_MAX_CLIMB = 4
# 크롬 Views 다이얼로그(alert/confirm/prompt/beforeunload, 그 외 네이티브 확인창)의 공통 컨테이너.
$DIALOG_CLIENT_CLASS = 'DialogClientView'
$DIALOG_BUTTON_ROW_CLASS = 'DialogClientView::ButtonRowContainer'
$DIALOG_ROOT_CLASS = 'RootView'
$CHROME_PROCESS = 'chrome'

$script:processNameCache = @{}

function Emit($line) {
    [Console]::Out.WriteLine($line)
}

function Clean($text) {
    if ($null -eq $text) { return '' }
    return ($text -replace "[`t`r`n]", ' ')
}

function Get-ProcessNameCached($processId) {
    if ($script:processNameCache.ContainsKey($processId)) { return $script:processNameCache[$processId] }
    $name = ''
    try { $name = (Get-Process -Id $processId -ErrorAction Stop).ProcessName } catch { $name = '' }
    $script:processNameCache[$processId] = $name
    return $name
}

function Get-ChromeWindows {
    # Chrome_WidgetWin_1 은 Electron/CEF 앱(Docker Desktop 등)도 쓰는 클래스라
    # 프로세스 이름까지 확인해야 실제 크롬 창만 남는다.
    $cond = New-Object System.Windows.Automation.PropertyCondition($AE::ClassNameProperty, 'Chrome_WidgetWin_1')
    $found = $AE::RootElement.FindAll($TS::Children, $cond)
    $windows = @()
    foreach ($candidate in $found) {
        if ($candidate.Current.ControlType -ne $CT::Window) { continue }
        if ([string]::IsNullOrEmpty($candidate.Current.Name)) { continue }
        if ((Get-ProcessNameCached $candidate.Current.ProcessId) -ne $CHROME_PROCESS) { continue }
        $windows += $candidate
    }
    return $windows
}

# 탭 스트립 = TabItem 을 품은 첫 번째 Tab 컨트롤.
#   가로 탭: HorizontalTabStripRegionViewOld > TabStrip > Tab[TabContainerImpl] > TabItem
#   세로 탭: Tab[VerticalTabStripRegionView] > TabStripView > ScrollView > Viewport
#            > (Pinned|Unpinned)TabContainerView > [TabGroupView >] TabItem[TabView]
# 두 영역은 모드와 무관하게 항상 트리에 함께 존재하고 비활성 쪽은 탭 없이 비어 있으므로,
# 첫 Tab 컨트롤을 그대로 쓰면 안 되고 실제 탭이 들어 있는지 확인해야 한다.
# 웹 콘텐츠(Document)의 ARIA 탭을 잘못 잡지 않도록 Document 하위로는 내려가지 않는다.
function Find-TabStrip($element, $depth) {
    if ($depth -le 0) { return $null }
    $child = $TW.GetFirstChild($element)
    while ($null -ne $child) {
        $type = $child.Current.ControlType
        if ($type -eq $CT::Tab) {
            if (@(Get-TabItems $child).Count -gt 0) { return $child }
        } elseif ($type -ne $CT::Document) {
            $found = Find-TabStrip $child ($depth - 1)
            if ($null -ne $found) { return $found }
        }
        $child = $TW.GetNextSibling($child)
    }
    return $null
}

# a11y 트리가 아직 안 깨어 있으면 한 번 더 유도하고 재시도한다.
function Resolve-TabStrip($window) {
    $strip = Find-TabStrip $window $STRIP_MAX_DEPTH
    if ($null -ne $strip) { return $strip }
    [ChromeA11y]::Wake([IntPtr]$window.Current.NativeWindowHandle)
    Start-Sleep -Milliseconds 700
    return Find-TabStrip $window $STRIP_MAX_DEPTH
}

# 스트립 하위의 TabItem 을 트리 순서(= 화면 순서, 세로 탭은 고정 탭이 먼저)대로 모은다.
# 세로 탭은 스크롤 뷰·탭 그룹 뷰 안에 중첩되어 있으므로 직계 자식만 봐서는 안 된다.
function Get-TabItems($strip) {
    $items = New-Object System.Collections.ArrayList
    Add-TabItems $strip $TAB_ITEM_MAX_DEPTH $items
    return $items.ToArray()
}

function Add-TabItems($element, $depth, $items) {
    if ($depth -le 0) { return }
    $child = $TW.GetFirstChild($element)
    while ($null -ne $child) {
        $type = $child.Current.ControlType
        if ($type -eq $CT::TabItem) { [void]$items.Add($child) }
        elseif ($type -ne $CT::Document) { Add-TabItems $child ($depth - 1) $items }
        $child = $TW.GetNextSibling($child)
    }
}

# 창 하위의 DialogClientView 를 모은다. 실측 구조 (JS confirm):
#   BrowserRootView > Window[RootView, name=제목] > NonClientView > BubbleFrameView
#     > DialogClientView > ... Text[Label] (본문) / ButtonRowContainer > Button[MdTextButton]
# 버튼 행의 순서는 [수락, 취소] 다 (confirm 실측: 확인/취소).
# 정보 표시줄(InfoBar)의 버튼은 DialogClientView 밖이라 걸리지 않는다 — 확장의 "디버깅 중" 표시줄
# '취소' 를 잘못 누르면 디버거가 떨어지므로 이 구분이 중요하다.
# 웹 콘텐츠·탭 스트립·툴바 하위는 다이얼로그가 있을 수 없고 노드가 많으므로 내려가지 않는다.
function Add-DialogClients($element, $depth, $found) {
    if ($depth -le 0) { return }
    $child = $TW.GetFirstChild($element)
    while ($null -ne $child) {
        $type = $child.Current.ControlType
        if ($child.Current.ClassName -eq $DIALOG_CLIENT_CLASS) { [void]$found.Add($child) }
        elseif ($type -ne $CT::Document -and $type -ne $CT::Tab -and $type -ne $CT::ToolBar) {
            Add-DialogClients $child ($depth - 1) $found
        }
        $child = $TW.GetNextSibling($child)
    }
}

# 번역·비밀번호 같은 비차단 말풍선(버블)도 DialogClientView 를 쓴다. 버블은 버튼을 자체 뷰에 두어
# ButtonRowContainer 가 비어 있고, 응답을 요구하는 다이얼로그는 거기에 확인/취소 버튼을 둔다 (실측).
# 그래서 "버튼 행에 버튼이 있는 것"만 다이얼로그로 취급한다.
function Get-DialogButtons($client) {
    $child = $TW.GetFirstChild($client)
    while ($null -ne $child) {
        if ($child.Current.ClassName -eq $DIALOG_BUTTON_ROW_CLASS) {
            return @(Get-ElementsOfType $child $CT::Button)
        }
        $child = $TW.GetNextSibling($child)
    }
    return @()
}

function Get-DialogClients($window) {
    $found = New-Object System.Collections.ArrayList
    Add-DialogClients $window $DIALOG_MAX_DEPTH $found
    $dialogs = @()
    foreach ($client in $found) {
        if (@(Get-DialogButtons $client).Count -gt 0) { $dialogs += $client }
    }
    return $dialogs
}

# 제목과 본문 라벨은 DialogClientView 의 형제(BubbleFrameView 쪽)에도 있으므로,
# 가장 가까운 RootView 조상을 다이얼로그 루트로 본다. 없으면 DialogClientView 자신.
function Get-DialogRoot($client) {
    $current = $client
    for ($i = 0; $i -lt $DIALOG_ROOT_MAX_CLIMB; $i++) {
        $parent = $TW.GetParent($current)
        if ($null -eq $parent) { break }
        if ($parent.Current.ClassName -eq $DIALOG_ROOT_CLASS) { return $parent }
        $current = $parent
    }
    return $client
}

function Add-ElementsOfType($element, $wantedType, $depth, $items) {
    if ($depth -le 0) { return }
    $child = $TW.GetFirstChild($element)
    while ($null -ne $child) {
        $type = $child.Current.ControlType
        if ($type -eq $wantedType) { [void]$items.Add($child) }
        elseif ($type -ne $CT::Document) { Add-ElementsOfType $child $wantedType ($depth - 1) $items }
        $child = $TW.GetNextSibling($child)
    }
}

function Get-ElementsOfType($element, $wantedType) {
    $items = New-Object System.Collections.ArrayList
    Add-ElementsOfType $element $wantedType $DIALOG_CONTENT_MAX_DEPTH $items
    return $items.ToArray()
}

# 다이얼로그가 떠 있는 동안 크롬은 BrowserRootView 하위를 다이얼로그만 남기고 가린다 (실측).
# 그래서 탭 스트립이 안 보이는 원인이 다이얼로그일 수 있고, 그 경우 조치 방법을 알려준다.
function Get-StripMissingMessage($window) {
    $hwnd = $window.Current.NativeWindowHandle
    if (@(Get-DialogClients $window).Count -gt 0) {
        return "tab strip of hwnd $hwnd is hidden behind an open dialog - see chrome_list_dialogs / chrome_dismiss_dialog"
    }
    return "tab strip not found for hwnd $hwnd"
}

function Invoke-Dialogs {
    foreach ($window in @(Get-ChromeWindows)) {
        $hwnd = $window.Current.NativeWindowHandle
        [ChromeA11y]::Wake([IntPtr]$hwnd)
        $dialogIndex = 0
        foreach ($client in @(Get-DialogClients $window)) {
            $root = Get-DialogRoot $client
            $title = Clean $root.Current.Name
            Emit "D$SEP$hwnd$SEP$dialogIndex$SEP$title"
            foreach ($label in @(Get-ElementsOfType $root $CT::Text)) {
                $text = Clean $label.Current.Name
                if ($text.Length -eq 0 -or $text -eq $title) { continue }
                Emit "X$SEP$hwnd$SEP$dialogIndex$SEP$text"
            }
            $buttonIndex = 0
            foreach ($button in @(Get-DialogButtons $client)) {
                $enabled = 0
                if ($button.Current.IsEnabled) { $enabled = 1 }
                Emit ("B$SEP$hwnd$SEP$dialogIndex$SEP$buttonIndex$SEP$enabled$SEP" + (Clean $button.Current.Name))
                $buttonIndex++
            }
            $dialogIndex++
        }
    }
}

function Invoke-DialogClick($hwnd, $dialogIndex, $buttonIndex) {
    $window = $null
    foreach ($candidate in @(Get-ChromeWindows)) {
        if ($candidate.Current.NativeWindowHandle -eq $hwnd) { $window = $candidate; break }
    }
    if ($null -eq $window) {
        Emit "ERR${SEP}no chrome window with hwnd $hwnd"
        return
    }
    $clients = @(Get-DialogClients $window)
    if ($dialogIndex -lt 0 -or $dialogIndex -ge $clients.Count) {
        Emit "ERR${SEP}dialog index $dialogIndex out of range (window has $($clients.Count) dialogs)"
        return
    }
    $buttons = @(Get-DialogButtons $clients[$dialogIndex])
    if ($buttonIndex -lt 0 -or $buttonIndex -ge $buttons.Count) {
        Emit "ERR${SEP}button index $buttonIndex out of range (dialog has $($buttons.Count) buttons)"
        return
    }
    $target = $buttons[$buttonIndex]
    # 누르면 다이얼로그와 함께 요소가 사라지므로 이름은 먼저 읽어 둔다.
    $name = Clean $target.Current.Name
    try {
        $target.GetCurrentPattern($IVP).Invoke()
    } catch {
        Emit "ERR${SEP}failed to press button '$name': $($_.Exception.Message)"
        return
    }
    Emit "OK$SEP$name"
}

function Get-VisualState($window) {
    try { return $window.GetCurrentPattern($WP).Current.WindowVisualState.ToString() }
    catch { return 'Unknown' }
}

function Invoke-List {
    foreach ($window in @(Get-ChromeWindows)) {
        $hwnd = $window.Current.NativeWindowHandle
        [ChromeA11y]::Wake([IntPtr]$hwnd)
        Emit ("W$SEP$hwnd$SEP" + (Get-VisualState $window) + $SEP + (Clean $window.Current.Name))

        $strip = Resolve-TabStrip $window
        if ($null -eq $strip) {
            Emit ("ERR$SEP" + (Get-StripMissingMessage $window))
            continue
        }
        $index = 0
        foreach ($item in @(Get-TabItems $strip)) {
            $selected = 0
            try { if ($item.GetCurrentPattern($SIP).Current.IsSelected) { $selected = 1 } } catch { }
            Emit ("T$SEP$hwnd$SEP$index$SEP$selected$SEP" + (Clean $item.Current.Name))
            $index++
        }
    }
}

function Invoke-Activate($hwnd, $tabIndex, $toFront) {
    $window = $null
    foreach ($candidate in @(Get-ChromeWindows)) {
        if ($candidate.Current.NativeWindowHandle -eq $hwnd) { $window = $candidate; break }
    }
    if ($null -eq $window) {
        Emit "ERR${SEP}no chrome window with hwnd $hwnd"
        return
    }

    # 최소화된 창은 활성 탭조차 visibilityState=hidden 이라, 복원하지 않으면 탭 선택이 무의미하다.
    if ((Get-VisualState $window) -eq 'Minimized') {
        try {
            $window.GetCurrentPattern($WP).SetWindowVisualState($WVS::Normal)
            Start-Sleep -Milliseconds 300
        } catch {
            Emit "ERR${SEP}failed to restore minimized window: $($_.Exception.Message)"
            return
        }
    }
    if ($toFront -eq '1') {
        try { $window.SetFocus() } catch { }
    }

    $strip = Resolve-TabStrip $window
    if ($null -eq $strip) {
        Emit ("ERR$SEP" + (Get-StripMissingMessage $window))
        return
    }
    $items = @(Get-TabItems $strip)
    if ($tabIndex -lt 0 -or $tabIndex -ge $items.Count) {
        Emit "ERR${SEP}tab index $tabIndex out of range (window has $($items.Count) tabs)"
        return
    }
    $target = $items[$tabIndex]
    try {
        $target.GetCurrentPattern($SIP).Select()
    } catch {
        Emit "ERR${SEP}failed to select tab: $($_.Exception.Message)"
        return
    }
    Emit ("OK$SEP" + (Clean $target.Current.Name))
}

while ($true) {
    $line = [Console]::In.ReadLine()
    if ($null -eq $line) { break }
    $line = $line.Trim()
    if ($line.Length -eq 0) { continue }

    $parts = $line.Split("`t")
    try {
        switch ($parts[0]) {
            'PING' { Emit 'PONG' }
            'LIST' {
                # 창 목록마다 프로세스 이름을 새로 확인해야 크롬 재시작 후에도 정확하다.
                $script:processNameCache = @{}
                Invoke-List
            }
            'ACTIVATE' {
                if ($parts.Count -lt 4) { Emit "ERR${SEP}ACTIVATE requires hwnd, tabIndex, toFront" }
                else { Invoke-Activate ([int]$parts[1]) ([int]$parts[2]) $parts[3] }
            }
            'DIALOGS' {
                $script:processNameCache = @{}
                Invoke-Dialogs
            }
            'DIALOG_CLICK' {
                if ($parts.Count -lt 4) { Emit "ERR${SEP}DIALOG_CLICK requires hwnd, dialogIndex, buttonIndex" }
                else { Invoke-DialogClick ([int]$parts[1]) ([int]$parts[2]) ([int]$parts[3]) }
            }
            'EXIT' { Emit '<<<END>>>'; [Console]::Out.Flush(); exit 0 }
            default { Emit "ERR${SEP}unknown command '$($parts[0])'" }
        }
    } catch {
        Emit "ERR${SEP}$($_.Exception.Message)"
    }
    Emit '<<<END>>>'
    [Console]::Out.Flush()
}
