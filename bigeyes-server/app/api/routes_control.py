import logging
from fastapi import APIRouter, HTTPException
from typing import List
from app.dlna.models import DlnaDevice
from app.proxy.models import ControlRequest, SelectDeviceRequest
from app.dlna.device_manager import device_manager
from app.dlna.controller import dlna_controller

logger = logging.getLogger(__name__)
router = APIRouter()

@router.get("/api/devices", response_model=List[DlnaDevice])
async def get_devices():
    """Returns currently discovered DLNA devices on the LAN."""
    return device_manager.get_devices()

@router.post("/api/devices/refresh", response_model=List[DlnaDevice])
async def refresh_devices():
    """Forces an immediate SSDP discovery scan."""
    return await device_manager.scan_once()

@router.post("/api/select_device")
async def select_device(req: SelectDeviceRequest):
    """Sets the target DLNA device for casting."""
    ok = device_manager.select_device(req.device_id)
    if not ok:
        raise HTTPException(status_code=404, detail="Device not found")
    return {"status": "ok", "selected_device_id": req.device_id}

@router.post("/api/control")
async def control_playback(req: ControlRequest):
    """
    Sends playback commands (play, pause, stop, seek) to the currently selected DLNA TV.
    """
    target = device_manager.get_selected_device()
    if not target or not target.av_transport_control_url:
        raise HTTPException(status_code=400, detail="No active DLNA TV device available")

    ctrl_url = target.av_transport_control_url
    action = req.action.lower()
    success = False

    if action == "play":
        success = await dlna_controller.play(ctrl_url)
    elif action == "pause":
        success = await dlna_controller.pause(ctrl_url)
    elif action == "stop":
        success = await dlna_controller.stop(ctrl_url)
    elif action == "seek":
        if req.position is None:
            raise HTTPException(status_code=400, detail="Seek action requires 'position' parameter")
        success = await dlna_controller.seek(ctrl_url, req.position)
    else:
        raise HTTPException(status_code=400, detail=f"Unsupported control action: {req.action}")

    return {"status": "ok" if success else "error", "action": action, "success": success}
