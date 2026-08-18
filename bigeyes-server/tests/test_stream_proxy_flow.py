import pytest
from unittest.mock import patch, AsyncMock
from httpx import ASGITransport, AsyncClient
from app.main import app
from app.proxy.fetcher import fetcher
from app.proxy.cache import cache

MOCK_ORIGIN_M3U8 = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-KEY:METHOD=AES-128,URI="enc.key",IV=0x1234567890abcdef1234567890abcdef
#EXTINF:10.0,
segment_0.ts
#EXTINF:10.0,
segment_1.ts
#EXT-X-ENDLIST
"""

MOCK_TS_DATA = b"\x47\x40\x00\x10" + b"\x00" * 184
MOCK_KEY_DATA = b"\x01\x02\x03\x04" * 4

@pytest.mark.asyncio
async def test_full_cast_and_stream_proxy_pipeline():
    with patch.object(fetcher, "fetch_text", new_callable=AsyncMock) as mock_fetch_text, \
         patch.object(fetcher, "fetch_bytes", new_callable=AsyncMock) as mock_fetch_bytes:

        mock_fetch_text.return_value = MOCK_ORIGIN_M3U8
        mock_fetch_bytes.side_effect = lambda url, **kwargs: (
            MOCK_KEY_DATA if "enc.key" in url else MOCK_TS_DATA
        )

        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            # 1. Trigger Cast
            cast_payload = {
                "url": "https://video.resource.com/movie/index.m3u8",
                "referer": "https://video.resource.com/play/123",
                "user_agent": "CustomPlayer/1.0",
                "cookie": "uid=9988",
                "title": "Inception (2010)",
            }
            cast_resp = await client.post("/api/cast", json=cast_payload)
            assert cast_resp.status_code == 200
            cast_data = cast_resp.json()
            assert cast_data["status"] == "ok"
            stream_id = cast_data["stream_id"]
            assert stream_id is not None
            assert f"/stream/{stream_id}/index.m3u8" in cast_data["proxy_url"]

            # 2. Get Rewritten M3U8 Playlist
            m3u8_resp = await client.get(f"/stream/{stream_id}/index.m3u8")
            assert m3u8_resp.status_code == 200
            assert "application/vnd.apple.mpegurl" in m3u8_resp.headers["content-type"]
            m3u8_text = m3u8_resp.text
            
            # Verify segments and keys were rewritten to proxy URLs
            assert f"/stream/{stream_id}/seg/0.ts" in m3u8_text
            assert f"/stream/{stream_id}/seg/1.ts" in m3u8_text
            assert f"/stream/{stream_id}/key/0.key" in m3u8_text

            # 3. TV requests Segment 0
            seg_resp = await client.get(f"/stream/{stream_id}/seg/0.ts")
            assert seg_resp.status_code == 200
            assert "video/mp2t" in seg_resp.headers["content-type"]
            assert seg_resp.content == MOCK_TS_DATA

            # Verify Segment was cached
            cache_key = f"{stream_id}_seg_0.ts"
            assert cache.has(cache_key)

            # 4. TV requests Decryption Key 0
            key_resp = await client.get(f"/stream/{stream_id}/key/0.key")
            assert key_resp.status_code == 200
            assert key_resp.content == MOCK_KEY_DATA

            # 5. Check Status API
            status_resp = await client.get("/api/status")
            assert status_resp.status_code == 200
            status_data = status_resp.json()
            assert status_data["has_active_stream"] is True
            assert status_data["stream_id"] == stream_id
            assert status_data["title"] == "Inception (2010)"
