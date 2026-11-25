@echo off
rem Create a Windows shortcut for ZooInspector, carrying the app icon (ZooInspector.ico).
rem ASCII only on purpose: cmd mis-parses a UTF-8 .bat with non-ASCII text. See README.
rem
rem   create-shortcut.bat                     shortcut on the Desktop
rem   create-shortcut.bat "D:\my\folder"      shortcut in that folder
rem
rem A .bat cannot carry an icon of its own - only a shortcut can, which is why this exists.
rem When a bundled runtime is present the shortcut points straight at javaw.exe, so not even
rem a console flash appears; otherwise it points at ZooInspector.bat, started minimized.
rem
rem Paths reach PowerShell through environment variables, never inline in the -Command
rem string: a path ending in a backslash next to a quote breaks PowerShell's parsing.

setlocal EnableExtensions
set "DIR=%~dp0"
set "ZI_DEST=%~1"
set "JAR="

if exist "%DIR%zookeeper-dev-ZooInspector.jar" set "JAR=%DIR%zookeeper-dev-ZooInspector.jar"
if not defined JAR if exist "%DIR%dist\zookeeper-dev-ZooInspector.jar" set "JAR=%DIR%dist\zookeeper-dev-ZooInspector.jar"
if not defined JAR if exist "%DIR%target\zookeeper-dev-ZooInspector.jar" set "JAR=%DIR%target\zookeeper-dev-ZooInspector.jar"
if not defined JAR goto nojar
for %%I in ("%JAR%") do set "JARDIR=%%~dpI"

set "ICO=%DIR%ZooInspector.ico"
if not exist "%ICO%" if exist "%JARDIR%ZooInspector.ico" set "ICO=%JARDIR%ZooInspector.ico"
if not exist "%ICO%" goto noico

rem Prefer javaw.exe from the bundled runtime: no console window at all.
set "TARGET="
set "ZI_MODE=bat"
if exist "%JARDIR%runtime\bin\javaw.exe" set "TARGET=%JARDIR%runtime\bin\javaw.exe"
if not defined TARGET if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "TARGET=%JAVA_HOME%\bin\javaw.exe"
if defined TARGET (
	set "ZI_MODE=javaw"
) else (
	if not exist "%DIR%ZooInspector.bat" goto nobat
	set "TARGET=%DIR%ZooInspector.bat"
)

set "ZI_JAR=%JAR%"
set "ZI_TARGET=%TARGET%"
set "ZI_WORK=%JARDIR%"
set "ZI_ICON=%ICO%"

powershell -NoProfile -Command "$d=$env:ZI_DEST; if(-not $d){$d=[Environment]::GetFolderPath('Desktop')}; if(-not (Test-Path -LiteralPath $d)){New-Item -ItemType Directory -Path $d -Force | Out-Null}; $p=Join-Path $d 'ZooInspector.lnk'; $s=(New-Object -ComObject WScript.Shell).CreateShortcut($p); $s.TargetPath=$env:ZI_TARGET; if($env:ZI_MODE -eq 'javaw'){$s.Arguments='-jar ' + [char]34 + $env:ZI_JAR + [char]34; $s.WindowStyle=1}else{$s.WindowStyle=7}; $s.WorkingDirectory=$env:ZI_WORK; $s.IconLocation=$env:ZI_ICON + ',0'; $s.Description='ZooInspector - ZooKeeper desktop client'; $s.Save(); Write-Host ('Created ' + $p); Write-Host ('Target  ' + $s.TargetPath + ' ' + $s.Arguments)"
if errorlevel 1 goto psfail
endlocal & exit /b 0

:nojar
echo Cannot find zookeeper-dev-ZooInspector.jar. Build it first: mvn package 1>&2
goto fail

:noico
echo Cannot find ZooInspector.ico next to this file. 1>&2
echo Regenerate it with the IconExport dev tool - see README. 1>&2
goto fail

:nobat
echo No bundled runtime, no JAVA_HOME javaw.exe, and no ZooInspector.bat to fall back to. 1>&2
echo Run mvn package -Pbundle-runtime, or set JAVA_HOME. 1>&2
goto fail

:psfail
echo PowerShell could not create the shortcut. 1>&2
goto fail

:fail
echo("%cmdcmdline%" | find /i "%~nx0" >nul 2>&1 && pause
endlocal & exit /b 1