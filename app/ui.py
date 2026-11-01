import sys

import requests
from PySide6.QtCore import QThread, QTimer, Signal
from PySide6.QtWidgets import (QApplication, QHBoxLayout, QLabel, QLineEdit, QListWidget, QMainWindow,
                               QMessageBox, QPushButton, QTextEdit, QVBoxLayout, QWidget)

from app.config import base_url, load_env

load_env()
BASE = base_url()


def _error_text(exc: Exception) -> str:
    """HTTP hatalarında sunucunun Türkçe 'detail' mesajını göster."""
    response = getattr(exc, "response", None)
    if response is not None:
        try:
            return str(response.json().get("detail", exc))
        except Exception:
            pass
    if isinstance(exc, requests.ConnectionError):
        return "Yerel köprüye ulaşılamadı. Uygulamayı run_windows.bat ile yeniden başlatın."
    return str(exc)


class PlanWorker(QThread):
    """Claude isteğini arayüzü dondurmadan arka planda çalıştırır."""
    done = Signal(str)

    def __init__(self, instruction: str, parent=None):
        super().__init__(parent)
        self.instruction = instruction

    def run(self):
        try:
            r = requests.post(BASE + "/task", json={"instruction": self.instruction}, timeout=180)
            r.raise_for_status()
            data = r.json()
            self.done.emit(data["plan"] + "\n\n" + data["note"])
        except Exception as e:
            self.done.emit(f"Hata: {_error_text(e)}")


class JarvisWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("FK JARVIS | Cloud + Local Agent")
        self.resize(1100, 760)
        self.setStyleSheet("""
            QMainWindow, QWidget { background:#080d18; color:#e8f2ff; font-family:'Segoe UI'; font-size:14px; }
            QLabel#title { font-size:26px; font-weight:700; color:#7dd3fc; }
            QTextEdit, QLineEdit, QListWidget { background:#101a2b; border:1px solid #1d4ed8; border-radius:10px; padding:10px; color:#eef7ff; }
            QPushButton { background:#123b70; border:1px solid #38bdf8; border-radius:9px; padding:10px 16px; font-weight:600; }
            QPushButton:hover { background:#155e9b; }
            QPushButton:disabled { background:#1e293b; color:#64748b; }
        """)
        root = QWidget(); self.setCentralWidget(root)
        layout = QVBoxLayout(root)
        title = QLabel("FK JARVIS"); title.setObjectName("title")
        subtitle = QLabel("CLAUDE CLOUD BRAIN  •  WINDOWS LOCAL CREATIVE AGENT")
        layout.addWidget(title); layout.addWidget(subtitle)
        self.status = QLabel("Yerel köprü kontrol ediliyor…")
        layout.addWidget(self.status)
        self.config_status = QLabel("")
        self.config_status.setWordWrap(True)
        layout.addWidget(self.config_status)
        self.voice_status = QLabel("Sesli asistan: hazır")
        layout.addWidget(self.voice_status)
        voice_row = QHBoxLayout()
        self.voice_button = QPushButton("🎙 Gemini ile konuşmayı başlat")
        self.voice_button.clicked.connect(self.toggle_gemini_voice)
        voice_row.addWidget(self.voice_button)
        self.windows_voice_button = QPushButton("🎤 Windows sesli komut (4 sn)")
        self.windows_voice_button.clicked.connect(self.start_windows_voice_command)
        voice_row.addWidget(self.windows_voice_button)
        layout.addLayout(voice_row)
        self.task = QTextEdit(); self.task.setPlaceholderText("Görevini yaz… Örn: Photoshop'u aç ve ürün fotoğrafı düzenleme iş akışını hazırla.")
        self.task.setMaximumHeight(120); layout.addWidget(self.task)
        row = QHBoxLayout()
        self.send_button = QPushButton("Claude ile planla"); self.send_button.clicked.connect(self.plan)
        refresh = QPushButton("Onayları yenile"); refresh.clicked.connect(self.refresh_all)
        row.addWidget(self.send_button); row.addWidget(refresh); layout.addLayout(row)
        self.output = QTextEdit(); self.output.setReadOnly(True); self.output.setPlaceholderText("Claude görev planı burada görünür.")
        layout.addWidget(self.output)
        layout.addWidget(QLabel("BEKLEYEN YEREL EYLEMLER"))
        self.pending = QListWidget(); layout.addWidget(self.pending)
        row2 = QHBoxLayout()
        self.action_app = QLineEdit(); self.action_app.setPlaceholderText("photoshop / premiere / lightroom / capcut / browser")
        request = QPushButton("Uygulama başlatma onayı iste"); request.clicked.connect(self.request_launch)
        approve = QPushButton("Seçileni onayla"); approve.clicked.connect(lambda: self.decide(True))
        reject = QPushButton("Reddet"); reject.clicked.connect(lambda: self.decide(False))
        row2.addWidget(self.action_app); row2.addWidget(request); row2.addWidget(approve); row2.addWidget(reject)
        layout.addLayout(row2)
        self.items = []
        self.voice_worker = None
        self.windows_voice_worker = None
        self.plan_worker = None
        self.refresh_all()
        # Köprü geç açılırsa durum kendiliğinden güncellensin.
        self.health_timer = QTimer(self)
        self.health_timer.timeout.connect(self.check_health)
        self.health_timer.start(5000)

    # ---------- ses ----------
    def toggle_gemini_voice(self):
        if self.voice_worker and self.voice_worker.isRunning():
            self.voice_worker.stop()
            self.voice_status.setText("Gemini sesli asistan durduruluyor…")
            return
        try:
            from app.gemini_voice import GeminiVoiceWorker
        except Exception as exc:
            self.voice_error(f"Gemini modülü yüklenemedi: {exc}")
            return
        self.voice_worker = GeminiVoiceWorker(self)
        self.voice_worker.status.connect(self.voice_status.setText)
        self.voice_worker.transcript.connect(self.append_voice_transcript)
        self.voice_worker.error.connect(self.voice_error)
        self.voice_worker.finished.connect(lambda: self.voice_button.setText("🎙 Gemini ile konuşmayı başlat"))
        self.voice_button.setText("■ Sesli görüşmeyi durdur")
        self.voice_worker.start()

    def start_windows_voice_command(self):
        if self.windows_voice_worker and self.windows_voice_worker.isRunning():
            return
        try:
            from app.windows_voice_commands import WindowsVoiceCommandWorker
        except Exception as exc:
            QMessageBox.warning(self, "Sesli komut", f"Sesli komut modülü yüklenemedi: {exc}")
            return
        self.windows_voice_worker = WindowsVoiceCommandWorker(self)
        self.windows_voice_worker.status.connect(self.voice_status.setText)
        self.windows_voice_worker.transcript.connect(self.append_voice_transcript)
        self.windows_voice_worker.error.connect(self.windows_voice_error)
        self.windows_voice_worker.finished.connect(lambda: self.windows_voice_button.setEnabled(True))
        self.windows_voice_worker.finished.connect(self.refresh_approvals)
        self.windows_voice_button.setEnabled(False)
        self.windows_voice_worker.start()

    def windows_voice_error(self, message):
        self.voice_status.setText("Windows sesli komut: hata")
        QMessageBox.warning(self, "Sesli komut", message)

    def append_voice_transcript(self, line):
        self.output.append(line)

    def voice_error(self, message):
        self.voice_status.setText("Gemini sesli asistan: hata")
        self.output.append("Ses hatası: " + message)
        QMessageBox.warning(self, "Gemini sesli asistan", message)

    # ---------- köprü ----------
    def refresh_all(self):
        self.check_health(); self.refresh_status(); self.refresh_approvals()

    def check_health(self):
        try:
            r = requests.get(BASE + "/health", timeout=2); r.raise_for_status()
            self.status.setText(f"● Yerel köprü çevrimiçi — {BASE.removeprefix('http://')}")
            self.status.setStyleSheet("color:#4ade80;")
        except Exception:
            self.status.setText("● Yerel köprü kapalı. Uygulamayı run_windows.bat ile başlatın.")
            self.status.setStyleSheet("color:#f87171;")

    def refresh_status(self):
        try:
            r = requests.get(BASE + "/status", timeout=5); r.raise_for_status()
            data = r.json()
        except Exception:
            return
        parts = [
            "Claude anahtarı: " + ("✔" if data.get("anthropic_key") else "✘ (.env > ANTHROPIC_API_KEY)"),
            "Gemini anahtarı: " + ("✔" if data.get("gemini_key") else "✘ (.env > GEMINI_API_KEY)"),
        ]
        apps = data.get("apps", {})
        found = [k for k, v in apps.items() if v]
        missing = [k for k, v in apps.items() if not v]
        parts.append("Bulunan uygulamalar: " + (", ".join(found) or "yok"))
        if missing:
            parts.append("Bulunamayan: " + ", ".join(missing) + " (config/apps.json)")
        self.config_status.setText("   •   ".join(parts))

    def plan(self):
        instruction = self.task.toPlainText().strip()
        if len(instruction) < 3:
            QMessageBox.information(self, "FK JARVIS", "Lütfen en az 3 karakterlik bir görev yazın.")
            return
        if self.plan_worker and self.plan_worker.isRunning():
            return
        self.output.setPlainText("Claude görev planı hazırlanıyor…")
        self.send_button.setEnabled(False)
        self.plan_worker = PlanWorker(instruction, self)
        self.plan_worker.done.connect(self.output.setPlainText)
        self.plan_worker.finished.connect(lambda: self.send_button.setEnabled(True))
        self.plan_worker.start()

    def request_launch(self):
        app_id = self.action_app.text().strip().lower()
        allowed = ("photoshop", "premiere", "lightroom", "capcut", "browser")
        if app_id not in allowed:
            QMessageBox.warning(self, "Geçersiz uygulama", "Şunlardan birini yazın: " + " / ".join(allowed))
            return
        try:
            r = requests.post(BASE + "/actions/request", json={"action": "launch_app", "app_id": app_id, "description": f"{app_id} uygulamasını aç"}, timeout=5)
            r.raise_for_status(); self.refresh_approvals()
        except Exception as e: QMessageBox.warning(self, "İstek başarısız", _error_text(e))

    def refresh_approvals(self):
        try:
            r = requests.get(BASE + "/approvals", timeout=5); r.raise_for_status()
            self.items = r.json(); self.pending.clear()
            for item in self.items:
                self.pending.addItem(f"{item['action']['app_id']}  |  {item['action']['description']}  |  {item['id'][:8]}")
            if self.items and self.pending.currentRow() < 0:
                self.pending.setCurrentRow(0)
        except Exception: pass

    def decide(self, approved):
        row = self.pending.currentRow()
        if row < 0 or row >= len(self.items):
            QMessageBox.information(self, "FK JARVIS", "Önce listeden bir eylem seçin.")
            return
        item = self.items[row]
        try:
            r = requests.post(BASE + f"/approvals/{item['id']}", json={"approved": approved}, timeout=15); r.raise_for_status()
            data = r.json()
            msg = data.get("result") or ("Onaylandı." if approved else "Reddedildi.")
            QMessageBox.information(self, "FK JARVIS", msg)
        except Exception as e: QMessageBox.warning(self, "İşlem başarısız", _error_text(e))
        self.refresh_approvals()

    def closeEvent(self, event):
        for worker in (self.voice_worker,):
            if worker and worker.isRunning():
                worker.stop(); worker.wait(3000)
        super().closeEvent(event)


def main() -> int:
    app = QApplication.instance() or QApplication(sys.argv)
    window = JarvisWindow(); window.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
