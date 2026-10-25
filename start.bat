@echo off
chcp 65001 >nul
cd /d %~dp0

rem ===== RAG Study Helper 启动脚本（本机环境）=====
rem 用法：双击本文件即可。关闭黑窗口 = 停止应用。

set SPRING_DATASOURCE_PASSWORD=SSC163425
set APP_RAG_CHAT_API_KEY=sk-1789f5fef3b5407d966043fec5ce62b5
set APP_RAG_EMBEDDING_API_KEY=sk-rnqlwrpejjzgnteltynbzsibilsvwtsuoogfcuepppbuyrbz

rem ===== 定位 java：优先用本机固定安装的 JDK 17，找不到再退回 PATH =====
set JAVA_EXE=java
if exist "C:\Program Files\Java\jdk-17.0.19\bin\java.exe" (
    set "JAVA_EXE=C:\Program Files\Java\jdk-17.0.19\bin\java.exe"
) else (
    where java >nul 2>nul
    if errorlevel 1 (
        echo [错误] 找不到 java。请安装 JDK 17 或修改本脚本中的 JAVA_EXE 路径。
        pause >nul
        exit /b 1
    )
)

echo ============================================
echo  RAG Study Helper
echo  MySQL   : localhost:3306 / rag_study_helper
echo  Redis   : localhost:6379 （需先启动 Redis）
echo  Web     : http://localhost:8083
echo ============================================
echo.

"%JAVA_EXE%" -jar target\rag-study-helper-0.0.1-SNAPSHOT.jar

echo.
echo ============================================
echo  应用已退出，按任意键关闭窗口
echo ============================================
pause >nul
