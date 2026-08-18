import asyncio
import time
import logging
from typing import Dict, List, Optional
from app.dlna.models import DlnaDevice
from app.dlna.ssdp import ssdp_scanner
from app.dlna.controller import dlna_controller

logger = logging.getLogger(__name__)

class DeviceManager:
    """
    Manages discovered DLNA devices, auto-selection, and periodic refresh.
    """
    def __init__(self):
        self._devices: Dict[str, DlnaDevice] = {}
        self._selected_device_id: Optional[str] = None
        self._scan_task: Optional[asyncio.Task] = None
        self._lock = asyncio.Lock()

    async def scan_once(self) -> List[DlnaDevice]:
        try:
            found = await ssdp_scanner.scan(timeout=3.0)
            now = time.time()
            async with self._lock:
                for dev in found:
                    dev.last_seen = now
                    if dev.id not in self._devices:
                        logger.info(f"Discovered new DLNA device: {dev.name} ({dev.ip}) [{dev.id}]")
                    # Preserve selected flag
                    if dev.id == self._selected_device_id:
                        dev.selected = True
                    self._devices[dev.id] = dev

                # Clean up devices not seen for > 120s
                stale_ids = [k for k, v in self._devices.items() if now - v.last_seen > 120]
                for sid in stale_ids:
                    logger.info(f"Removing stale DLNA device: {self._devices[sid].name}")
                    del self._devices[sid]

                # Auto-select if only 1 device available and none selected
                if len(self._devices) == 1 and not self._selected_device_id:
                    sole_id = next(iter(self._devices.keys()))
                    self._selected_device_id = sole_id
                    self._devices[sole_id].selected = True

            return self.get_devices()
        except Exception as e:
            logger.error(f"Error during SSDP device scan: {e}")
            return self.get_devices()

    def get_devices(self) -> List[DlnaDevice]:
        dev_list = list(self._devices.values())
        for d in dev_list:
            d.selected = (d.id == self._selected_device_id)
        return dev_list

    def select_device(self, device_id: str) -> bool:
        if device_id in self._devices:
            self._selected_device_id = device_id
            for d in self._devices.values():
                d.selected = (d.id == device_id)
            logger.info(f"Selected DLNA device: {self._devices[device_id].name}")
            return True
        return False

    def get_selected_device(self) -> Optional[DlnaDevice]:
        if self._selected_device_id and self._selected_device_id in self._devices:
            return self._devices[self._selected_device_id]
        if len(self._devices) == 1:
            return next(iter(self._devices.values()))
        return None

    async def _periodic_scan_loop(self):
        while True:
            try:
                await self.scan_once()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.debug(f"Periodic scan error: {e}")
            await asyncio.sleep(25.0)

    def start_background_scan(self):
        if not self._scan_task or self._scan_task.done():
            self._scan_task = asyncio.create_task(self._periodic_scan_loop())
            logger.info("Started background SSDP device scanner.")

    def stop(self):
        if self._scan_task:
            self._scan_task.cancel()
            self._scan_task = None

device_manager = DeviceManager()
