@echo off
rem ZooInspector launcher for Windows - double-click to open the UI, no console left behind.
rem ASCII only on purpose: cmd mis-parses a UTF-8 .bat with non-ASCII text (byte offsets
rem drift after a multi-byte line), and chcp 65001 does not fix it. See README for Chinese docs.
rem
rem Keep this file next to the jar (dist\): copying the whole folder to another machine works.
rem
rem   double-click                                      open the UI (javaw, no console)
rem   ZooInspector.bat --connect 10.0.0.1:2181/myapp    connect on startup
rem   ZooInspector.bat --dark                           dark theme
rem   ZooInspector.bat --console                        stay in the console, show logs
rem   ZooInspector.bat --help                           application usage
rem
rem Java lookup order: runtime\ beside the jar -> runtime\ beside this file -> JAVA_HOME -> PATH.
rem Build with -Pbundle-runtime to get dist\runtime\, then no system Java is needed.
rem Extra JVM options: set ZOOINSPECTOR_OPTS=-Xmx1g

setlocal EnableExtensions
set "DIR=%~dp0"
set "JAR="

rem Prefer the jar beside this file; also work from the repo root.
if exist "%DIR%zookeeper-dev-ZooInspector.jar" set "JAR=%DIR%zookeeper-dev-ZooInspector.jar"
if not defined JAR if exist "%DIR%dist\zookeeper-dev-ZooInspector.jar" set "JAR=%DIR%dist\zookeeper-dev-ZooInspector.jar"
if not defined JAR if exist "%DIR%target\zookeeper-dev-ZooInspector.jar" set "JAR=%DIR%target\zookeeper-dev-ZooInspector.jar"
if not defined JAR goto nojar

rem A bundled runtime travels with the jar, so resolve it from the jar's own folder.
for %%I in ("%JAR%") do set "JARDIR=%%~dpI"

set "BIN="
if exist "%JARDIR%runtime\bin\javaw.exe" set "BIN=%JARDIR%runtime\bin\"
if not defined BIN if exist "%DIR%runtime\bin\javaw.exe" set "BIN=%DIR%runtime\bin\"
if not defined BIN if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "BIN=%JAVA_HOME%\bin\"
if not defined BIN for %%I in (javaw.exe) do if not "%%~$PATH:I"=="" set "BIN=%%~dp$PATH:I"
if not defined BIN for %%I in (java.exe) do if not "%%~$PATH:I"=="" set "BIN=%%~dp$PATH:I"
if not defined BIN goto nojava

rem Re-quote each argument so spaces survive; --console is ours, not the app's.
set "CONSOLE="
set "ARGS="
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--console" (set "CONSOLE=1") else (set ARGS=%ARGS% "%~1")
if /i "%~1"=="--help" set "CONSOLE=1"
if /i "%~1"=="-h" set "CONSOLE=1"
shift
goto parse
:parsed

rem java.exe without javaw.exe cannot start silently - fall back to console mode.
if not exist "%BIN%javaw.exe" set "CONSOLE=1"

rem Run from the jar's folder: config\ defaults to the folder holding the jar.
cd /d "%JARDIR%"

if defined CONSOLE (
	"%BIN%java.exe" %ZOOINSPECTOR_OPTS% -jar "%JAR%" %ARGS%
	call set "RC=%%ERRORLEVEL%%"
) else (
	start "" "%BIN%javaw.exe" %ZOOINSPECTOR_OPTS% -jar "%JAR%" %ARGS%
	set "RC=0"
)

endlocal & exit /b %RC%

:nojar
echo Cannot find zookeeper-dev-ZooInspector.jar. 1>&2
echo Put this file next to the jar, or build it first: mvn package 1>&2
goto fail

:nojava
echo Cannot find a Java runtime (javaw.exe / java.exe). 1>&2
echo Install JDK/JRE 8+ and set JAVA_HOME, or build a bundled runtime: mvn package -Pbundle-runtime 1>&2
goto fail

:fail
rem A double-click window vanishes at once - pause so the message can be read.
echo("%cmdcmdline%" | find /i "%~nx0" >nul 2>&1 && pause
endlocal & exit /b 1