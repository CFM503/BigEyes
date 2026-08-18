import socket
import logging
from typing import Optional
from zeroconf import Zeroconf, ServiceInfo
from app.config import settings
from app.utils.network import get_lan_ip

logger = logging.getLogger(__name__)

class MDNSService:
    """
    Broadcasts the BigEyes Server instance via mDNS so Android clients
    can automatically discover it via NsdManager.
    """
    def __init__(self):
        self._zeroconf: Optional[Zeroconf] = None
        self._service_info: Optional[ServiceInfo] = None

    def start(self, port: int = settings.PORT):
        lan_ip = get_lan_ip()
        logger.info(f"Starting mDNS broadcast on {lan_ip}:{port} with service type '{settings.MDNS_TYPE}'")
        try:
            self._zeroconf = Zeroconf()
            ip_bytes = socket.inet_aton(lan_ip)
            
            self._service_info = ServiceInfo(
                type_=settings.MDNS_TYPE,
                name=f"{settings.SERVICE_NAME}.{settings.MDNS_TYPE}",
                addresses=[ip_bytes],
                port=port,
                properties={
                    "version": "1.0",
                    "server": "bigeyes-server",
                },
                server=f"{socket.gethostname()}.local.",
            )
            self._zeroconf.register_service(self._service_info)
            logger.info("mDNS service successfully registered.")
        except Exception as e:
            logger.error(f"Failed to register mDNS service: {e}")

    def stop(self):
        if self._zeroconf:
            logger.info("Unregistering mDNS service...")
            try:
                if self._service_info:
                    self._zeroconf.unregister_service(self._service_info)
                self._zeroconf.close()
            except Exception as e:
                logger.warning(f"Error unregistering mDNS: {e}")
            finally:
                self._zeroconf = None
                self._service_info = None

mdns_service = MDNSService()
