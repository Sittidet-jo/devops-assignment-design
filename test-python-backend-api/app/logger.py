import os
import logging
from logging.handlers import RotatingFileHandler

# Configuration for log directory and file path
LOG_DIR = os.getenv("LOG_DIR", "/app/logs")
LOG_FILE = os.path.join(LOG_DIR, "app.log")

# Ensure the log directory exists
if not os.path.exists(LOG_DIR):
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
    except Exception as e:
        print(f"Error creating log directory: {e}")

# Set up logging format
log_format = logging.Formatter(
    "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)

# Initialize logger
logger = logging.getLogger("test-api-logger")
logger.setLevel(logging.INFO)

# Console Handler (Stdout)
console_handler = logging.StreamHandler()
console_handler.setFormatter(log_format)
logger.addHandler(console_handler)

# File Handler (For PVC Testing)
try:
    file_handler = RotatingFileHandler(
        LOG_FILE, maxBytes=10*1024*1024, backupCount=5
    )
    file_handler.setFormatter(log_format)
    logger.addHandler(file_handler)
    logger.info(f"Logging initialized at: {LOG_FILE}")
except Exception as e:
    logger.error(f"Failed to initialize file logging: {e}")

def get_logger():
    return logger
