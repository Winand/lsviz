param (
    [string]$Mirror = "https://download.qt.io",
    [string]$Version = "6.10.2",
    # QtJambi requires msvc2022_64 https://www.qtjambi.io/doc/First-Steps-With-QtJambi.md
    [string]$Toolchain = "msvc2022_64", # msvc2022_64, mingw, llvm_mingw
    [string]$Destination = "qt"
)

if (Test-Path -Path $Destination) {
    Write-Error "Destinantion path $Destination already exists" -ErrorAction Stop
} else {
    $null = New-Item -ItemType Directory -Path $Destination
}

$major = $Version.Split(".")[0]
$compactVersion = $Version -replace "\.", ""
$mirror = $Mirror.TrimEnd("/")
$filePattern = "$Version.+?qtbase.+?\.7z"

$baseUrl = "$mirror/online/qtsdkrepository/windows_x86/desktop/qt{0}_{1}/qt{0}_{1}/qt.qt{0}.{1}.win64_$Toolchain/" -f `
    $major, $compactVersion

Write-Host "Retrieve file list from repository: $baseUrl"
$webContent = Invoke-WebRequest -Uri $baseUrl -UseBasicParsing -ErrorAction Stop
if ($webContent.Content -match $filePattern) {
    $fileName = $Matches[0]
    $downloadUrl = $baseUrl + $fileName
    $downloadPath = Join-Path $Destination $fileName
    Write-Host "Download Qt distribution from: $downloadUrl"
    Start-BitsTransfer -Source $downloadUrl -Destination $downloadPath  # Invoke-WebRequest with progress bar is slow

    Write-Host "Extract Qt to: $Destination"
    tar -xf $downloadPath -C $Destination

    Write-Host "Clean up"
    Get-ChildItem -Path $Destination | Where-Object { $_.Name -notmatch '^(bin|plugins)$' } | Remove-Item -Recurse -Force
    $debugFiles = Get-ChildItem -Path $Destination -Filter "*d.dll" -Recurse
    foreach ($i in $debugFiles) {
        $releaseFilePath = Join-Path $i.DirectoryName ($i.Name -replace 'd\.dll$', '.dll')
        if (Test-Path $releaseFilePath) {
            Remove-Item $i.FullName -Force
        }
    }
} else {
    Write-Error "Distribution file not found in repo" -ErrorAction Stop
}
Write-Host "Qt $Version ($Toolchain) bootstrap finished"
