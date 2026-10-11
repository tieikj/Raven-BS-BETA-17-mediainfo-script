# media-helper.ps1
# Serves the current Windows media session (Spotify, YouTube in a browser, etc.)
# as JSON on http://127.0.0.1:8765/ so the Raven MediaInfo script can read it.
# Only listens on localhost. Run with:
#   powershell.exe -NoLogo -ExecutionPolicy Bypass -File media-helper.ps1
# Windows PowerShell 5.1 (the built-in powershell.exe) is required, not pwsh 7.

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]

$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($op, $type) {
    $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    $t.Wait(-1) | Out-Null
    $t.Result
}

$managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
$propsType   = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
$manager     = Await ($managerType::RequestAsync()) $managerType

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add('http://127.0.0.1:8765/')
$listener.Start()
Write-Host 'Media helper running on http://127.0.0.1:8765/  (Ctrl+C to stop)'

try {
    while ($listener.IsListening) {
        $ctx = $listener.GetContext()

        $data = [ordered]@{
            title    = ''
            artist   = ''
            album    = ''
            position = 0
            length   = 0
            playing  = $false
            app      = ''
        }

        try {
            $session = $manager.GetCurrentSession()
            if ($session) {
                $props    = Await ($session.TryGetMediaPropertiesAsync()) $propsType
                $timeline = $session.GetTimelineProperties()
                $playback = $session.GetPlaybackInfo()

                $data.title    = [string]$props.Title
                $data.artist   = [string]$props.Artist
                $data.album    = [string]$props.AlbumTitle
                $data.position = [long]$timeline.Position.TotalMilliseconds
                $data.length   = [long]($timeline.EndTime.TotalMilliseconds - $timeline.StartTime.TotalMilliseconds)
                $data.playing  = ($playback.PlaybackStatus.ToString() -eq 'Playing')
                $data.app      = [string]$session.SourceAppUserModelId
            }
        } catch {
            # leave defaults (empty title = nothing playing)
        }

        $bytes = [System.Text.Encoding]::UTF8.GetBytes(($data | ConvertTo-Json -Compress))
        $ctx.Response.ContentType = 'application/json; charset=utf-8'
        $ctx.Response.ContentLength64 = $bytes.Length
        $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
        $ctx.Response.Close()
    }
} finally {
    $listener.Stop()
}
