param(
    [string]$BrandColor = "#9C001E"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

if ($BrandColor -notmatch '^#[0-9A-Fa-f]{6}$') {
    throw "BrandColor must use #RRGGBB format."
}

$targetRed = [Convert]::ToInt32($BrandColor.Substring(1, 2), 16)
$targetGreen = [Convert]::ToInt32($BrandColor.Substring(3, 2), 16)
$targetBlue = [Convert]::ToInt32($BrandColor.Substring(5, 2), 16)
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$resourceRoot = Join-Path $repositoryRoot "app/src/main/res"

function Save-TransformedPng {
    param(
        [Parameter(Mandatory)]
        [string]$Path,

        [Parameter(Mandatory)]
        [scriptblock]$Transform
    )

    $loaded = [System.Drawing.Bitmap]::new($Path)
    try {
        # Clone into memory so the original file is not locked while it is
        # atomically replaced below.
        $source = [System.Drawing.Bitmap]::new($loaded)
    }
    finally {
        $loaded.Dispose()
    }
    $result = [System.Drawing.Bitmap]::new(
        $source.Width,
        $source.Height,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )

    try {
        for ($y = 0; $y -lt $source.Height; $y++) {
            for ($x = 0; $x -lt $source.Width; $x++) {
                $result.SetPixel($x, $y, (& $Transform $source.GetPixel($x, $y) $x $y))
            }
        }

        $memory = [System.IO.MemoryStream]::new()
        try {
            $result.Save($memory, [System.Drawing.Imaging.ImageFormat]::Png)
            [System.IO.File]::WriteAllBytes($Path, $memory.ToArray())
        }
        finally {
            $memory.Dispose()
        }
    }
    finally {
        $result.Dispose()
        $source.Dispose()
    }
}

function Test-AlreadyBranded {
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    $bitmap = [System.Drawing.Bitmap]::new($Path)
    try {
        $matches = 0
        for ($y = 0; $y -lt $bitmap.Height; $y++) {
            for ($x = 0; $x -lt $bitmap.Width; $x++) {
                $pixel = $bitmap.GetPixel($x, $y)
                if ($pixel.R -eq $targetRed -and $pixel.G -eq $targetGreen -and $pixel.B -eq $targetBlue) {
                    $matches++
                    if ($matches -ge 20) {
                        return $true
                    }
                }
            }
        }

        return $false
    }
    finally {
        $bitmap.Dispose()
    }
}

$logoTransform = {
    param([System.Drawing.Color]$pixel)

    if ($pixel.A -eq 0) {
        return [System.Drawing.Color]::FromArgb(0, 0, 0, 0)
    }

    # This keeps the script safe to rerun on already recolored artwork.
    if ($pixel.R -gt $pixel.B -and $pixel.R -gt $pixel.G) {
        return $pixel
    }

    # The source artwork consists of a cyan background and a white glyph. The
    # red channel provides a stable estimate of white antialiasing coverage.
    $whiteCoverage = [Math]::Max(0.0, [Math]::Min(1.0, ($pixel.R - 35.0) / 220.0))
    $red = [Math]::Round($targetRed + ((255 - $targetRed) * $whiteCoverage))
    $green = [Math]::Round($targetGreen + ((255 - $targetGreen) * $whiteCoverage))
    $blue = [Math]::Round($targetBlue + ((255 - $targetBlue) * $whiteCoverage))
    return [System.Drawing.Color]::FromArgb($pixel.A, $red, $green, $blue)
}

$launcherIcons = Get-ChildItem $resourceRoot -Recurse -File -Filter "ic_launcher.png"
$inAppLogos = Get-ChildItem $resourceRoot -Recurse -File -Filter "ic_syncthing_logo.png"
foreach ($asset in @($launcherIcons) + @($inAppLogos)) {
    if (-not (Test-AlreadyBranded -Path $asset.FullName)) {
        Save-TransformedPng -Path $asset.FullName -Transform $logoTransform
    }
}

$adaptiveBackground = Join-Path $resourceRoot "mipmap/ic_background.png"
if (-not (Test-AlreadyBranded -Path $adaptiveBackground)) {
    Save-TransformedPng -Path $adaptiveBackground -Transform {
        param([System.Drawing.Color]$pixel)
        return [System.Drawing.Color]::FromArgb($pixel.A, $targetRed, $targetGreen, $targetBlue)
    }
}

foreach ($asset in Get-ChildItem $resourceRoot -Recurse -File -Filter "banner.png") {
    if (Test-AlreadyBranded -Path $asset.FullName) {
        continue
    }

    $bannerScale = $asset.Name.Length
    $probe = [System.Drawing.Bitmap]::new($asset.FullName)
    try {
        $bannerScale = $probe.Width / 640.0
    }
    finally {
        $probe.Dispose()
    }

    $bannerTransform = {
        param([System.Drawing.Color]$pixel, [int]$x, [int]$y)

        $centerX = 111.0 * $bannerScale
        $centerY = 187.0 * $bannerScale
        $radius = 89.0 * $bannerScale
        $distanceSquared = (($x - $centerX) * ($x - $centerX)) + (($y - $centerY) * ($y - $centerY))
        if ($distanceSquared -le ($radius * $radius)) {
            return & $logoTransform $pixel
        }

        $inTextBounds = (
            ($x -ge (210 * $bannerScale) -and $x -le (620 * $bannerScale) -and
                $y -ge (75 * $bannerScale) -and $y -le (145 * $bannerScale)) -or
            ($x -ge (270 * $bannerScale) -and $x -le (510 * $bannerScale) -and
                $y -ge (150 * $bannerScale) -and $y -le (218 * $bannerScale)) -or
            ($x -ge (225 * $bannerScale) -and $x -le (625 * $bannerScale) -and
                $y -ge (220 * $bannerScale) -and $y -le (258 * $bannerScale))
        )

        if ($inTextBounds -and $pixel.R -lt 250) {
            # Text antialiasing is a blend of Syncthing blue (#0990D1) and its
            # white outline. Match that color line so blue sky remains intact.
            $foregroundCoverage = (255.0 - $pixel.R) / (255.0 - 9.0)
            $expectedGreen = 255.0 + ((144.0 - 255.0) * $foregroundCoverage)
            $expectedBlue = 255.0 + ((209.0 - 255.0) * $foregroundCoverage)
            if ([Math]::Abs($pixel.G - $expectedGreen) -le 28.0 -and
                [Math]::Abs($pixel.B - $expectedBlue) -le 28.0) {
                return & $logoTransform $pixel
            }
        }

        return $pixel
    }

    Save-TransformedPng -Path $asset.FullName -Transform $bannerTransform
}

Write-Output "Recolored Syncthing branding assets to $BrandColor."
