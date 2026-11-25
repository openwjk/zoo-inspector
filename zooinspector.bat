@echo off
rem ZooInspector launcher (Windows cmd / PowerShell).
rem ASCII only on purpose: a UTF-8 .bat with non-ASCII comments gets mis-parsed
rem under the console's OEM code page.
rem
rem   .\zooinspector.bat                                 open the UI
rem   .\zooinspector.bat --connect 10.0.0.1:2181/myapp    connect on startup
rem   .\zooinspector.bat --dark                          dark theme
rem
rem Java lookup order: bundled runtime next to the jar -> JAVA_HOME -> PATH.
rem Build with -Pbundle-runtime to get dist\runtime\, then no system Java is needed.

setlocal
set DIR=%~dp0
set JAR=

if exist "%DIR%dist\zookeeper-dev-ZooInspector.jar" set JAR=%DIR%dist\zookeeper-dev-ZooInspector.jar
if not defined JAR if exist "%DIR%target\zookeeper-dev-ZooInspector.jar" set JAR=%DIR%target\zookeeper-dev-ZooInspector.jar
if not defined JAR if exist "%DIR%zookeeper-dev-ZooInspector.jar" set JAR=%DIR%zookeeper-dev-ZooInspector.jar

if not defined JAR (
	echo Cannot find zookeeper-dev-ZooInspector.jar. Build it first: mvn package 1>&2
	exit /b 1
)

rem Resolve the folder that actually holds the jar, so a bundled runtime beside it wins.
for %%I in ("%JAR%") do set JARDIR=%%~dpI

set JAVA=
if exist "%JARDIR%runtime\bin\java.exe" set JAVA=%JARDIR%runtime\bin\java.exe
if not defined JAVA if exist "%DIR%runtime\bin\java.exe" set JAVA=%DIR%runtime\bin\java.exe
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set JAVA=%JAVA_HOME%\bin\java.exe
if not defined JAVA set JAVA=java

"%JAVA%" %ZOOINSPECTOR_OPTS% -jar "%JAR%" %*
endlocal