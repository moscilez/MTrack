# Regenerates Android resources from the supplied logo without stretching it.
# Run from any directory: powershell -File tools/Generate-Icons.ps1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$resourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../app/src/main/res'))
$logoPath = Join-Path $resourceRoot 'drawable/mtrack_logo.png'
$logoSource = [Drawing.Image]::FromFile($logoPath)
function New-LogoBitmap([int]$size) {
 $bitmap = New-Object Drawing.Bitmap($size, $size)
 $graphics = [Drawing.Graphics]::FromImage($bitmap)
 $graphics.Clear([Drawing.Color]::White)
 $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
 $scale = [Math]::Min($size / $logoSource.Width, $size / $logoSource.Height)
 $width = [int]($logoSource.Width * $scale)
 $height = [int]($logoSource.Height * $scale)
 $graphics.DrawImage($logoSource,[int](($size-$width)/2),[int](($size-$height)/2),$width,$height)
 $graphics.Dispose()
 return ,$bitmap
}
try {
 $sizes = @{mdpi=48;hdpi=72;xhdpi=96;xxhdpi=144;xxxhdpi=192}
 foreach ($entry in $sizes.GetEnumerator()) {
  $directory = Join-Path $resourceRoot ('mipmap-' + $entry.Key)
  New-Item -ItemType Directory -Force $directory | Out-Null
  $bitmap = New-LogoBitmap $entry.Value
  $bitmap.Save((Join-Path $directory 'ic_launcher.png'),[Drawing.Imaging.ImageFormat]::Png)
  $bitmap.Dispose()
 }
 # The Android 13 themed icon uses the actual logo silhouette as an alpha mask.
 Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @"
using System.Drawing;
public static class LogoMask {
 public static void Convert(Bitmap bitmap) {
  for(int y=0;y<bitmap.Height;y++) for(int x=0;x<bitmap.Width;x++) {
   Color p=bitmap.GetPixel(x,y);
   int alpha=(255-(p.R+p.G+p.B)/3)*p.A/255;
   bitmap.SetPixel(x,y,Color.FromArgb(alpha,0,0,0));
  }
 }
}
"@
 $mask = New-LogoBitmap 432
 [LogoMask]::Convert($mask)
 $directory = Join-Path $resourceRoot 'drawable-nodpi'
 New-Item -ItemType Directory -Force $directory | Out-Null
 $mask.Save((Join-Path $directory 'ic_launcher_monochrome.png'),[Drawing.Imaging.ImageFormat]::Png)
 $mask.Dispose()
} finally { $logoSource.Dispose() }
