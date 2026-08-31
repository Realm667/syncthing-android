<#
.SYNOPSIS
Creates the initial ES-DE metadata sidecars from authoritative gamelist.xml files.

.DESCRIPTION
Recursively reads system gamelist.xml files below a ROM root and creates one
.esde-sync/<game path>.esde.json file per valid game. The XML and ROM files are
never modified. Without -Apply, the script is a read-only preview. Existing
sidecars are skipped unless -OverwriteExisting is explicitly supplied.

.EXAMPLE
.\Initialize-EsdeSidecars.ps1 -RomsRoot '\\Dg-qn-nas\database\EMULATION\ROMs\HANDHELD-SYNC\ROMS'

.EXAMPLE
.\Initialize-EsdeSidecars.ps1 -RomsRoot '\\Dg-qn-nas\database\EMULATION\ROMs\HANDHELD-SYNC\ROMS' -Apply
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $RomsRoot,

    [switch] $Apply,

    [switch] $OverwriteExisting
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:MaxGamelistBytes = 64MB
$script:MaxPathLength = 4096
$script:MaxSegmentLength = 255
$script:SidecarDirectoryName = '.esde-sync'
$script:SidecarSuffix = '.esde.json'
$script:Utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)

function Get-CanonicalDirectoryPath {
    param([Parameter(Mandatory = $true)][string] $Path)

    $item = Get-Item -LiteralPath $Path -Force
    if (-not $item.PSIsContainer) {
        throw "ROM root is not a directory: $Path"
    }
    return [System.IO.Path]::GetFullPath($item.FullName).TrimEnd('\', '/')
}

function Test-PathInsideRoot {
    param(
        [Parameter(Mandatory = $true)][string] $Candidate,
        [Parameter(Mandatory = $true)][string] $Root
    )

    $fullCandidate = [System.IO.Path]::GetFullPath($Candidate)
    $rootPrefix = $Root.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    return $fullCandidate.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)
}

function Get-DirectChildText {
    param(
        [Parameter(Mandatory = $true)][System.Xml.XmlElement] $Parent,
        [Parameter(Mandatory = $true)][string] $Name
    )

    foreach ($child in $Parent.ChildNodes) {
        if ($child.NodeType -eq [System.Xml.XmlNodeType]::Element -and $child.LocalName -eq $Name) {
            return [string] $child.InnerText
        }
    }
    return $null
}

function ConvertTo-NormalizedGamePath {
    param([Parameter(Mandatory = $true)][string] $RawPath)

    if ([string]::IsNullOrWhiteSpace($RawPath)) { throw 'empty game path' }
    if ($RawPath.Length -gt $script:MaxPathLength) { throw 'game path is too long' }
    if ($RawPath.IndexOf([char] 0) -ge 0) { throw 'NUL in game path' }

    $slashed = $RawPath.Replace('\', '/')
    if ($slashed.StartsWith('/') -or $slashed -match '^[A-Za-z]:') {
        throw 'absolute or drive-qualified game path'
    }
    if ($slashed.StartsWith('./')) { $slashed = $slashed.Substring(2) }
    $segments = $slashed.Split('/')
    if ($segments.Count -eq 0) { throw 'empty game path' }
    foreach ($segment in $segments) {
        if ([string]::IsNullOrWhiteSpace($segment) -or $segment -eq '.' -or $segment -eq '..') {
            throw 'unsafe game path segment'
        }
        if ($segment.Length -gt $script:MaxSegmentLength) { throw 'game path segment is too long' }
    }
    return './' + ($segments -join '/')
}

function ConvertTo-NullableBoolean {
    param([AllowNull()][string] $Value)
    if ($Value -ceq 'true') { return $true }
    if ($Value -ceq 'false') { return $false }
    return $null
}

function ConvertTo-NullableInt64 {
    param([AllowNull()][string] $Value)
    if ($null -eq $Value) { return $null }
    $parsed = 0L
    if ([long]::TryParse($Value, [System.Globalization.NumberStyles]::Integer,
            [System.Globalization.CultureInfo]::InvariantCulture, [ref] $parsed)) {
        return $parsed
    }
    return $null
}

function ConvertTo-NullableRating {
    param([AllowNull()][string] $Value)
    if ($null -eq $Value) { return $null }
    $parsed = 0.0
    if ([double]::TryParse($Value, [System.Globalization.NumberStyles]::Float,
            [System.Globalization.CultureInfo]::InvariantCulture, [ref] $parsed) -and
            -not [double]::IsNaN($parsed) -and -not [double]::IsInfinity($parsed) -and
            $parsed -ge 0.0 -and $parsed -le 1.0) {
        return $parsed
    }
    return $null
}

function Test-PlayersValue {
    param([AllowNull()][string] $Value)
    if ($null -eq $Value -or $Value -notmatch '^(\d{1,2})(?:-(\d{1,2}))?$') { return $false }
    $first = [int] $Matches[1]
    $last = if ($Matches[2]) { [int] $Matches[2] } else { $first }
    return $first -ge 1 -and $first -le 99 -and $last -ge $first -and $last -le 99
}

function Add-OptionalProperty {
    param(
        [Parameter(Mandatory = $true)][System.Collections.Specialized.OrderedDictionary] $Target,
        [Parameter(Mandatory = $true)][string] $Name,
        [AllowNull()] $Value
    )
    if ($null -ne $Value) { $Target.Add($Name, $Value) }
}

function Read-SafeGamelist {
    param([Parameter(Mandatory = $true)][System.IO.FileInfo] $File)

    if ($File.Length -gt $script:MaxGamelistBytes) {
        throw "gamelist.xml exceeds $script:MaxGamelistBytes bytes"
    }
    $readerSettings = New-Object System.Xml.XmlReaderSettings
    $readerSettings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
    $readerSettings.XmlResolver = $null
    $readerSettings.ConformanceLevel = [System.Xml.ConformanceLevel]::Fragment
    $readerSettings.MaxCharactersInDocument = $script:MaxGamelistBytes
    $readerSettings.MaxCharactersFromEntities = 0

    $reader = [System.Xml.XmlReader]::Create($File.FullName, $readerSettings)
    try {
        $document = New-Object System.Xml.XmlDocument
        $document.PreserveWhitespace = $true
        $document.XmlResolver = $null
        $container = $document.CreateElement('esdeSyncDocument')
        [void] $document.AppendChild($container)
        while (-not $reader.EOF) {
            $node = $document.ReadNode($reader)
            if ($null -eq $node) {
                if (-not $reader.EOF) { [void] $reader.Read() }
                continue
            }
            if ($node.NodeType -eq [System.Xml.XmlNodeType]::XmlDeclaration) { continue }
            [void] $container.AppendChild($node)
        }
    }
    finally {
        $reader.Dispose()
    }

    $elements = @($container.ChildNodes | Where-Object { $_.NodeType -eq [System.Xml.XmlNodeType]::Element })
    $gameLists = @($elements | Where-Object { $_.LocalName -eq 'gameList' })
    $alternativeEmulators = @($elements | Where-Object { $_.LocalName -eq 'alternativeEmulator' })
    $unsupported = @($elements | Where-Object { $_.LocalName -notin @('gameList', 'alternativeEmulator') })
    if ($gameLists.Count -ne 1) { throw 'expected exactly one gameList element' }
    if ($alternativeEmulators.Count -gt 1) { throw 'expected at most one alternativeEmulator element' }
    if ($unsupported.Count -gt 0) { throw "unsupported top-level element: $($unsupported[0].LocalName)" }
    foreach ($node in $container.ChildNodes) {
        if ($node.NodeType -eq [System.Xml.XmlNodeType]::Text -and -not [string]::IsNullOrWhiteSpace($node.Value)) {
            throw 'unexpected text outside gameList'
        }
        if ($node.NodeType -notin @(
                [System.Xml.XmlNodeType]::Element,
                [System.Xml.XmlNodeType]::Text,
                [System.Xml.XmlNodeType]::Whitespace,
                [System.Xml.XmlNodeType]::SignificantWhitespace,
                [System.Xml.XmlNodeType]::Comment)) {
            throw "unsupported top-level XML node: $($node.NodeType)"
        }
    }
    return $document
}

function Write-Utf8JsonAtomically {
    param(
        [Parameter(Mandatory = $true)][string] $TargetPath,
        [Parameter(Mandatory = $true)][string] $Json
    )

    $directory = [System.IO.Path]::GetDirectoryName($TargetPath)
    [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    $temporaryPath = Join-Path $directory ('.' + [System.IO.Path]::GetFileName($TargetPath) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [System.IO.File]::WriteAllText($temporaryPath, $Json + [Environment]::NewLine, $script:Utf8WithoutBom)
        if ([System.IO.File]::Exists($TargetPath)) {
            [System.IO.File]::Replace($temporaryPath, $TargetPath, $null)
        }
        else {
            [System.IO.File]::Move($temporaryPath, $TargetPath)
        }
    }
    finally {
        if ([System.IO.File]::Exists($temporaryPath)) { [System.IO.File]::Delete($temporaryPath) }
    }
}

$root = Get-CanonicalDirectoryPath -Path $RomsRoot
$gamelists = @(Get-ChildItem -LiteralPath $root -Filter 'gamelist.xml' -File -Recurse -Force |
    Where-Object { $_.FullName -notmatch '[\\/]\.esde-sync(?:[\\/]|$)' } |
    Sort-Object FullName)
if ($gamelists.Count -eq 0) {
    throw "No gamelist.xml files were found below: $root"
}

$summary = [ordered]@{
    Gamelists = $gamelists.Count
    Games = 0
    WouldWrite = 0
    Written = 0
    ExistingSkipped = 0
    InvalidGames = 0
    Errors = 0
}

Write-Host "ES-DE sidecar bootstrap root: $root"
Write-Host ($(if ($Apply) { 'Mode: APPLY' } else { 'Mode: PREVIEW (no files will be written)' }))

foreach ($gamelist in $gamelists) {
    $systemDirectory = [System.IO.Path]::GetFullPath($gamelist.Directory.FullName)
    if (-not (Test-PathInsideRoot -Candidate $systemDirectory -Root $root)) {
        Write-Warning "Skipped gamelist outside root: $($gamelist.FullName)"
        $summary.Errors++
        continue
    }

    try {
        $document = Read-SafeGamelist -File $gamelist
    }
    catch {
        Write-Warning "Invalid gamelist '$($gamelist.FullName)': $($_.Exception.Message)"
        $summary.Errors++
        continue
    }

    $gameList = @($document.DocumentElement.ChildNodes | Where-Object {
        $_.NodeType -eq [System.Xml.XmlNodeType]::Element -and $_.LocalName -eq 'gameList'
    })[0]
    foreach ($gameNode in @($gameList.ChildNodes | Where-Object {
            $_.NodeType -eq [System.Xml.XmlNodeType]::Element -and $_.LocalName -eq 'game'
        })) {
        if ($gameNode -isnot [System.Xml.XmlElement]) { continue }
        $summary.Games++
        $rawPath = Get-DirectChildText -Parent $gameNode -Name 'path'
        try {
            $gamePath = ConvertTo-NormalizedGamePath -RawPath $rawPath
            $relativeGamePath = $gamePath.Substring(2).Replace('/', [System.IO.Path]::DirectorySeparatorChar)
            $target = Join-Path (Join-Path $systemDirectory $script:SidecarDirectoryName) ($relativeGamePath + $script:SidecarSuffix)
            $targetFull = [System.IO.Path]::GetFullPath($target)
            $sidecarRoot = [System.IO.Path]::GetFullPath((Join-Path $systemDirectory $script:SidecarDirectoryName))
            if (-not (Test-PathInsideRoot -Candidate $targetFull -Root $sidecarRoot)) {
                throw 'sidecar path escaped its system directory'
            }
        }
        catch {
            Write-Warning "Skipped unsafe game path in '$($gamelist.FullName)': $($_.Exception.Message)"
            $summary.InvalidGames++
            continue
        }

        if ([System.IO.File]::Exists($targetFull) -and -not $OverwriteExisting) {
            $summary.ExistingSkipped++
            continue
        }

        $sidecar = [ordered]@{
            schemaVersion = 1
            game = $gamePath
        }
        Add-OptionalProperty $sidecar 'favorite' (ConvertTo-NullableBoolean (Get-DirectChildText $gameNode 'favorite'))
        Add-OptionalProperty $sidecar 'completed' (ConvertTo-NullableBoolean (Get-DirectChildText $gameNode 'completed'))
        Add-OptionalProperty $sidecar 'playcount' (ConvertTo-NullableInt64 (Get-DirectChildText $gameNode 'playcount'))
        Add-OptionalProperty $sidecar 'playtime' (ConvertTo-NullableInt64 (Get-DirectChildText $gameNode 'playtime'))
        Add-OptionalProperty $sidecar 'lastplayed' (Get-DirectChildText $gameNode 'lastplayed')
        Add-OptionalProperty $sidecar 'altemulator' (Get-DirectChildText $gameNode 'altemulator')
        $players = Get-DirectChildText $gameNode 'players'
        if (Test-PlayersValue $players) { Add-OptionalProperty $sidecar 'players' $players }
        Add-OptionalProperty $sidecar 'rating' (ConvertTo-NullableRating (Get-DirectChildText $gameNode 'rating'))
        $sidecar.Add('updatedAt', [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", [System.Globalization.CultureInfo]::InvariantCulture))

        $summary.WouldWrite++
        if ($Apply -and $PSCmdlet.ShouldProcess($targetFull, 'Create ES-DE metadata sidecar')) {
            $json = ([pscustomobject] $sidecar) | ConvertTo-Json -Depth 4 -Compress
            Write-Utf8JsonAtomically -TargetPath $targetFull -Json $json
            $summary.Written++
        }
    }
}

Write-Host ''
Write-Host "Gamelists: $($summary.Gamelists); games: $($summary.Games); invalid games: $($summary.InvalidGames); errors: $($summary.Errors)"
Write-Host "Would write: $($summary.WouldWrite); written: $($summary.Written); existing sidecars skipped: $($summary.ExistingSkipped)"
if (-not $Apply) {
    Write-Host 'Preview complete. Run again with -Apply after reviewing the counts.'
}
if ($summary.Errors -gt 0 -or $summary.InvalidGames -gt 0) { exit 2 }
