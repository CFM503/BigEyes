import uvicorn
import logging
from app.config import settings
from app.utils.network import get_lan_ip

if __name__ == "__main__":
    lan_ip = get_lan_ip()
    print("\n" + "=" * 55)
    print(f"  BigEyes PC Server")
    print(f"  Local Access:  http://127.0.0.1:{settings.PORT}")
    print(f"  LAN Access:    http://{lan_ip}:{settings.PORT}")
    print(f"  mDNS Service:  {settings.MDNS_TYPE}")
    print("=" * 55 + "\n")
    
    uvicorn.run(
        "app.main:app",
        host=settings.HOST,
        port=settings.PORT,
        reload=False,
        log_level="info",
    )
