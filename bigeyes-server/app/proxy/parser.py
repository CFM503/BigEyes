import re
from urllib.parse import urljoin, urlparse
from typing import Tuple, List, Dict, Optional
import m3u8
from app.proxy.models import StreamSession, SegmentItem, KeyItem, VariantItem

KEY_URI_REGEX = re.compile(r'URI=(["\'])(.*?)\1')

class M3U8Parser:
    """
    Parser and Rewriter for Master and Media HLS Playlists.
    Normalizes all segment & key URLs to absolute targets and rewrites them
    to point to the BigEyes local proxy endpoints.
    """

    @staticmethod
    def is_master_playlist(content: str) -> bool:
        return "#EXT-X-STREAM-INF" in content

    @classmethod
    def parse_master_playlist(cls, content: str, base_url: str) -> List[VariantItem]:
        parsed = m3u8.loads(content, uri=base_url)
        variants = []
        for idx, pl in enumerate(parsed.playlists):
            abs_uri = urljoin(base_url, pl.uri)
            bandwidth = pl.stream_info.bandwidth if pl.stream_info else 0
            resolution = (
                f"{pl.stream_info.resolution[0]}x{pl.stream_info.resolution[1]}"
                if pl.stream_info and pl.stream_info.resolution
                else None
            )
            codecs = pl.stream_info.codecs if pl.stream_info else None
            variants.append(
                VariantItem(
                    index=idx,
                    bandwidth=bandwidth or 0,
                    resolution=resolution,
                    codecs=codecs,
                    uri=abs_uri,
                )
            )
        # Sort variants by bandwidth descending
        variants.sort(key=lambda v: v.bandwidth, reverse=True)
        # Re-index
        for i, v in enumerate(variants):
            v.index = i
        return variants

    @classmethod
    def select_default_variant(cls, variants: List[VariantItem]) -> VariantItem:
        """
        Selects an optimal variant:
        If there are multiple (e.g. 4K, 1080p, 720p, 480p), select middle/high (e.g. 1080p / index ~ 1 or 0).
        """
        if not variants:
            raise ValueError("No variants found in master playlist")
        if len(variants) == 1:
            return variants[0]
        # If > 1, avoid potential 4K lag on budget DLNA TV, select 1080p or 1st/2nd
        return variants[min(1, len(variants) - 1)]

    @classmethod
    def rewrite_media_playlist(
        cls,
        content: str,
        base_url: str,
        stream_id: str,
        server_base_url: str,
    ) -> Tuple[str, List[SegmentItem], List[KeyItem]]:
        """
        Parses media playlist and rewrites segment and key URIs.
        Returns:
            - rewritten_m3u8_text
            - list of SegmentItem
            - list of KeyItem
        """
        parsed = m3u8.loads(content, uri=base_url)
        segments: List[SegmentItem] = []
        keys: List[KeyItem] = []
        key_url_map: Dict[str, int] = {}  # original_abs_url -> key_index

        # Process Keys first
        for k in parsed.keys:
            if k and k.uri:
                abs_key_uri = urljoin(base_url, k.uri)
                if abs_key_uri not in key_url_map:
                    key_idx = len(keys)
                    key_url_map[abs_key_uri] = key_idx
                    keys.append(
                        KeyItem(
                            index=key_idx,
                            method=k.method,
                            uri=abs_key_uri,
                            iv=k.iv,
                            key_format=k.keyformat,
                            key_format_versions=k.keyformatversions,
                        )
                    )

        # Process Segments
        for idx, seg in enumerate(parsed.segments):
            abs_seg_uri = urljoin(base_url, seg.uri)
            key_idx = None
            if seg.key and seg.key.uri:
                abs_key_uri = urljoin(base_url, seg.key.uri)
                key_idx = key_url_map.get(abs_key_uri)

            segments.append(
                SegmentItem(
                    index=idx,
                    uri=abs_seg_uri,
                    duration=float(seg.duration or 0.0),
                    title=seg.title,
                    key_index=key_idx,
                    byte_range=str(seg.byterange) if seg.byterange else None,
                )
            )

        # Line-by-line rewrite
        lines = content.splitlines()
        rewritten_lines = []
        seg_counter = 0

        for line in lines:
            trimmed = line.strip()
            if not trimmed:
                continue

            if trimmed.startswith("#EXT-X-KEY"):
                # Rewrite URI in EXT-X-KEY line
                def key_replacer(match):
                    quote = match.group(1)
                    raw_uri = match.group(2)
                    abs_uri = urljoin(base_url, raw_uri)
                    idx = key_url_map.get(abs_uri, 0)
                    proxy_url = f"{server_base_url}/stream/{stream_id}/key/{idx}.key"
                    return f'URI={quote}{proxy_url}{quote}'

                rewritten_line = KEY_URI_REGEX.sub(key_replacer, line)
                rewritten_lines.append(rewritten_line)

            elif trimmed.startswith("#"):
                # Pass through other HLS tags
                rewritten_lines.append(line)

            else:
                # Segment URI line
                if seg_counter < len(segments):
                    proxy_seg_url = f"{server_base_url}/stream/{stream_id}/seg/{seg_counter}.ts"
                    rewritten_lines.append(proxy_seg_url)
                    seg_counter += 1
                else:
                    rewritten_lines.append(line)

        return "\n".join(rewritten_lines) + "\n", segments, keys
