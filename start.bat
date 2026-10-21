@echo off
chcp 65001 >nul
cd /d %~dp0

rem ===== RAG Study Helper 启动脚本（本机环境）=====
rem 用法：双击本文件即可。关闭黑窗口 = 停止应用。

set SPRING_DATASOURCE_PASSWORD=SSC163425
set APP_RAG_CHAT_API_KEY=sk-1789f5fef3b5407d966043fec5ce62b5
set APP_RAG_EMBEDDING_API_KEY=sk-rnqlwrpejjzgnteltynbzsibilsvwtsuoogfcuepppbuyrbz

echo ============================================
echo  RAG Study Helper
echo  MySQL   : localhost:3306 / rag_study_helper
echo  Redis   : localhost:6379 （需先启动 Redis）
echo  Web     : http://localhost:8083
echo ============================================
echo.

java -jar target\rag-study-helper-0.0.1-SNAPSHOT.jar

echo.
echo ============================================
echo  应用已退出，按任意键关闭窗口
echo ============================================
pause >nul
