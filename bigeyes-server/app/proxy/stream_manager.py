import uuid
import logging
from typing import Dict, Optional
from urllib.parse import urljoin
from app.proxy.models import StreamSession, SegmentItem, KeyItem, VariantItem
from app.proxy.cache import cache
from app.proxy.fetcher import fetcher
from app.proxy.parser import M3U8Parser
from app.proxy.prefetcher import prefetcher

logger = logging.getLogger(__name__)

class StreamManager:
    """
    Coordinator for active HLS stream sessions.
    Manages session lifecycle, playlist rewriting, segment retrieval, caching and prefetching.
    """
    def __init__(self):
        self._sessions: Dict[str, StreamSession] = {}
        self._raw_media_playlists: Dict[str, str] = {}  # stream_id -> raw media m3u8 text
        self._raw_base_urls: Dict[str, str] = {}  # stream_id -> media playlist base url
        self._active_stream_id: Optional[str] = None

    def get_active_session(self) -> Optional[StreamSession]:
        if self._active_stream_id and self._active_stream_id in self._sessions:
            return self._sessions[self._active_stream_id]
        return None

    def get_session(self, stream_id: str) -> Optional[StreamSession]:
        return self._sessions.get(stream_id)

    async def create_session(
        self,
        url: str,
        referer: Optional[str] = None,
        user_agent: Optional[str] = None,
        cookie: Optional[str] = None,
        title: Optional[str] = None,
    ) -> StreamSession:
        # Generate stream id
        stream_id = str(uuid.uuid4())[:8]
        logger.info(f"Creating new stream session {stream_id} for URL: {url}")

        # Fetch root playlist
        root_content = await fetcher.fetch_text(url, referer, user_agent, cookie)
        
        is_master = M3U8Parser.is_master_playlist(root_content)
        media_content = root_content
        media_base_url = url
        variants = []

        if is_master:
            logger.info(f"Stream {stream_id} is a Master Playlist. Parsing variants...")
            variants = M3U8Parser.parse_master_playlist(root_content, url)
            default_variant = M3U8Parser.select_default_variant(variants)
            logger.info(f"Selected default variant: {default_variant.resolution or 'default'} ({default_variant.uri})")
            media_base_url = default_variant.uri
            media_content = await fetcher.fetch_text(default_variant.uri, referer, user_agent, cookie)

        session = StreamSession(
            stream_id=stream_id,
            original_url=url,
            referer=referer,
            user_agent=user_agent,
            cookie=cookie,
            title=title,
            is_master=is_master,
            variants=variants,
        )

        self._raw_media_playlists[stream_id] = media_content
        self._raw_base_urls[stream_id] = media_base_url
        self._sessions[stream_id] = session
        
        # Stop previous stream's prefetch tasks
        if self._active_stream_id and self._active_stream_id != stream_id:
            await prefetcher.cancel_stream(self._active_stream_id)
        self._active_stream_id = stream_id

        return session

    def get_rewritten_m3u8(self, stream_id: str, server_base_url: str) -> str:
        session = self._sessions.get(stream_id)
        if not session or stream_id not in self._raw_media_playlists:
            raise KeyError(f"Stream session {stream_id} not found")

        raw_content = self._raw_media_playlists[stream_id]
        base_url = self._raw_base_urls[stream_id]

        rewritten_text, segments, keys = M3U8Parser.rewrite_media_playlist(
            content=raw_content,
            base_url=base_url,
            stream_id=stream_id,
            server_base_url=server_base_url,
        )

        # Update session with parsed segments and keys
        session.segments = segments
        session.keys = keys

        # Auto-trigger prefetch for first few segments to warm up cache
        if len(segments) > 0:
            prefetcher.trigger_prefetch(session, current_seg_index=-1)

        return rewritten_text

    async def get_segment(self, stream_id: str, seg_index: int) -> bytes:
        session = self._sessions.get(stream_id)
        if not session:
            raise KeyError(f"Stream session {stream_id} not found")
        if seg_index < 0 or seg_index >= len(session.segments):
            raise IndexError(f"Segment index {seg_index} out of range (total {len(session.segments)})")

        seg = session.segments[seg_index]
        cache_key = f"{stream_id}_seg_{seg_index}.ts"

        # Check Cache
        data = cache.get(cache_key)
        if data is None:
            logger.info(f"Cache miss for segment {seg_index} in stream {stream_id}. Fetching from source: {seg.uri}")
            data = await fetcher.fetch_bytes(
                url=seg.uri,
                referer=session.referer,
                user_agent=session.user_agent,
                cookie=session.cookie,
            )
            cache.put(cache_key, data)
        else:
            logger.debug(f"Cache hit for segment {seg_index} in stream {stream_id}")

        # Update progress and trigger prefetch for upcoming segments
        session.last_accessed_seg = seg_index
        prefetcher.trigger_prefetch(session, seg_index)

        return data

    async def get_key(self, stream_id: str, key_index: int) -> bytes:
        session = self._sessions.get(stream_id)
        if not session:
            raise KeyError(f"Stream session {stream_id} not found")
        if key_index < 0 or key_index >= len(session.keys):
            raise IndexError(f"Key index {key_index} out of range (total {len(session.keys)})")

        key_item = session.keys[key_index]
        cache_key = f"{stream_id}_key_{key_index}.key"

        data = cache.get(cache_key)
        if data is None:
            logger.info(f"Fetching encryption key {key_index} for stream {stream_id}: {key_item.uri}")
            data = await fetcher.fetch_bytes(
                url=key_item.uri,
                referer=session.referer,
                user_agent=session.user_agent,
                cookie=session.cookie,
            )
            cache.put(cache_key, data)
        return data

stream_manager = StreamManager()
