# Інструменти для телевізора Bravis (CVTE / MediaTek, Android 7) через ADB по мережі.
# Запускати через tv-tools.bat. Нічого не видаляє: застосунки лише вимикаються
# (pm disable-user) і повертаються пунктом «Відновити все».

$ErrorActionPreference = 'Continue'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Adb = Join-Path $Here 'platform-tools\adb.exe'
$IpFile = Join-Path $Here 'tv-ip.txt'
$ListFile = Join-Path $Here 'bloat-list.txt'
$RestoreFile = Join-Path $Here 'disabled-by-script.txt'
$HiddenFile = Join-Path $Here 'hidden-by-script.txt'

# Без цих пакетів com.google.android.gms падає з повідомленням про помилку.
$GmsDeps = @('com.google.android.gsf', 'com.google.android.gsf.login', 'com.google.android.gsf.notouch')

# «Постійні» системні пакети: система запускає їх при старті навіть вимкненими, і вони
# падають. Такі пакети треба приховувати (pm hide): тоді система їх не бачить і не запускає.
$HideFirst = @('com.google.android.gms')
$script:Target = ''

# Ці пакети скрипт не вимикає ніколи, навіть якщо вони потраплять у список.
$Protected = @(
    'android', 'com.android.systemui', 'com.android.settings', 'com.android.packageinstaller',
    'com.android.providers.settings', 'com.android.providers.tv', 'com.android.shell',
    'com.android.inputmethod.latin', 'com.android.webview',
    'com.example.user.myapplication',   # запасний головний екран при завантаженні
    'com.cvte.tv.launcher',             # штатний лаунчер (запасний)
    'com.cvte.tv.launcher.fall',
    'com.cvte.tvservice', 'com.cvte.tv.api.impl', 'com.cvte.tv.androidsetting',
    'ua.domivka'
)

function Test-Protected([string]$pkg) {
    return ($Protected -contains $pkg) -or $pkg.StartsWith('com.mediatek.') -or $pkg.StartsWith('com.android.providers.')
}

function Get-Adb {
    if (Test-Path $Adb) { return }
    Write-Host 'Завантажую ADB (Android platform-tools) з офіційного сайту Google...'
    $zip = Join-Path $env:TEMP 'platform-tools-latest-windows.zip'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip -UseBasicParsing
    Expand-Archive $zip -DestinationPath $Here -Force
    Remove-Item $zip -ErrorAction SilentlyContinue
    if (-not (Test-Path $Adb)) { throw 'Не вдалося розпакувати ADB.' }
}

function Connect-Tv {
    $ip = ''
    if (Test-Path $IpFile) { $ip = (Get-Content $IpFile -Raw).Trim() }
    $answer = Read-Host "IP-адреса телевізора (Налаштування -> Мережа) [$ip]"
    if ($answer) { $ip = $answer.Trim() }
    if (-not $ip) { Write-Host 'IP-адресу не вказано.'; return $false }
    Set-Content -Path $IpFile -Value $ip
    $script:Target = "$($ip):5555"

    Write-Host (& $Adb connect $script:Target 2>&1 | Out-String).Trim()
    for ($i = 0; $i -lt 30; $i++) {
        $state = (& $Adb -s $script:Target get-state 2>$null | Out-String).Trim()
        if ($state -eq 'device') { Write-Host 'Підключено до телевізора.'; return $true }
        if ($i -eq 0) {
            Write-Host "Якщо на телевізорі з'явилося питання про налагодження, виберіть «Завжди дозволяти» і «OK»."
        }
        Start-Sleep -Seconds 2
        if ($state -ne 'unauthorized') { & $Adb connect $script:Target 2>&1 | Out-Null }
    }
    return $false
}

function Invoke-Sh([string]$cmd) {
    return (& $Adb -s $script:Target shell $cmd 2>&1 | Out-String).Trim()
}

function Get-Packages([string]$flags = '') {
    $raw = Invoke-Sh "pm list packages $flags"
    return @($raw -split "`r?`n" | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ })
}

function Get-FreeMb {
    $m = [regex]::Match((Invoke-Sh 'cat /proc/meminfo'), 'MemAvailable:\s+(\d+)')
    if ($m.Success) { return [int]([int64]$m.Groups[1].Value / 1024) }
    return -1
}

function Set-Animations([string]$scale) {
    foreach ($key in 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale') {
        Invoke-Sh "settings put global $key $scale" | Out-Null
    }
}

# ---------------------------------------------------------------- 1. Звіт

function Invoke-Report {
    $file = Join-Path $Here ('tv-report-' + (Get-Date -Format 'yyyyMMdd-HHmm') + '.txt')
    $sb = New-Object System.Text.StringBuilder
    $sections = [ordered]@{
        'Прошивка'                 = 'getprop ro.build.fingerprint'
        "Пам'ять"                  = 'cat /proc/meminfo | head -6'
        'Системні пакети'          = 'pm list packages -s'
        'Встановлені користувачем' = 'pm list packages -3'
        'Вимкнені'                 = 'pm list packages -d'
        "Хто скільки пам'яті їсть" = 'dumpsys meminfo | head -70'
        'Процеси'                  = 'top -n 1 -m 25'
    }
    foreach ($title in $sections.Keys) {
        Write-Host "  $title..."
        [void]$sb.AppendLine("===== $title =====")
        [void]$sb.AppendLine((Invoke-Sh $sections[$title]))
        [void]$sb.AppendLine()
    }
    [IO.File]::WriteAllText($file, $sb.ToString(), [Text.Encoding]::UTF8)
    Write-Host "Готово: $file"
    Write-Host 'Цей файл можна надіслати в чат, щоб підібрати список під ваш телевізор.'
}

# ---------------------------------------------------------------- 2. Оптимізація

function Invoke-Optimize {
    if (-not (Test-Path $ListFile)) { Write-Host "Немає файлу $ListFile"; return }
    $installed = Get-Packages
    $alreadyOff = Get-Packages '-d'
    $wanted = New-Object System.Collections.Generic.List[string]

    foreach ($line in Get-Content $ListFile -Encoding UTF8) {
        $entry = ($line -replace '#.*$', '').Trim()
        if (-not $entry) { continue }
        if ($entry.StartsWith('~')) {
            $word = $entry.Substring(1).ToLower()
            foreach ($p in $installed) { if ($p.ToLower().Contains($word)) { $wanted.Add($p) } }
        } elseif ($installed -contains $entry) {
            $wanted.Add($entry)
        }
    }
    $todo = @($wanted | Select-Object -Unique | Where-Object { -not (Test-Protected $_) -and -not ($alreadyOff -contains $_) })

    Write-Host ''
    if ($todo.Count -eq 0) {
        Write-Host 'Зі списку нічого вимикати не треба (або вже вимкнено).'
    } else {
        Write-Host 'Буде вимкнено:'
        $todo | ForEach-Object { Write-Host "  - $_" }
    }
    Write-Host '  + анімації інтерфейсу у 2 рази швидші'
    Write-Host '  + Домівка і SmartTube будуть попередньо скомпільовані (швидший запуск)'
    $ok = Read-Host 'Продовжити? (т/н)'
    if ($ok -notmatch '^(т|t|y|д|так|yes)') { Write-Host 'Скасовано.'; return }

    $before = Get-FreeMb
    foreach ($p in $todo) {
        $r = Invoke-Sh "pm disable-user --user 0 $p"
        if ($r -match 'disabled') {
            Add-Content -Path $RestoreFile -Value $p
            Write-Host "  вимкнено: $p"
            continue
        }
        # Деякі пакети (наприклад, сервіси Google) прошивка не дає вимкнути: пробуємо приховати
        $h = Invoke-Sh "pm hide $p"
        if ($h -match 'true') {
            Add-Content -Path $HiddenFile -Value $p
            Write-Host "  приховано: $p"
        } else {
            Write-Host "  не вдалося: $p"
            Write-Host "     вимкнення: $r"
            Write-Host "     приховання: $h"
        }
    }

    # «Постійні» пакети приховуємо, навіть якщо їх уже вимкнено: інакше система все одно
    # запускає їх при старті, і вони падають з повідомленням.
    $active = Get-Packages   # приховані сюди не потрапляють, вимкнені потрапляють
    $listed = @(Get-Content $ListFile -Encoding UTF8 | ForEach-Object { ($_ -replace '#.*$', '').Trim() })
    foreach ($p in $HideFirst) {
        if (-not ($active -contains $p) -or -not ($listed -contains $p)) { continue }
        $h = Invoke-Sh "pm hide $p"
        if ($h -match 'true') {
            Add-Content -Path $HiddenFile -Value $p
            Write-Host "  приховано (щоб не запускався і не падав): $p"
        } else {
            Write-Host ''
            Write-Host "Не вдалося приховати $p ($h)."
            Write-Host 'Щоб не було повідомлень про помилку, вмикаю сервіси Google назад.'
            Invoke-Sh "pm enable $p" | Out-Null
            foreach ($d in $GmsDeps) { Invoke-Sh "pm enable $d" | Out-Null }
        }
    }

    Set-Animations '0.5'

    foreach ($p in $installed) {
        if ($p -eq 'ua.domivka' -or $p.ToLower().Contains('smarttube')) {
            Write-Host "  компілюю $p (до хвилини)..."
            Invoke-Sh "cmd package compile -m speed -f $p" | Out-Null
        }
    }

    Invoke-Sh 'am kill-all' | Out-Null
    Start-Sleep -Seconds 3
    $after = Get-FreeMb
    Write-Host ''
    Write-Host "Вільної пам'яті: було $before МБ, стало $after МБ."
    Write-Host 'Для повного ефекту вимкніть телевізор з розетки на 10 секунд.'
}

# ---------------------------------------------------------------- 3. Відновлення

function Invoke-Restore {
    $list = @()
    if (Test-Path $RestoreFile) { $list = @(Get-Content $RestoreFile | Where-Object { $_ } | Select-Object -Unique) }
    if ($list.Count -eq 0) { Write-Host 'Скрипт ще нічого не вимикав.' }
    foreach ($p in $list) {
        Write-Host "  вмикаю: $p  $(Invoke-Sh "pm enable $p")"
    }
    if (Test-Path $RestoreFile) { Remove-Item $RestoreFile }
    if (Test-Path $HiddenFile) {
        foreach ($p in @(Get-Content $HiddenFile | Where-Object { $_ } | Select-Object -Unique)) {
            Write-Host "  показую: $p  $(Invoke-Sh "pm unhide $p")"
        }
        Remove-Item $HiddenFile
    }
    Set-Animations '1.0'
    Write-Host 'Готово: усе, що вимикав скрипт, знову увімкнено, анімації звичайні.'
}

# ---------------------------------------------------------------- 4/5. Головний екран

function Set-Home([string]$component, [string]$name) {
    $r = Invoke-Sh "cmd package set-home-activity $component"
    Write-Host "Головний екран: $name. $r"
}

# ---------------------------------------------------------------- меню

try {
    Get-Adb
} catch {
    Write-Host "Помилка: $_"
    return
}

Write-Host ''
Write-Host 'Перед першим запуском увімкніть ADB на телевізорі (див. README.txt).'
if (-not (Connect-Tv)) {
    Write-Host 'Не вдалося підключитися. Перевірте IP-адресу, що ADB увімкнено і що телевізор і ROG Ally в одній мережі.'
    return
}

$quit = $false
while (-not $quit) {
    Write-Host ''
    Write-Host '==== Телевізор ===='
    Write-Host "Вільної пам'яті зараз: $(Get-FreeMb) МБ"
    Write-Host '1 - Звіт про телевізор (нічого не змінює)'
    Write-Host '2 - Оптимізувати (вимкнути зайве за списком bloat-list.txt)'
    Write-Host '3 - Відновити все, що вимкнув скрипт'
    Write-Host '4 - Зробити Домівку головним екраном'
    Write-Host '5 - Повернути штатний головний екран'
    Write-Host '0 - Вихід'
    switch (Read-Host 'Виберіть') {
        '1' { Invoke-Report }
        '2' { Invoke-Optimize }
        '3' { Invoke-Restore }
        '4' {
            Set-Home 'ua.domivka/.HomeActivity' 'Домівка'
            # Доступ до статистики: Домівка точно бачитиме, що прошивка після ввімкнення
            # відкрила штатний лаунчер, і підмінятиме лише його
            Invoke-Sh 'appops set ua.domivka GET_USAGE_STATS allow' | Out-Null
            Write-Host 'Домівці надано доступ до статистики використання (сторож увімкнення).'
        }
        '5' { Set-Home 'com.cvte.tv.launcher/.AppActivity' 'штатний' }
        '0' { $quit = $true }
        default { Write-Host 'Невідомий пункт.' }
    }
}
& $Adb disconnect $script:Target 2>&1 | Out-Null
