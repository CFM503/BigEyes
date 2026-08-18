import asyncio
import logging
from typing import Optional, Dict
import httpx
from app.config import settings

logger = logging.getLogger(__name__)

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

class StreamFetcher:
    """
    Asynchronous HTTP client for fetching HLS playlists, segments, and keys.
    Maintains connection pools, forwards anti-hotlink headers, and handles retries.
    """
    def __init__(self):
        # High connection limits for concurrent segment prefetching
        limits = httpx.Limits(max_keepalive_connections=20, max_connections=30, keepalive_expiry=60.0)
        timeout = httpx.Timeout(connect=10.0, read=20.0, write=10.0, pool=10.0)
        self._client = httpx.AsyncClient(
            limits=limits,
            timeout=timeout,
            follow_redirects=True,
            verify=False  # Allow sources with untrusted/expired certs common in media hosts
        )

    def _build_headers(
        self,
        referer: Optional[str] = None,
        user_agent: Optional[str] = None,
        cookie: Optional[str] = None,
        extra_headers: Optional[Dict[str, str]] = None,
    ) -> Dict[str, str]:
        headers = {
            "User-Agent": user_agent or DEFAULT_USER_AGENT,
            "Accept": "*/*",
            "Accept-Encoding": "gzip, deflate, br",
            "Connection": "keep-alive",
        }
        if referer:
            headers["Referer"] = referer
            # Also set Origin if referer is present
            try:
                from urllib.parse import urlparse
                parsed = urlparse(referer)
                headers["Origin"] = f"{parsed.scheme}://{parsed.netloc}"
            except Exception:
                pass
        if cookie:
            headers["Cookie"] = cookie
        if extra_headers:
            headers.update(extra_headers)
        return headers

    async def fetch_text(
        self,
        url: str,
        referer: Optional[str] = None,
        user_agent: Optional[str] = None,
        cookie: Optional[str] = None,
    ) -> str:
        headers = self._build_headers(referer, user_agent, cookie)
        logger.debug(f"Fetching m3u8 text from: {url}")
        resp = await self._client.get(url, headers=headers)
        resp.raise_for_status()
        return resp.text

    async def fetch_bytes(
        self,
        url: str,
        referer: Optional[str] = None,
        user_agent: Optional[str] = None,
        cookie: Optional[str] = None,
        max_retries: int = settings.MAX_RETRIES,
        retry_backoffs: list[float] = settings.RETRY_BACKOFFS,
    ) -> bytes:
        headers = self._build_headers(referer, user_agent, cookie)
        last_exception = None
        
        for attempt in range(max_retries + 1):
            try:
                resp = await self._client.get(url, headers=headers)
                resp.raise_for_status()
                return resp.content
            except Exception as e:
                last_exception = e
                if attempt < max_retries:
                    backoff = retry_backoffs[min(attempt, len(retry_backoffs) - 1)]
                    logger.warning(
                        f"Fetch failed for {url} (attempt {attempt + 1}/{max_retries + 1}): {e}. Retrying in {backoff}s..."
                    )
                    await asyncio.sleep(backoff)
                else:
                    logger.error(f"Fetch permanently failed for {url} after {max_retries + 1} attempts: {e}")
                    
        raise last_exception or Exception(f"Failed to fetch {url}")

    async def close(self):
        await self._client.aclose()

fetcher = StreamFetcher()
