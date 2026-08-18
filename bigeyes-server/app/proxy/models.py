from pydantic import BaseModel, Field
from typing import List, Dict, Optional
import time

class KeyItem(BaseModel):
    index: int
    method: str
    uri: str
    iv: Optional[str] = None
    key_format: Optional[str] = None
    key_format_versions: Optional[str] = None

class SegmentItem(BaseModel):
    index: int
    uri: str
    duration: float = 0.0
    title: Optional[str] = None
    key_index: Optional[int] = None
    byte_range: Optional[str] = None

class VariantItem(BaseModel):
    index: int
    bandwidth: int = 0
    resolution: Optional[str] = None
    codecs: Optional[str] = None
    uri: str

class StreamSession(BaseModel):
    stream_id: str
    original_url: str
    referer: Optional[str] = None
    user_agent: Optional[str] = None
    cookie: Optional[str] = None
    title: Optional[str] = None
    created_at: float = Field(default_factory=time.time)
    
    is_master: bool = False
    variants: List[VariantItem] = Field(default_factory=list)
    selected_variant_index: int = 0
    
    segments: List[SegmentItem] = Field(default_factory=list)
    keys: List[KeyItem] = Field(default_factory=list)
    
    last_accessed_seg: int = 0
    is_live: bool = False
    target_duration: float = 10.0
    media_sequence: int = 0

class CastRequest(BaseModel):
    url: str
    referer: Optional[str] = None
    user_agent: Optional[str] = None
    cookie: Optional[str] = None
    title: Optional[str] = None

class ControlRequest(BaseModel):
    action: str  # play | pause | stop | seek
    position: Optional[str | int | float] = None

class SelectDeviceRequest(BaseModel):
    device_id: str

class PairRequest(BaseModel):
    code: str
