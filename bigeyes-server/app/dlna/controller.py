import logging
import xml.etree.ElementTree as ET
from html import escape
from typing import Dict, Optional, Tuple
import httpx

logger = logging.getLogger(__name__)

def _format_time(seconds: float | int | str) -> str:
    """Converts seconds or time string to HH:MM:SS."""
    if isinstance(seconds, str):
        if ":" in seconds:
            return seconds
        try:
            seconds = float(seconds)
        except ValueError:
            return "00:00:00"
            
    secs = int(seconds)
    hours = secs // 3600
    minutes = (secs % 3600) // 60
    rem_secs = secs % 60
    return f"{hours:02d}:{minutes:02d}:{rem_secs:02d}"

class DlnaController:
    """
    UPnP / DLNA AVTransport SOAP client for controlling smart TVs and media renderers.
    """
    def __init__(self):
        self._client = httpx.AsyncClient(timeout=8.0, verify=False)

    async def _send_soap_action(
        self,
        control_url: str,
        service_type: str,
        action_name: str,
        arguments: Dict[str, str],
    ) -> Tuple[bool, str]:
        """Sends a SOAP request to the device's control URL."""
        args_xml = "".join([f"<{k}>{escape(str(v))}</{k}>" for k, v in arguments.items()])
        soap_body = (
            '<?xml version="1.0" encoding="utf-8"?>\r\n'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">\r\n'
            '  <s:Body>\r\n'
            f'    <u:{action_name} xmlns:u="{service_type}">\r\n'
            f"      {args_xml}\r\n"
            f'    </u:{action_name}>\r\n'
            '  </s:Body>\r\n'
            '</s:Envelope>'
        )
        headers = {
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPAction": f'"{service_type}#{action_name}"',
            "Connection": "close",
        }
        try:
            resp = await self._client.post(control_url, content=soap_body.encode("utf-8"), headers=headers)
            if resp.status_code == 200:
                return True, resp.text
            else:
                logger.warning(f"SOAP action {action_name} failed: HTTP {resp.status_code} - {resp.text}")
                return False, resp.text
        except Exception as e:
            logger.error(f"SOAP action {action_name} connection error: {e}")
            return False, str(e)

    async def set_av_transport_uri(
        self,
        control_url: str,
        uri: str,
        title: str = "BigEyes Video",
    ) -> bool:
        service_type = "urn:schemas-upnp-org:service:AVTransport:1"
        escaped_title = escape(title or "BigEyes Video")
        didl_metadata = (
            '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
            'xmlns:dc="http://purl.org/dc/elements/1.1/" '
            'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
            '<item id="0" parentID="-1" restricted="1">'
            f'<dc:title>{escaped_title}</dc:title>'
            '<upnp:class>object.item.videoItem</upnp:class>'
            f'<res protocolInfo="http-get:*:application/vnd.apple.mpegurl:*">{uri}</res>'
            '</item>'
            '</DIDL-Lite>'
        )
        args = {
            "InstanceID": "0",
            "CurrentURI": uri,
            "CurrentURIMetaData": didl_metadata,
        }
        ok, _ = await self._send_soap_action(control_url, service_type, "SetAVTransportURI", args)
        return ok

    async def play(self, control_url: str, speed: str = "1") -> bool:
        service_type = "urn:schemas-upnp-org:service:AVTransport:1"
        args = {
            "InstanceID": "0",
            "Speed": speed,
        }
        ok, _ = await self._send_soap_action(control_url, service_type, "Play", args)
        return ok

    async def pause(self, control_url: str) -> bool:
        service_type = "urn:schemas-upnp-org:service:AVTransport:1"
        args = {
            "InstanceID": "0",
        }
        ok, _ = await self._send_soap_action(control_url, service_type, "Pause", args)
        return ok

    async def stop(self, control_url: str) -> bool:
        service_type = "urn:schemas-upnp-org:service:AVTransport:1"
        args = {
            "InstanceID": "0",
        }
        ok, _ = await self._send_soap_action(control_url, service_type, "Stop", args)
        return ok

    async def seek(self, control_url: str, target_time: float | int | str) -> bool:
        service_type = "urn:schemas-upnp-org:service:AVTransport:1"
        time_str = _format_time(target_time)
        args = {
            "InstanceID": "0",
            "Unit": "REL_TIME",
            "Target": time_str,
        }
        ok, _ = await self._send_soap_action(control_url, service_type, "Seek", args)
        return ok

    async def get_position_info(self, control_url: str) -> Dict[str, str]:
        service_type = "urn:schemas-upnp-org:service:AVTransport:1"
        args = {"InstanceID": "0"}
        ok, resp_xml = await self._send_soap_action(control_url, service_type, "GetPositionInfo", args)
        result = {
            "track_duration": "00:00:00",
            "rel_time": "00:00:00",
            "track_uri": "",
        }
        if ok and resp_xml:
            try:
                root = ET.fromstring(resp_xml)
                for elem in root.iter():
                    tag = elem.tag.split("}", 1)[1] if "}" in elem.tag else elem.tag
                    if tag == "TrackDuration" and elem.text:
                        result["track_duration"] = elem.text.strip()
                    elif tag == "RelTime" and elem.text:
                        result["rel_time"] = elem.text.strip()
                    elif tag == "TrackURI" and elem.text:
                        result["track_uri"] = elem.text.strip()
            except Exception as e:
                logger.debug(f"Failed parsing GetPositionInfo XML: {e}")
        return result

    async def get_transport_info(self, control_url: str) -> Dict[str, str]:
        service_type = "urn:schemas-upnp-org:service:AVTransport:1"
        args = {"InstanceID": "0"}
        ok, resp_xml = await self._send_soap_action(control_url, service_type, "GetTransportInfo", args)
        result = {
            "current_transport_state": "STOPPED",
            "current_transport_status": "OK",
        }
        if ok and resp_xml:
            try:
                root = ET.fromstring(resp_xml)
                for elem in root.iter():
                    tag = elem.tag.split("}", 1)[1] if "}" in elem.tag else elem.tag
                    if tag == "CurrentTransportState" and elem.text:
                        result["current_transport_state"] = elem.text.strip()
                    elif tag == "CurrentTransportStatus" and elem.text:
                        result["current_transport_status"] = elem.text.strip()
            except Exception as e:
                logger.debug(f"Failed parsing GetTransportInfo XML: {e}")
        return result

    async def close(self):
        await self._client.aclose()

dlna_controller = DlnaController()
