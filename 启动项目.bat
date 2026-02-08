@echo off
chcp 65001 >nul
echo ========================================
echo   SmartEdu Agent 智能教学助手
echo ========================================
echo.

echo [提示] 请确保 MySQL 数据库已启动
echo        数据库名: smart_edu_agent
echo        如果数据库不存在，应用会自动创建表结构
echo.

echo [启动] Spring Boot 应用启动中...
echo.
echo 访问地址：
echo   智能教学助手: http://localhost:8080/teaching.html
echo   对话助手:     http://localhost:8080/index.html
echo   笔记助手:     http://localhost:8080/note.html
echo   学情分析:     http://localhost:8080/analysis.html
echo.
echo ========================================
echo.

java -jar target\smart-edu-agent-0.0.1-SNAPSHOT.jar

echo.
echo ========================================
echo 应用已停止
echo ========================================
pause
