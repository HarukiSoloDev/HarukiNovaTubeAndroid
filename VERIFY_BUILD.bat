@echo off
setlocal
cd /d "%~dp0"
echo ==============================================
echo Haruki NovaTube Android v0.8.2 - Full Build Check
echo ==============================================
echo.

where java >nul 2>nul
if errorlevel 1 (
  if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
    set "PATH=%JAVA_HOME%\bin;%PATH%"
  ) else if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
    set "PATH=%JAVA_HOME%\bin;%PATH%"
  )
)

where java >nul 2>nul
if errorlevel 1 (
  echo ERROR: Java was not found.
  echo Open Android Studio ^> Settings ^> Build Tools ^> Gradle and select the Embedded JDK,
  echo or set JAVA_HOME to Android Studio's jbr folder, then rerun this file.
  goto :fail
)

where python >nul 2>nul
if %errorlevel%==0 (
  echo [1/2] Running source preflight...
  python tools\preflight.py
  if errorlevel 1 goto :fail
) else (
  echo [1/2] Python not found - skipping optional source preflight.
)

echo [2/2] Building debug APK with Gradle...
call gradlew.bat :app:assembleDebug
if errorlevel 1 goto :fail

echo.
echo BUILD CHECK PASSED.
echo APK: app\build\outputs\apk\debug\app-debug.apk
pause
exit /b 0

:fail
echo.
echo BUILD CHECK FAILED. Copy the FIRST red compiler error and send it for fixing.
pause
exit /b 1
