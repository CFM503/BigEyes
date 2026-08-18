import logging
from fastapi import APIRouter
from app.config import settings
from app.proxy.models import PairRequest
from app.proxy.stream_manager import stream_manager
from app.dlna.device_manager import device_manager
from app.dlna.controller import dlna_controller

logger = logging.getLogger(__name__)
router = APIRouter()

@router.get("/api/status")
async def get_status():
    """
    Returns current playback status:
    - active stream info (title, id)
    - active device info
    - TV transport state (PLAYING, PAUSED, STOPPED)
    - position (rel_time) and track_duration
    """
    active_session = stream_manager.get_active_session()
    selected_device = device_manager.get_selected_device()

    transport_state = "IDLE"
    transport_status = "OK"
    rel_time = "00:00:00"
    duration = "00:00:00"

    if selected_device and selected_device.av_transport_control_url:
        try:
            pos_info = await dlna_controller.get_position_info(selected_device.av_transport_control_url)
            trans_info = await dlna_controller.get_transport_info(selected_device.av_transport_control_url)
            rel_time = pos_info.get("rel_time", "00:00:00")
            duration = pos_info.get("track_duration", "00:00:00")
            transport_state = trans_info.get("current_transport_state", "STOPPED")
            transport_status = trans_info.get("current_transport_status", "OK")
        except Exception as e:
            logger.debug(f"Failed querying TV status: {e}")

    return {
        "status": "ok",
        "has_active_stream": active_session is not None,
        "stream_id": active_session.stream_id if active_session else None,
        "title": active_session.title if active_session else None,
        "device": selected_device.name if selected_device else None,
        "device_ip": selected_device.ip if selected_device else None,
        "state": transport_state.lower(),
        "transport_status": transport_status,
        "position": rel_time,
        "duration": duration,
    }

@router.post("/api/pair")
async def pair_device(req: PairRequest):
    """
    Simple pairing endpoint for Android clients to exchange code for token.
    """
    return {
        "status": "ok",
        "token": settings.PAIR_TOKEN_SECRET,
        "message": "Paired successfully with BigEyes PC Server",
    }
