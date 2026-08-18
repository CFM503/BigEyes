import asyncio
import socket
import select
import logging
import time
import xml.etree.ElementTree as ET
from urllib.parse import urljoin, urlparse
from typing import Dict, List, Optional
import httpx
from app.dlna.models import DlnaDevice

logger = logging.getLogger(__name__)

SSDP_ADDR = "239.255.255.250"
SSDP_PORT = 1900
SSDP_MX = 2
SEARCH_TARGETS = [
    "urn:schemas-upnp-org:device:MediaRenderer:1",
    "urn:schemas-upnp-org:service:AVTransport:1",
]

def _build_msearch_packet(st: str) -> bytes:
    msg = (
        f"M-SEARCH * HTTP/1.1\r\n"
        f"HOST: {SSDP_ADDR}:{SSDP_PORT}\r\n"
        f'MAN: "ssdp:discover"\r\n'
        f"MX: {SSDP_MX}\r\n"
        f"ST: {st}\r\n"
        f"\r\n"
    )
    return msg.encode("utf-8")

def _strip_ns(tag: str) -> str:
    if "}" in tag:
        return tag.split("}", 1)[1]
    return tag

def _sync_ssdp_discovery(timeout: float = 2.0) -> set[str]:
    locations: set[str] = set()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 4)
    sock.settimeout(0.4)

    try:
        # Send search requests
        for st in SEARCH_TARGETS:
            try:
                packet = _build_msearch_packet(st)
                sock.sendto(packet, (SSDP_ADDR, SSDP_PORT))
            except Exception as e:
                logger.debug(f"SSDP send error for {st}: {e}")

        # Collect responses
        start_time = time.time()
        while time.time() - start_time < timeout:
            ready = select.select([sock], [], [], 0.4)
            if not ready[0]:
                continue
            try:
                data, _ = sock.recvfrom(4096)
                response = data.decode("utf-8", errors="ignore")
                for line in response.splitlines():
                    if line.upper().startswith("LOCATION:"):
                        loc = line.split(":", 1)[1].strip()
                        if loc:
                            locations.add(loc)
            except (socket.timeout, BlockingIOError):
                continue
            except Exception as e:
                logger.debug(f"SSDP recv error: {e}")
                break
    finally:
        sock.close()

    return locations

class SSDPScanner:
    """
    SSDP scanner for discovering DLNA/UPnP MediaRenderer devices.
    """
    def __init__(self):
        self._http_client = httpx.AsyncClient(timeout=3.0, verify=False)

    async def scan(self, timeout: float = 2.0) -> List[DlnaDevice]:
        loop = asyncio.get_running_loop()
        locations = await loop.run_in_executor(None, _sync_ssdp_discovery, timeout)

        # Fetch and parse device description XML for each location concurrently
        tasks = [self._fetch_device_info(loc) for loc in locations]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        
        devices: List[DlnaDevice] = []
        for r in results:
            if isinstance(r, DlnaDevice) and r.av_transport_control_url:
                devices.append(r)

        return devices

    async def _fetch_device_info(self, location_url: str) -> Optional[DlnaDevice]:
        try:
            resp = await self._http_client.get(location_url)
            if resp.status_code != 200:
                return None
            return self._parse_device_xml(resp.text, location_url)
        except Exception as e:
            logger.debug(f"Failed to fetch device XML from {location_url}: {e}")
            return None

    def _parse_device_xml(self, xml_text: str, location_url: str) -> Optional[DlnaDevice]:
        try:
            root = ET.fromstring(xml_text)
            parsed_url = urlparse(location_url)
            device_ip = parsed_url.hostname or ""

            friendly_name = "Unknown TV"
            udn = ""
            av_control_url = None
            rendering_control_url = None

            for elem in root.iter():
                tag = _strip_ns(elem.tag)
                if tag == "friendlyName" and elem.text and friendly_name == "Unknown TV":
                    friendly_name = elem.text.strip()
                elif tag == "UDN" and elem.text and not udn:
                    udn = elem.text.strip()

            for service in root.iter():
                tag = _strip_ns(service.tag)
                if tag == "service":
                    st = ""
                    ctrl = ""
                    for child in service:
                        c_tag = _strip_ns(child.tag)
                        if c_tag == "serviceType" and child.text:
                            st = child.text.strip()
                        elif c_tag == "controlURL" and child.text:
                            ctrl = child.text.strip()
                    
                    if "AVTransport" in st and ctrl:
                        av_control_url = urljoin(location_url, ctrl)
                    elif "RenderingControl" in st and ctrl:
                        rendering_control_url = urljoin(location_url, ctrl)

            if not udn:
                udn = f"uuid-{device_ip}"

            return DlnaDevice(
                id=udn,
                name=friendly_name,
                ip=device_ip,
                location_url=location_url,
                av_transport_control_url=av_control_url,
                rendering_control_url=rendering_control_url,
            )
        except Exception as e:
            logger.debug(f"Error parsing device XML from {location_url}: {e}")
            return None

    async def close(self):
        await self._http_client.aclose()

ssdp_scanner = SSDPScanner()
