#!/bin/bash
set -e

# รับค่าจาก Environment Variables
PYTHON_BIN=${PYTHON_BIN:-"python3"}
REQ_FILE=${REQ_FILE:-"requirements.txt"}
LINT_TOOLS=${LINT_TOOLS:-"flake8"}
TEST_TOOLS=${TEST_TOOLS:-"pytest pytest-cov"}
AUDIT_TOOLS=${AUDIT_TOOLS:-"pip-audit"}
# VENV_DIR จะถูกส่งมาจาก Groovy เพื่อให้ Jenkins รู้ path ด้วย

echo "[Python-Setup] Requirements: $REQ_FILE"
echo "[Python-Setup] Target Venv: $VENV_DIR"

# Setup Pyenv (ถ้ามี)
export PYENV_ROOT="/opt/pyenv"
export PATH="$PYENV_ROOT/bin:$PATH"
if command -v pyenv 1>/dev/null 2>&1; then
    eval "$(pyenv init -)"
fi

# สร้าง Venv ถ้ายังไม่มี
if [ ! -d "$VENV_DIR" ]; then
    echo "Creating virtualenv with $PYTHON_BIN..."
    if ! $PYTHON_BIN -m venv "$VENV_DIR"; then
        echo "Standard venv failed, retrying without pip..."
        $PYTHON_BIN -m venv "$VENV_DIR" --without-pip
        curl -sS https://bootstrap.pypa.io/get-pip.py -o get-pip.py
        "$VENV_DIR/bin/python" get-pip.py
        rm -f get-pip.py
    fi
else
    echo "Reusing existing virtualenv."
fi

# Activate & Install
source "$VENV_DIR/bin/activate"
export PIP_NO_INPUT=1

echo "Upgrading pip..."
python -m pip install --upgrade pip setuptools wheel >/dev/null

if [ -f "$REQ_FILE" ]; then
    echo "Installing dependencies..."
    pip install -r "$REQ_FILE"
else
    echo "[WARN] $REQ_FILE not found."
fi

echo "Installing tools: $LINT_TOOLS $AUDIT_TOOLS $TEST_TOOLS"
pip install $LINT_TOOLS $AUDIT_TOOLS $TEST_TOOLS >/dev/null