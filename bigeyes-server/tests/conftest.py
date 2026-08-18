import pytest
from unittest.mock import patch, AsyncMock
from app.dlna.controller import dlna_controller
from app.dlna.device_manager import device_manager

@pytest.fixture(autouse=True)
def mock_network_dependencies():
    with patch.object(device_manager, "scan_once", new_callable=AsyncMock) as m_scan, \
         patch.object(dlna_controller, "get_position_info", new_callable=AsyncMock) as m_pos, \
         patch.object(dlna_controller, "get_transport_info", new_callable=AsyncMock) as m_trans, \
         patch.object(dlna_controller, "set_av_transport_uri", new_callable=AsyncMock) as m_set, \
         patch.object(dlna_controller, "play", new_callable=AsyncMock) as m_play, \
         patch.object(dlna_controller, "pause", new_callable=AsyncMock) as m_pause, \
         patch.object(dlna_controller, "stop", new_callable=AsyncMock) as m_stop, \
         patch.object(dlna_controller, "seek", new_callable=AsyncMock) as m_seek:

        m_scan.return_value = []
        m_pos.return_value = {"rel_time": "00:01:23", "track_duration": "01:30:00", "track_uri": ""}
        m_trans.return_value = {"current_transport_state": "PLAYING", "current_transport_status": "OK"}
        m_set.return_value = True
        m_play.return_value = True
        m_pause.return_value = True
        m_stop.return_value = True
        m_seek.return_value = True

        yield
