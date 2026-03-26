import os
import json
import sys
import time
import requests
import logging
import yaml

# Configure Logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger("CronJob-SmokeTest")

# Load Configuration from config.yaml
CONFIG_PATH = "config.yaml"
config = {}
if os.path.exists(CONFIG_PATH):
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            config = yaml.safe_load(f) or {}
    except Exception as e:
        logger.warning(f"⚠️ Could not load config.yaml: {str(e)}")

# Configurations
# Prioritize config.yaml, fallback to environment variables, then defaults
BASE_URL = config.get("app", {}).get("base_url") or os.getenv("BASE_URL", "http://localhost:10104")
BASE_URL = BASE_URL.rstrip('/')

AUTH_TOKEN = config.get("security", {}).get("api_key") or os.getenv("AUTH_TOKEN", "")

TEST_FILE = "tests/smoke-test.json"
MAX_RETRIES = 3
RETRY_DELAY = 5

def check_health():
    """Step 1: Health Check before running tests"""
    health_url = f"{BASE_URL}/health/ready"
    logger.info(f"Checking health at: {health_url}")
    
    for i in range(MAX_RETRIES):
        try:
            response = requests.get(health_url, timeout=10)
            if response.status_code == 200:
                data = response.json()
                if data.get("status") == "ready" and data.get("database") == "connected":
                    logger.info("✅ Health Check Passed: System is ready.")
                    return True
                else:
                    logger.warning(f"⚠️ Health check responded but system not ready: {data}")
            else:
                logger.warning(f"⚠️ Health check failed with status: {response.status_code}")
        except Exception as e:
            logger.error(f"❌ Connection error during health check: {str(e)}")
        
        if i < MAX_RETRIES - 1:
            logger.info(f"Retrying in {RETRY_DELAY}s...")
            time.sleep(RETRY_DELAY)
            
    return False

def run_smoke_tests():
    """Step 2: Run tests from Postman collection (smoke-test.json)"""
    if not os.path.exists(TEST_FILE):
        logger.error(f"Test file not found: {TEST_FILE}")
        return False

    with open(TEST_FILE, 'r') as f:
        collection = json.load(f)

    items = collection.get('item', [])
    logger.info(f"Found {len(items)} test cases in {TEST_FILE}")
    
    all_passed = True
    
    for item in items:
        name = item.get('name')
        request_data = item.get('request', {})
        method = request_data.get('method', 'GET')
        url_obj = request_data.get('url', {})
        
        # Replace {{BASE_URL}} and path components
        raw_url = url_obj.get('raw', '')
        url = raw_url.replace('{{BASE_URL}}', BASE_URL).replace('{{auth_token}}', AUTH_TOKEN)
        
        # Headers
        headers = {h['key']: h['value'] for h in request_data.get('header', []) if h.get('key') and not h.get('disabled')}
        
        # Auth Handling
        auth = request_data.get('auth', {})
        if auth.get('type') == 'bearer' and AUTH_TOKEN:
            headers['Authorization'] = f"Bearer {AUTH_TOKEN}"
        elif 'Authorization' not in headers and AUTH_TOKEN:
            # Fallback if no explicit auth type but token exists
            headers['Authorization'] = f"Bearer {AUTH_TOKEN}"

        # Body
        body = None
        if request_data.get('body', {}).get('mode') == 'raw':
            body_raw = request_data['body']['raw']
            try:
                body = json.loads(body_raw)
            except:
                body = body_raw

        logger.info(f"Running Test: [{name}] {method} {url}")
        
        try:
            start_time = time.time()
            if method == 'GET':
                resp = requests.get(url, headers=headers, timeout=15)
            elif method == 'POST':
                resp = requests.post(url, headers=headers, json=body, timeout=15)
            else:
                logger.warning(f"Unsupported method {method}, skipping...")
                continue
            
            elapsed = (time.time() - start_time) * 1000
            
            # Basic validation: status code should be 2xx
            if 200 <= resp.status_code < 300:
                logger.info(f"✅ PASS: {name} (Status: {resp.status_code}, Time: {elapsed:.2f}ms)")
            else:
                logger.error(f"❌ FAIL: {name} (Status: {resp.status_code})")
                logger.error(f"   Response: {resp.text[:200]}")
                all_passed = False
                
        except Exception as e:
            logger.error(f"❌ ERROR: {name} - {str(e)}")
            all_passed = False

    return all_passed

if __name__ == "__main__":
    logger.info("🚀 Starting CronJob Smoke Test")
    
    if not check_health():
        logger.error("🚫 System not ready after retries. Aborting tests.")
        sys.exit(1)
        
    success = run_smoke_tests()
    
    if success:
        logger.info("🎉 All Smoke Tests PASSED!")
        sys.exit(0)
    else:
        logger.error("🛑 Some Smoke Tests FAILED!")
        sys.exit(1)
