import asyncio
import logging
from typing import Dict, Set, Optional
from app.config import settings
from app.proxy.models import StreamSession, SegmentItem
from app.proxy.cache import cache
from app.proxy.fetcher import fetcher

logger = logging.getLogger(__name__)

class PrefetchManager:
    """
    Manages background prefetching of upcoming video segments.
    Provides sliding window prefetch, concurrency limits, deduplication, and cancellation.
    """
    def __init__(self, concurrency: int = settings.PREFETCH_CONCURRENCY, window: int = settings.PREFETCH_WINDOW):
        self.semaphore = asyncio.Semaphore(concurrency)
        self.window = window
        self._active_tasks: Dict[str, Set[int]] = {}  # stream_id -> set of active seg_indices
        self._stream_tasks: Dict[str, Set[asyncio.Task]] = {}  # stream_id -> set of Tasks
        self._lock = asyncio.Lock()

    def _get_cache_key(self, stream_id: str, seg_index: int) -> str:
        return f"{stream_id}_seg_{seg_index}.ts"

    async def _fetch_segment_worker(self, session: StreamSession, seg: SegmentItem):
        stream_id = session.stream_id
        cache_key = self._get_cache_key(stream_id, seg.index)
        
        async with self.semaphore:
            try:
                if cache.has(cache_key):
                    return
                logger.debug(f"[Prefetch] Fetching segment {seg.index} for stream {stream_id}: {seg.uri}")
                data = await fetcher.fetch_bytes(
                    url=seg.uri,
                    referer=session.referer,
                    user_agent=session.user_agent,
                    cookie=session.cookie,
                )
                cache.put(cache_key, data)
                logger.debug(f"[Prefetch] Cached segment {seg.index} ({len(data)} bytes) for {stream_id}")
            except asyncio.CancelledError:
                pass
            except Exception as e:
                logger.warning(f"[Prefetch] Failed prefetching segment {seg.index} for {stream_id}: {e}")
            finally:
                async with self._lock:
                    if stream_id in self._active_tasks:
                        self._active_tasks[stream_id].discard(seg.index)

    def trigger_prefetch(self, session: StreamSession, current_seg_index: int):
        """
        Triggers prefetching for the next `window` segments following current_seg_index.
        """
        stream_id = session.stream_id
        total_segs = len(session.segments)
        if total_segs == 0:
            return

        start_idx = current_seg_index + 1
        end_idx = min(start_idx + self.window, total_segs)

        for idx in range(start_idx, end_idx):
            cache_key = self._get_cache_key(stream_id, idx)
            if cache.has(cache_key):
                continue

            if stream_id not in self._active_tasks:
                self._active_tasks[stream_id] = set()
            if stream_id not in self._stream_tasks:
                self._stream_tasks[stream_id] = set()

            if idx in self._active_tasks[stream_id]:
                continue

            self._active_tasks[stream_id].add(idx)
            seg = session.segments[idx]
            task = asyncio.create_task(self._fetch_segment_worker(session, seg))
            self._stream_tasks[stream_id].add(task)
            task.add_done_callback(lambda t, s=stream_id: self._stream_tasks.get(s, set()).discard(t))

    async def cancel_stream(self, stream_id: str):
        """Cancels all active prefetch tasks for a given stream."""
        async with self._lock:
            tasks = self._stream_tasks.pop(stream_id, set())
            for t in tasks:
                t.cancel()
            self._active_tasks.pop(stream_id, None)

prefetcher = PrefetchManager()
