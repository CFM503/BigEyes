import socket
import logging
from app.config import settings

logger = logging.getLogger(__name__)

def get_lan_ip() -> str:
    """
    Detects the best LAN IPv4 address for DLNA callback and HLS stream URLs.
    Falls back to socket routing trick or 127.0.0.1.
    """
    if settings.LAN_IP_OVERRIDE:
        return settings.LAN_IP_OVERRIDE
    
    # 1. Try standard routing trick to public DNS / gateway
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        # Using a public unroutable/routable IP - doesn't actually send packets
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127."):
            return ip
    except Exception as e:
        logger.debug(f"Failed routing lookup for LAN IP: {e}")
    
    # 2. Try hostname lookup
    try:
        hostname = socket.gethostname()
        for ip in socket.gethostbyname_ex(hostname)[2]:
            if not ip.startswith("127.") and (
                ip.startswith("192.168.") or ip.startswith("10.") or ip.startswith("172.")
            ):
                return ip
    except Exception as e:
        logger.debug(f"Failed hostname lookup for LAN IP: {e}")
        
    return "127.0.0.1"
