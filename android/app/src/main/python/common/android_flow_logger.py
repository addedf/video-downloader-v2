import logging
import sys
import time
from contextlib import contextmanager
from typing import Any, Dict, Iterator, Optional

DEFAULT_LOGGER_NAME = "AndroidFlow"
DEFAULT_PREVIEW_LIMIT = 120
MAX_LOG_VALUE_LENGTH = 180
TRUNCATION_SUFFIX = "..."


class AndroidFlowLogger:
    def __init__(self, name: str = DEFAULT_LOGGER_NAME) -> None:
        self.timings: Dict[str, int] = {}
        self._started_at = time.perf_counter()
        self._logger = logging.getLogger(name)
        self._logger.setLevel(logging.INFO)
        self._logger.propagate = False
        if not self._logger.handlers:
            handler = logging.StreamHandler(sys.stderr)
            handler.setLevel(logging.INFO)
            handler.setFormatter(
                logging.Formatter(
                    "%(asctime)s - %(name)s - %(levelname)s - %(message)s",
                    datefmt="%Y-%m-%d %H:%M:%S",
                )
            )
            self._logger.addHandler(handler)

    def info(self, event: str, **fields: Any) -> None:
        self._logger.info("%s%s", event, self._format_fields(fields))

    def warning(self, event: str, **fields: Any) -> None:
        self._logger.warning("%s%s", event, self._format_fields(fields))

    def error(self, event: str, **fields: Any) -> None:
        self._logger.error("%s%s", event, self._format_fields(fields))

    @contextmanager
    def stage(self, name: str, **fields: Any) -> Iterator[None]:
        started_at = time.perf_counter()
        self.info(f"{name}.start", **fields)
        try:
            yield
        except Exception as exc:
            duration_ms = self._elapsed_ms(started_at)
            self.timings[f"{name}_ms"] = duration_ms
            self.error(
                f"{name}.failed",
                duration_ms=duration_ms,
                error=type(exc).__name__,
                message=str(exc),
            )
            raise
        else:
            duration_ms = self._elapsed_ms(started_at)
            self.timings[f"{name}_ms"] = duration_ms
            self.info(f"{name}.done", duration_ms=duration_ms)

    def mark_total(self) -> None:
        self.timings["total_ms"] = self._elapsed_ms(self._started_at)

    @staticmethod
    def _elapsed_ms(started_at: float) -> int:
        return max(0, int((time.perf_counter() - started_at) * 1000))

    @staticmethod
    def _format_fields(fields: Dict[str, Any]) -> str:
        cleaned = {
            key: value
            for key, value in fields.items()
            if value is not None and value != ""
        }
        if not cleaned:
            return ""
        parts = [f"{key}={AndroidFlowLogger._safe_value(value)}" for key, value in cleaned.items()]
        return " | " + " ".join(parts)

    @staticmethod
    def _safe_value(value: Any) -> str:
        text = str(value).replace("\n", "\\n").replace("\r", "\\r")
        if len(text) > MAX_LOG_VALUE_LENGTH:
            return text[: MAX_LOG_VALUE_LENGTH - len(TRUNCATION_SUFFIX)] + TRUNCATION_SUFFIX
        return text


def new_flow_logger(name: str = "DyAndroidFlow") -> AndroidFlowLogger:
    return AndroidFlowLogger(name)


def url_preview(value: Optional[str], limit: int = DEFAULT_PREVIEW_LIMIT) -> str:
    text = str(value or "").replace("\n", " ").strip()
    if len(text) <= limit:
        return text
    return text[: max(0, limit - len(TRUNCATION_SUFFIX))] + TRUNCATION_SUFFIX
