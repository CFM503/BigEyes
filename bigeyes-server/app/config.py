import os
from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="BIGEYES_", extra="ignore")

    HOST: str = "0.0.0.0"
    PORT: int = 8765
    SERVICE_NAME: str = "BigEyes-Server"
    MDNS_TYPE: str = "_bigeyes._tcp.local."
    
    # Cache settings
    CACHE_DIR: Path = Path.home() / ".bigeyes" / "cache"
    MAX_CACHE_SIZE_BYTES: int = 2 * 1024 * 1024 * 1024  # 2 GB
    
    # Prefetch settings
    PREFETCH_WINDOW: int = 5  # Fetch N+1 to N+5 segments
    PREFETCH_CONCURRENCY: int = 4  # Concurrency limit
    
    # Retry settings
    MAX_RETRIES: int = 3
    RETRY_BACKOFFS: list[float] = [0.5, 1.0, 2.0]
    
    # Network / Overrides
    LAN_IP_OVERRIDE: str | None = None
    
    # Auth (MVP default disabled or simple token)
    PAIR_TOKEN_SECRET: str = "bigeyes-secret-token"

settings = Settings()
settings.CACHE_DIR.mkdir(parents=True, exist_ok=True)
