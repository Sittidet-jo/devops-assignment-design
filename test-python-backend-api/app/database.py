from pymongo import MongoClient
from app.config import settings

mongo_cfg = settings['mongodb']

MONGO_URI = f"mongodb://{mongo_cfg['username']}:{mongo_cfg['password']}@{mongo_cfg['server']}/?authSource={mongo_cfg['authsource']}"

client = MongoClient(MONGO_URI, serverSelectionTimeoutMS=2000)
db = client["workshop_test_db"]
