import pytest
from httpx import ASGITransport, AsyncClient
from app.main import app
from app.dlna.device_manager import device_manager
from app.dlna.models import DlnaDevice

@pytest.mark.asyncio
async def test_root_endpoint():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.get("/")
        assert resp.status_code == 200
        data = resp.json()
        assert data["service"] == "BigEyes PC Server"
        assert data["version"] == "1.0.1"

@pytest.mark.asyncio
async def test_pair_endpoint():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post("/api/pair", json={"code": "123456"})
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "ok"
        assert "token" in data

@pytest.mark.asyncio
async def test_devices_and_control_flow():
    mock_dev = DlnaDevice(
        id="uuid-test-tv-1",
        name="Living Room Mock TV",
        ip="192.168.1.199",
        location_url="http://192.168.1.199:1234/desc.xml",
        av_transport_control_url="http://192.168.1.199:1234/ctrl/AVTransport",
    )
    device_manager._devices[mock_dev.id] = mock_dev
    device_manager.select_device(mock_dev.id)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        # 1. Get devices
        resp = await client.get("/api/devices")
        assert resp.status_code == 200
        devices = resp.json()
        assert len(devices) >= 1
        assert any(d["id"] == "uuid-test-tv-1" for d in devices)

        # 2. Select device
        resp = await client.post("/api/select_device", json={"device_id": "uuid-test-tv-1"})
        assert resp.status_code == 200

        # 3. Control playback
        resp = await client.post("/api/control", json={"action": "play"})
        assert resp.status_code == 200
        assert resp.json()["status"] == "ok"

        # 4. Status check
        resp = await client.get("/api/status")
        assert resp.status_code == 200
        status_data = resp.json()
        assert status_data["device"] == "Living Room Mock TV"
        assert status_data["state"] == "playing"
        assert status_data["position"] == "00:01:23"
