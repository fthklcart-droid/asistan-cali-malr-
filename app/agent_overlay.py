"""Bilgisayar kontrolü sırasında ekranın köşesinde duran, her zaman üstte kalan durum/onay paneli.

Windows 10 2004+ / 11'de ekran görüntülerinden gizlenir; böylece Claude paneli görmez ve tıklamaz.
"""
from __future__ import annotations

import sys

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QHBoxLayout, QLabel, QPushButton, QVBoxLayout, QWidget

WDA_EXCLUDEFROMCAPTURE = 0x11


class AgentOverlay(QWidget):
    decided = Signal(str)  # "yes" | "no" | "stop"
    stop_requested = Signal()

    def __init__(self):
        super().__init__(None, Qt.WindowStaysOnTopHint | Qt.FramelessWindowHint | Qt.Tool)
        self.setAttribute(Qt.WA_ShowWithoutActivating, True)
        self.setFixedWidth(420)
        self.setStyleSheet("""
            QWidget { background:#0b1220; color:#e8f2ff; font-family:'Segoe UI'; font-size:13px; }
            QLabel#head { font-weight:700; color:#7dd3fc; }
            QPushButton { border-radius:8px; padding:8px 12px; font-weight:600; border:1px solid #38bdf8; background:#123b70; }
            QPushButton#stop { background:#7f1d1d; border-color:#f87171; }
            QPushButton#ok { background:#14532d; border-color:#4ade80; }
        """)
        lay = QVBoxLayout(self)
        head = QLabel("FK JARVIS • Bilgisayar kontrolü"); head.setObjectName("head")
        lay.addWidget(head)
        self.status = QLabel("Başlıyor…"); self.status.setWordWrap(True)
        lay.addWidget(self.status)
        self.actions = QLabel(""); self.actions.setWordWrap(True); self.actions.hide()
        lay.addWidget(self.actions)
        row = QHBoxLayout()
        self.ok = QPushButton("✔ Onayla"); self.ok.setObjectName("ok")
        self.no = QPushButton("✘ Reddet")
        self.ok.clicked.connect(lambda: self._decide("yes"))
        self.no.clicked.connect(lambda: self._decide("no"))
        row.addWidget(self.ok); row.addWidget(self.no)
        lay.addLayout(row)
        self.stop = QPushButton("■ DURDUR  (Ctrl+Alt+F12 veya fareyi sol üst köşeye götür)"); self.stop.setObjectName("stop")
        self.stop.clicked.connect(self._stop)
        lay.addWidget(self.stop)
        self._set_buttons(False)

    def showEvent(self, event):
        super().showEvent(event)
        screen = self.screen().availableGeometry()
        self.adjustSize()
        self.move(screen.right() - self.width() - 16, screen.top() + 16)
        if sys.platform == "win32":
            try:
                import ctypes
                ctypes.windll.user32.SetWindowDisplayAffinity(int(self.winId()), WDA_EXCLUDEFROMCAPTURE)
            except Exception:
                pass

    def reset(self):
        self.status.setText("Başlıyor…")
        self._set_buttons(False)

    def _set_buttons(self, asking: bool):
        self.ok.setVisible(asking); self.no.setVisible(asking); self.actions.setVisible(asking)
        self.adjustSize()

    def set_status(self, text: str):
        self.status.setText(text[-400:])
        self.adjustSize()

    def ask(self, lines: list[str]):
        self.actions.setText("Claude şu eylemleri yapmak istiyor:\n• " + "\n• ".join(lines))
        self._set_buttons(True)
        self.raise_()

    def _decide(self, decision: str):
        self._set_buttons(False)
        self.decided.emit(decision)

    def _stop(self):
        self._set_buttons(False)
        self.status.setText("Durduruluyor…")
        self.stop_requested.emit()
        self.decided.emit("stop")
