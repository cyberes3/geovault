"""
Run a loop that flushes pending live track broadcasts from Redis every 200ms.

Run this as a separate process (e.g. systemd service) so batched updates are sent
to WebSocket clients. If not run, ingress still sends updates immediately via
broadcast_track_updated when Redis queue is unavailable.
"""
import time

from django.core.management.base import BaseCommand

from ...helpers import flush_pending_broadcasts


class Command(BaseCommand):
    help = "Flush live track broadcast buffer from Redis every 200ms (run in a loop)."

    def add_arguments(self, parser):
        parser.add_argument(
            "--interval",
            type=float,
            default=0.2,
            help="Flush interval in seconds (default 0.2)",
        )

    def handle(self, *args, **options):
        interval = max(0.05, float(options["interval"]))
        self.stdout.write(f"Flushing live track broadcasts every {interval}s (Ctrl+C to stop).")
        try:
            while True:
                time.sleep(interval)
                n = flush_pending_broadcasts()
                if n > 0:
                    self.stdout.write(f"Flushed {n} track(s).")
        except KeyboardInterrupt:
            self.stdout.write(self.style.SUCCESS("Stopped."))
