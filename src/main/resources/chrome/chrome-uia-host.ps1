# 크롬 탭 제어용 UI Automation 상주 호스트.
#
# stdin 으로 탭 구분 명령을 한 줄씩 받아, 응답 줄들을 stdout 에 쓰고 마지막에 <<<END>>> 를 출력한다.
# Add-Type / 어셈블리 로드 비용을 프로세스 수명 동안 한 번만 지불하기 위해 상주 방식으로 동작한다.
#
# 명령:
#   PING                                  -> PONG
#   LIST                                  -> W/T 레코드 (아래 형식)
#   ACTIVATE <hwnd> <tabIndex> <toFront>  -> OK <탭 이름> | ERR <사유>
#   EXIT                                  -> 종료
#
# 레코드 형식 (탭 구분):
#   W <hwnd> <windowVisualState> <windowTitle>
#   T <hwnd> <tabIndex> <selected 0|1> <tabName>
#
# 탭 매칭/모호성 판정은 전부 Java 쪽(ChromeTabIndex)에 있다 — 이 스크립트는 단순 전송 계층이다.

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

# 변수명 주의: PowerShell 변수는 대소문자를 구분하지 않는다. 루프 변수 $tab 과 충돌하므로
# 구분자 변수를 $TAB 으로 두면 안 된다.
$SEP = "`t"
$STRIP_MAX_DEPTH = 8
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

function Find-TabStrip($element, $depth) {
    if ($depth -le 0) { return $null }
    $child = $TW.GetFirstChild($element)
    while ($null -ne $child) {
        if ($child.Current.ControlType -eq $CT::Tab) { return $child }
        $found = Find-TabStrip $child ($depth - 1)
        if ($null -ne $found) { return $found }
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

function Get-TabItems($strip) {
    $items = @()
    $child = $TW.GetFirstChild($strip)
    while ($null -ne $child) {
        if ($child.Current.ControlType -eq $CT::TabItem) { $items += $child }
        $child = $TW.GetNextSibling($child)
    }
    return $items
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
            Emit "ERR${SEP}tab strip not found for hwnd $hwnd"
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
        Emit "ERR${SEP}tab strip not found for hwnd $hwnd"
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
            'EXIT' { Emit '<<<END>>>'; [Console]::Out.Flush(); exit 0 }
            default { Emit "ERR${SEP}unknown command '$($parts[0])'" }
        }
    } catch {
        Emit "ERR${SEP}$($_.Exception.Message)"
    }
    Emit '<<<END>>>'
    [Console]::Out.Flush()
}
