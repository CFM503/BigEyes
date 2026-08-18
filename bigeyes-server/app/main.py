import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.config import settings
from app.discovery.mdns import mdns_service
from app.dlna.device_manager import device_manager
from app.api.routes_cast import router as cast_router
from app.api.routes_control import router as control_router
from app.api.routes_status import router as status_router
from app.utils.network import get_lan_ip

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("bigeyes.server")

@asynccontextmanager
async def lifespan(app: FastAPI):
    lan_ip = get_lan_ip()
    logger.info(f"==================================================")
    logger.info(f" Starting BigEyes PC Server on {lan_ip}:{settings.PORT}")
    logger.info(f" Cache Directory: {settings.CACHE_DIR}")
    logger.info(f"==================================================")
    
    # 1. Start mDNS broadcast
    mdns_service.start(port=settings.PORT)
    
    # 2. Start DLNA device background discovery
    device_manager.start_background_scan()
    # Trigger initial scan immediately
    await device_manager.scan_once()
    
    yield
    
    # Teardown
    logger.info("Shutting down BigEyes PC Server...")
    mdns_service.stop()
    device_manager.stop()

app = FastAPI(
    title="BigEyes PC Server",
    version="1.0.1",
    lifespan=lifespan,
)

# CORS middleware for open web/app access
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include routers
app.include_router(cast_router)
app.include_router(control_router)
app.include_router(status_router)

@app.get("/")
async def root():
    return {
        "service": "BigEyes PC Server",
        "version": "1.0.1",
        "lan_ip": get_lan_ip(),
        "port": settings.PORT,
    }
