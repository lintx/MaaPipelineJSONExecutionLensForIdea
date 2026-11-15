param(
    [string]$EventPath = $env:GITHUB_EVENT_PATH,
    [string]$OutputDirectory = $env:RUNNER_TEMP,
    [string]$EnvironmentFile = $env:GITHUB_ENV
)

$ErrorActionPreference = 'Stop'
$eventData = Get-Content -LiteralPath $EventPath -Encoding UTF8 -Raw | ConvertFrom-Json
$release = $eventData.release
if (!$release -or $eventData.action -ne 'published' -or $release.draft -or $release.prerelease) {
    throw 'Only a published, non-prerelease GitHub Release can publish to Marketplace.'
}

$tag = [string]$release.tag_name
if ($tag -cnotmatch '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') {
    throw 'Release tag must use vMAJOR.MINOR.PATCH, for example v0.1.1.'
}
if ([string]::IsNullOrWhiteSpace([string]$release.body)) {
    throw 'Write release notes before publishing the GitHub Release.'
}

# Release 正文是外部输入：只作为 UTF-8 纯文本转义后展示，不当作脚本或 HTML 执行。
$notesPath = Join-Path $OutputDirectory 'marketplace-release-notes.html'
$escapedNotes = [System.Net.WebUtility]::HtmlEncode([string]$release.body)
Set-Content -LiteralPath $notesPath -Value "<pre>$escapedNotes</pre>" -Encoding UTF8
Add-Content -LiteralPath $EnvironmentFile -Value "RELEASE_VERSION=$($tag.Substring(1))" -Encoding UTF8
Add-Content -LiteralPath $EnvironmentFile -Value "RELEASE_NOTES_FILE=$notesPath" -Encoding UTF8
