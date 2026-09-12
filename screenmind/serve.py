"""
ScreenMind Server Runner
Starts the FastAPI server on 0.0.0.0:8765 with full AquaScope integration.
"""
import logging
import uvicorn
from screenmind.storage.database import Database
from screenmind.api.server import create_app
from screenmind.config import settings

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("screenmind.serve")

def main():
    logger.info("Initializing ScreenMind database and application...")
    db = Database()
    app = create_app(database=db)
    
    host = "0.0.0.0"
    port = 8765
    logger.info(f"Starting ScreenMind API Server on http://{host}:{port} (LAN & Localhost accessible)")
    uvicorn.run(app, host=host, port=port, log_level="info")

if __name__ == "__main__":
    main()
