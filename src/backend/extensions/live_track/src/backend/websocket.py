"""WebSocket module for live_track: forwards track_updated events to the client."""

from geo_lib.websocket.base_module import BaseWebSocketModule


class LiveTrackModule(BaseWebSocketModule):
    @property
    def module_name(self) -> str:
        return "live_track"

    async def handle_message(self, message_type: str, data: dict) -> None:
        if message_type == "refresh":
            await self.send_initial_state()
        else:
            pass

    async def send_initial_state(self) -> None:
        await self.send_to_client("initial_state", {})

    async def track_updated(self, event: dict) -> None:
        data = event.get("data") or {}
        await self.send_to_client("track_updated", data)
