import pytest
import shutil
from pathlib import Path
from app.proxy.cache import DiskLRUCache

def test_lru_cache_basic(tmp_path):
    cache = DiskLRUCache(cache_dir=tmp_path, max_size_bytes=100)
    
    # Put item 1 (40 bytes)
    cache.put("item1", b"A" * 40)
    assert cache.has("item1")
    assert cache.get("item1") == b"A" * 40
    
    # Put item 2 (40 bytes) -> total 80
    cache.put("item2", b"B" * 40)
    assert cache.has("item1")
    assert cache.has("item2")
    
    # Put item 3 (40 bytes) -> exceeds 100 -> item1 (LRU) should be evicted
    cache.put("item3", b"C" * 40)
    assert not cache.has("item1")
    assert cache.has("item2")
    assert cache.has("item3")
    
    # Access item2 (making item3 the oldest)
    _ = cache.get("item2")
    
    # Put item 4 (40 bytes) -> item3 should be evicted
    cache.put("item4", b"D" * 40)
    assert cache.has("item2")
    assert not cache.has("item3")
    assert cache.has("item4")
