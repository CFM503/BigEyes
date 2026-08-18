import pytest
from app.proxy.parser import M3U8Parser

SAMPLE_MEDIA_PLAYLIST = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-KEY:METHOD=AES-128,URI="https://example.com/keys/enc.key",IV=0x1234567890abcdef1234567890abcdef
#EXTINF:9.009,
segment_0.ts
#EXTINF:9.009,
/media/hls/segment_1.ts
#EXTINF:9.009,
https://cdn.example.com/live/segment_2.ts
#EXT-X-ENDLIST
"""

SAMPLE_MASTER_PLAYLIST = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=720x480
low.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1280x720
mid.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=7680000,RESOLUTION=1920x1080
high.m3u8
"""

def test_master_playlist_parsing():
    assert M3U8Parser.is_master_playlist(SAMPLE_MASTER_PLAYLIST)
    variants = M3U8Parser.parse_master_playlist(SAMPLE_MASTER_PLAYLIST, "https://example.com/master.m3u8")
    assert len(variants) == 3
    # Ordered descending
    assert variants[0].resolution == "1920x1080"
    assert variants[0].bandwidth == 7680000
    
    selected = M3U8Parser.select_default_variant(variants)
    # Should pick medium/1080p
    assert selected.uri.endswith("high.m3u8") or selected.uri.endswith("mid.m3u8")

def test_media_playlist_rewriting():
    assert not M3U8Parser.is_master_playlist(SAMPLE_MEDIA_PLAYLIST)
    base_url = "https://example.com/media/index.m3u8"
    server_base = "http://192.168.1.100:8765"
    stream_id = "test1234"
    
    rewritten_text, segments, keys = M3U8Parser.rewrite_media_playlist(
        content=SAMPLE_MEDIA_PLAYLIST,
        base_url=base_url,
        stream_id=stream_id,
        server_base_url=server_base,
    )
    
    assert len(segments) == 3
    assert segments[0].uri == "https://example.com/media/segment_0.ts"
    assert segments[1].uri == "https://example.com/media/hls/segment_1.ts"
    assert segments[2].uri == "https://cdn.example.com/live/segment_2.ts"
    
    assert len(keys) == 1
    assert keys[0].uri == "https://example.com/keys/enc.key"
    
    # Check rewritten text content
    assert f"{server_base}/stream/{stream_id}/seg/0.ts" in rewritten_text
    assert f"{server_base}/stream/{stream_id}/seg/1.ts" in rewritten_text
    assert f"{server_base}/stream/{stream_id}/seg/2.ts" in rewritten_text
    assert f'{server_base}/stream/{stream_id}/key/0.key' in rewritten_text
