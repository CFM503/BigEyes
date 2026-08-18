from pydantic import BaseModel
from typing import Optional
import time

class DlnaDevice(BaseModel):
    id: str  # UDN or UUID
    name: str  # Friendly Name (e.g. "Living Room TV")
    ip: str
    location_url: str
    av_transport_control_url: Optional[str] = None
    rendering_control_url: Optional[str] = None
    selected: bool = False
    last_seen: float = 0.0
