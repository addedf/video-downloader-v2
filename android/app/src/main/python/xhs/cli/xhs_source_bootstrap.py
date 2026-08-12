import sys
import types
from pathlib import Path


def install_source_package() -> Path:
    xhs_root = Path(__file__).resolve().parents[1]
    source_root = xhs_root / "source"
    root_text = str(xhs_root)
    if root_text not in sys.path:
        sys.path.insert(0, root_text)

    _ensure_package("source", source_root)
    _ensure_package("source.application", source_root / "application")
    _ensure_package("source.module", source_root / "module")
    return xhs_root


def install_module_exports() -> None:
    install_source_package()
    import source.module as module
    from source.module.extend import Account
    from source.module.manager import Manager
    from source.module.mapping import Mapping
    from source.module.recorder import DataRecorder, IDRecorder, MapRecorder
    from source.module.settings import Settings
    from source.module.static import (
        ERROR,
        FILE_SIGNATURES,
        FILE_SIGNATURES_LENGTH,
        GENERAL,
        HEADERS,
        INFO,
        LICENCE,
        MASTER,
        MAX_WORKERS,
        PROJECT,
        PROGRESS,
        PROMPT,
        RELEASES,
        REPOSITORY,
        ROOT,
        USERAGENT,
        USERSCRIPT,
        VERSION_BETA,
        VERSION_MAJOR,
        VERSION_MINOR,
        WARNING,
        __VERSION__,
    )
    from source.module.tools import logging, retry, retry_limited, sleep_time

    exports = {
        "Account": Account,
        "Manager": Manager,
        "DataRecorder": DataRecorder,
        "IDRecorder": IDRecorder,
        "MapRecorder": MapRecorder,
        "Mapping": Mapping,
        "Settings": Settings,
        "VERSION_MAJOR": VERSION_MAJOR,
        "VERSION_MINOR": VERSION_MINOR,
        "VERSION_BETA": VERSION_BETA,
        "ROOT": ROOT,
        "REPOSITORY": REPOSITORY,
        "LICENCE": LICENCE,
        "RELEASES": RELEASES,
        "MASTER": MASTER,
        "PROMPT": PROMPT,
        "GENERAL": GENERAL,
        "PROGRESS": PROGRESS,
        "ERROR": ERROR,
        "WARNING": WARNING,
        "INFO": INFO,
        "USERSCRIPT": USERSCRIPT,
        "HEADERS": HEADERS,
        "PROJECT": PROJECT,
        "USERAGENT": USERAGENT,
        "FILE_SIGNATURES": FILE_SIGNATURES,
        "FILE_SIGNATURES_LENGTH": FILE_SIGNATURES_LENGTH,
        "MAX_WORKERS": MAX_WORKERS,
        "__VERSION__": __VERSION__,
        "retry": retry,
        "logging": logging,
        "sleep_time": sleep_time,
        "retry_limited": retry_limited,
    }
    for name, value in exports.items():
        setattr(module, name, value)


def _ensure_package(name: str, path: Path) -> types.ModuleType:
    existing = sys.modules.get(name)
    if existing is not None:
        existing.__path__ = [str(path)]
        return existing
    module = types.ModuleType(name)
    module.__file__ = str(path / "__init__.py")
    module.__path__ = [str(path)]
    module.__package__ = name
    sys.modules[name] = module
    return module
