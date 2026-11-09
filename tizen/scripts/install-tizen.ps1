<#
  Installe (ou met à jour) l'appli IPTV sur une TV Samsung depuis Windows.

  Usage :
    .\install-tizen.ps1 -TvIp 192.168.1.50
    .\install-tizen.ps1 -TvIp 192.168.1.50 -CertProfile tviptv         # profil de certificat (Tizen Studio)
    .\install-tizen.ps1 -TvIp 192.168.1.50 -Source ..\               # depuis le dossier tizen/ local
    .\install-tizen.ps1 -TvIp 192.168.1.50 -Wgt C:\chemin\tv-iptv.wgt

  Sans -Source ni -Wgt, le script télécharge la dernière version publiée sur GitHub.
  Prérequis : Tizen Studio (+ TV Extensions + Samsung Certificate Extension), un profil de
  certificat Samsung, et la TV en mode développeur avec l'IP de ce PC (voir tizen/README.md).
#>
param(
  [Parameter(Mandatory = $true)][string]$TvIp,
  [string]$CertProfile = "tviptv",
  [string]$Source = "",
  [string]$Wgt = "",
  [string]$TizenStudio = ""
)

$ErrorActionPreference = "Stop"
$AppId = "TvIptvNrob.IPTV"
$Repo = "nrobeveille/tv-iptv"

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

# --- Outils Tizen Studio
$candidates = @($TizenStudio, $env:TIZEN_STUDIO, "C:\tizen-studio", "$env:USERPROFILE\tizen-studio", "D:\tizen-studio") | Where-Object { $_ }
$home_ = $candidates | Where-Object { Test-Path (Join-Path $_ "tools\sdb.exe") } | Select-Object -First 1
if (-not $home_) { throw "Tizen Studio introuvable. Indique son dossier avec -TizenStudio C:\chemin\tizen-studio" }
$sdb = Join-Path $home_ "tools\sdb.exe"
$tizen = Join-Path $home_ "tools\ide\bin\tizen.bat"

$work = Join-Path $env:TEMP "tv-iptv-install"
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item $work -ItemType Directory | Out-Null
$target = Join-Path $work "tv-iptv.wgt"

# --- Paquet à installer
if ($Source) {
  Step "Empaquetage du dossier $Source"
  $app = Join-Path $work "app"
  New-Item $app -ItemType Directory | Out-Null
  foreach ($item in @("index.html", "config.xml", "icon.png", "css", "js")) {
    Copy-Item (Join-Path $Source $item) $app -Recurse
  }
  Compress-Archive -Path (Join-Path $app "*") -DestinationPath (Join-Path $work "tv-iptv.zip")
  Move-Item (Join-Path $work "tv-iptv.zip") $target
} elseif ($Wgt) {
  Copy-Item $Wgt $target
} else {
  Step "Recherche de la dernière version sur GitHub"
  $releases = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases?per_page=30" -Headers @{ "User-Agent" = "tv-iptv-installer" }
  $release = $releases | Where-Object { $_.tag_name -like "tizen-v*" } | Select-Object -First 1
  if (-not $release) { throw "Aucune version Tizen publiée sur GitHub." }
  $asset = $release.assets | Where-Object { $_.name -like "*.wgt" } | Select-Object -First 1
  Write-Host "Version $($release.tag_name)"
  Invoke-WebRequest $asset.browser_download_url -OutFile $target -Headers @{ "User-Agent" = "tv-iptv-installer" }
}

# --- Connexion à la TV
Step "Connexion à la TV ($TvIp)"
& $sdb connect "$($TvIp):26101" | Write-Host
$line = (& $sdb devices) | Where-Object { $_ -match [regex]::Escape($TvIp) -and $_ -match "device" } | Select-Object -First 1
if (-not $line) { throw "TV non connectée. Vérifie le mode développeur (IP de ce PC saisie sur la TV, TV redémarrée) et que la TV est allumée." }
$parts = ($line -split "\s+") | Where-Object { $_ }
$device = if ($parts.Count -ge 3) { $parts[2] } else { $parts[0] }
Write-Host "TV : $device"

# --- Signature avec ton certificat Samsung
Step "Signature avec le profil de certificat « $CertProfile »"
& $tizen package -t wgt -s $CertProfile -- $target
if ($LASTEXITCODE -ne 0) { throw "Signature impossible : vérifie le nom du profil (Tizen Studio > Tools > Certificate Manager)." }

# --- Installation et lancement
Step "Installation sur la TV"
& $tizen install -n $target -t $device
if ($LASTEXITCODE -ne 0) { throw "Installation refusée par la TV. Dans Tizen Studio : Device Manager > clic droit sur la TV > Permit to install applications, puis relance ce script. Si ça persiste : profil de certificat Samsung (pas Tizen) contenant le DUID de la TV." }

Step "Lancement de l'appli"
& $tizen run -p $AppId -t $device | Out-Null
Write-Host "`nC'est fait : « IPTV » est dans les applis de la TV." -ForegroundColor Green
