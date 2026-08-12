import time
from typing import Optional

MIN_PROGRESS_INTERVAL_SECONDS = 0.3
MIN_PROGRESS_PERCENT_DELTA = 1
PROGRESS_MIN_PERCENT = 0
PROGRESS_MAX_PERCENT = 100


class AndroidProgressReporter:
    def __init__(self, progress_callback=None):
        self.progress_callback = progress_callback
        self.step = ""
        self.detail = ""
        self.total_items = 0
        self.finished_items = 0
        self._last_percent = -1
        self._last_reported_at = 0.0

    def update_step(self, step: str, detail: str = "") -> None:
        self.step = step
        self.detail = detail

    def set_item_total(self, total: int, detail: str = "") -> None:
        self.total_items = int(total or 0)
        self.detail = detail

    def advance_item(self, status: str, detail: str = "") -> None:
        self.finished_items += 1
        self.detail = f"{status}: {detail}" if detail else status

    def on_author(self, nickname: Optional[str] = None, sec_uid: Optional[str] = None) -> None:
        return None

    def on_file_progress(
        self,
        downloaded_bytes: int,
        total_bytes: int,
        speed_bytes_per_second: int,
        *,
        force: bool = False,
    ) -> None:
        if self.progress_callback is None:
            return

        downloaded_bytes = max(0, int(downloaded_bytes or 0))
        total_bytes = max(0, int(total_bytes or 0))
        speed_bytes_per_second = max(0, int(speed_bytes_per_second or 0))
        percent = self._calculate_percent(downloaded_bytes, total_bytes)
        if not force and not self._should_report(percent, total_bytes):
            return

        self._last_percent = percent
        self._last_reported_at = time.monotonic()
        try:
            self.progress_callback.onProgress(
                percent,
                downloaded_bytes,
                total_bytes,
                speed_bytes_per_second,
            )
        except Exception:
            return None

    @classmethod
    def _calculate_percent(cls, downloaded_bytes: int, total_bytes: int) -> int:
        if total_bytes <= 0:
            return PROGRESS_MIN_PERCENT
        percent = int(downloaded_bytes * PROGRESS_MAX_PERCENT / total_bytes)
        return max(PROGRESS_MIN_PERCENT, min(PROGRESS_MAX_PERCENT, percent))

    def _should_report(self, percent: int, total_bytes: int) -> bool:
        now = time.monotonic()
        if total_bytes <= 0:
            return now - self._last_reported_at >= MIN_PROGRESS_INTERVAL_SECONDS
        return (
            percent - self._last_percent >= MIN_PROGRESS_PERCENT_DELTA
            or now - self._last_reported_at >= MIN_PROGRESS_INTERVAL_SECONDS
        )
