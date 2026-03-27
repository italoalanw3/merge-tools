@echo off
echo ============================================
echo   Git Merge Tool - Criando executavel .exe
echo ============================================
echo.

REM Verificar se Python está instalado
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERRO] Python nao encontrado!
    echo Instale Python em https://python.org
    echo Marque "Add Python to PATH" durante a instalacao.
    pause
    exit /b 1
)

echo [1/3] Instalando PyInstaller...
pip install pyinstaller --quiet

echo.
echo [2/3] Gerando executavel...
pyinstaller --onefile --windowed --name "GitMergeTool" --clean git_merge_tool.py

echo.
echo [3/3] Pronto!
echo.
echo ============================================
echo   Seu .exe esta em:  dist\GitMergeTool.exe
echo ============================================
echo.
echo Voce pode copiar o GitMergeTool.exe para
echo qualquer pasta e executar com duplo clique!
echo.
pause
