import os
import time
import logging
from pathlib import Path
from typing import Optional
from collections import OrderedDict
import threading
from app.config import settings

logger = logging.getLogger(__name__)

class DiskLRUCache:
    """
    Disk-based LRU Cache for video segments and keys.
    Maintains max disk storage constraints and tracks access recency.
    """
    def __init__(self, cache_dir: Path = settings.CACHE_DIR, max_size_bytes: int = settings.MAX_CACHE_SIZE_BYTES):
        self.cache_dir = cache_dir
        self.max_size_bytes = max_size_bytes
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        # OrderedDict mapping key -> size in bytes
        self._entries: OrderedDict[str, int] = OrderedDict()
        self._current_size = 0
        self._scan_existing()

    def _scan_existing(self):
        """Scans cache directory on startup to index existing cached files."""
        with self._lock:
            try:
                files = []
                for p in self.cache_dir.glob("*"):
                    if p.is_file():
                        files.append((p.stat().st_mtime, p.name, p.stat().st_size))
                # Sort by mtime ascending (oldest first)
                files.sort(key=lambda x: x[0])
                for _, name, size in files:
                    self._entries[name] = size
                    self._current_size += size
                logger.info(f"Loaded {len(self._entries)} cache files, total size: {self._current_size / 1024 / 1024:.2f} MB")
            except Exception as e:
                logger.warning(f"Error scanning cache dir: {e}")

    def _evict_if_needed(self, incoming_size: int):
        """Evicts oldest files if adding incoming_size exceeds max_size_bytes."""
        while self._current_size + incoming_size > self.max_size_bytes and self._entries:
            oldest_key, oldest_size = self._entries.popitem(last=False)
            file_path = self.cache_dir / oldest_key
            try:
                if file_path.exists():
                    file_path.unlink()
                self._current_size -= oldest_size
                logger.debug(f"Evicted cache entry: {oldest_key} ({oldest_size} bytes)")
            except Exception as e:
                logger.warning(f"Failed to delete evicted file {oldest_key}: {e}")

    def has(self, key: str) -> bool:
        with self._lock:
            if key in self._entries:
                # Mark as recently used
                self._entries.move_to_end(key)
                return (self.cache_dir / key).exists()
            return False

    def get(self, key: str) -> Optional[bytes]:
        with self._lock:
            if key not in self._entries:
                return None
            file_path = self.cache_dir / key
            if not file_path.exists():
                del self._entries[key]
                return None
            # Update LRU order
            self._entries.move_to_end(key)
            try:
                return file_path.read_bytes()
            except Exception as e:
                logger.error(f"Error reading cache file {key}: {e}")
                return None

    def put(self, key: str, data: bytes) -> None:
        size = len(data)
        with self._lock:
            self._evict_if_needed(size)
            file_path = self.cache_dir / key
            try:
                file_path.write_bytes(data)
                self._entries[key] = size
                self._entries.move_to_end(key)
                self._current_size += size
            except Exception as e:
                logger.error(f"Error writing cache file {key}: {e}")

    def clear(self) -> None:
        with self._lock:
            for key in list(self._entries.keys()):
                file_path = self.cache_dir / key
                try:
                    if file_path.exists():
                        file_path.unlink()
                except Exception:
                    pass
            self._entries.clear()
            self._current_size = 0

cache = DiskLRUCache()
