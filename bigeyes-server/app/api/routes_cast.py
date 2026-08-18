import logging
from fastapi import APIRouter, HTTPException, Request, Response
from fastapi.responses import PlainTextResponse, Response
from app.config import settings
from app.utils.network import get_lan_ip
from app.proxy.models import CastRequest
from app.proxy.stream_manager import stream_manager
from app.dlna.device_manager import device_manager
from app.dlna.controller import dlna_controller

logger = logging.getLogger(__name__)
router = APIRouter()

@router.post("/api/cast")
async def api_cast(req: CastRequest, request: Request):
    """
    Receives sniffed m3u8 context from Android App, prepares the local
    proxy stream, and initiates DLNA playback on the selected TV.
    """
    try:
        session = await stream_manager.create_session(
            url=req.url,
            referer=req.referer,
            user_agent=req.user_agent,
            cookie=req.cookie,
            title=req.title,
        )
    except Exception as e:
        logger.error(f"Failed to create stream session: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Failed to load stream: {e}")

    lan_ip = get_lan_ip()
    port = settings.PORT
    proxy_url = f"http://{lan_ip}:{port}/stream/{session.stream_id}/index.m3u8"
    logger.info(f"Stream prepared. Local proxy URL: {proxy_url}")

    # Find target DLNA device
    target_device = device_manager.get_selected_device()
    cast_success = False
    device_name = None

    if target_device and target_device.av_transport_control_url:
        device_name = target_device.name
        logger.info(f"Initiating DLNA cast to '{device_name}' at {target_device.av_transport_control_url}")
        ok_set = await dlna_controller.set_av_transport_uri(
            control_url=target_device.av_transport_control_url,
            uri=proxy_url,
            title=req.title or "BigEyes Video",
        )
        if ok_set:
            ok_play = await dlna_controller.play(target_device.av_transport_control_url)
            cast_success = ok_play
            if not ok_play:
                logger.warning(f"SetAVTransportURI succeeded but Play failed on '{device_name}'")
        else:
            logger.warning(f"Failed to set AVTransport URI on '{device_name}'")
    else:
        logger.warning("No DLNA TV selected or found on LAN. Video stream is available via proxy_url.")

    return {
        "status": "ok",
        "stream_id": session.stream_id,
        "proxy_url": proxy_url,
        "device": device_name,
        "cast_success": cast_success,
    }

@router.get("/stream/{stream_id}/index.m3u8")
async def get_stream_m3u8(stream_id: str, request: Request):
    """
    Returns the rewritten HLS playlist where all segments and keys
    point to this server's proxy endpoints.
    """
    lan_ip = get_lan_ip()
    port = settings.PORT
    server_base_url = f"http://{lan_ip}:{port}"

    try:
        rewritten_text = stream_manager.get_rewritten_m3u8(stream_id, server_base_url)
        return Response(
            content=rewritten_text,
            media_type="application/vnd.apple.mpegurl",
            headers={
                "Access-Control-Allow-Origin": "*",
                "Cache-Control": "no-cache, no-store, must-revalidate",
            },
        )
    except KeyError:
        raise HTTPException(status_code=404, detail="Stream session not found")
    except Exception as e:
        logger.error(f"Error serving m3u8 for {stream_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/stream/{stream_id}/seg/{seg_index}")
@router.get("/stream/{stream_id}/seg/{seg_index}.ts")
async def get_stream_segment(stream_id: str, seg_index: int):
    """
    Returns the binary TS video segment data, fetching or retrieving from cache.
    """
    try:
        data = await stream_manager.get_segment(stream_id, seg_index)
        return Response(
            content=data,
            media_type="video/mp2t",
            headers={
                "Access-Control-Allow-Origin": "*",
                "Accept-Ranges": "bytes",
                "Content-Length": str(len(data)),
            },
        )
    except KeyError:
        raise HTTPException(status_code=404, detail="Stream session not found")
    except IndexError:
        raise HTTPException(status_code=404, detail="Segment index out of range")
    except Exception as e:
        logger.error(f"Error serving segment {seg_index} for {stream_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/stream/{stream_id}/key/{key_index}")
@router.get("/stream/{stream_id}/key/{key_index}.key")
async def get_stream_key(stream_id: str, key_index: int):
    """
    Returns the binary HLS AES-128 decryption key.
    """
    try:
        data = await stream_manager.get_key(stream_id, key_index)
        return Response(
            content=data,
            media_type="application/octet-stream",
            headers={
                "Access-Control-Allow-Origin": "*",
                "Content-Length": str(len(data)),
            },
        )
    except KeyError:
        raise HTTPException(status_code=404, detail="Stream session not found")
    except IndexError:
        raise HTTPException(status_code=404, detail="Key index out of range")
    except Exception as e:
        logger.error(f"Error serving key {key_index} for {stream_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
