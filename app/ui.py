import sys

import requests
from PySide6.QtCore import QThread, QTimer, Signal
from PySide6.QtGui import QTextCursor
from PySide6.QtWidgets import (QApplication, QCheckBox, QComboBox, QFileDialog, QFormLayout, QGroupBox,
                               QHBoxLayout, QHeaderView, QInputDialog, QLabel, QLineEdit, QListWidget,
                               QMainWindow, QMessageBox, QPushButton, QSpinBox, QStyle, QSystemTrayIcon,
                               QTableWidget, QTableWidgetItem, QTabWidget, QTextBrowser, QTextEdit,
                               QVBoxLayout, QWidget)

from app import settings_store
from app.computer_agent import APPROVAL_MODES
from app.config import base_url, load_env

load_env()
BASE = base_url()
APP_IDS = ("photoshop", "premiere", "lightroom", "capcut", "browser")

STYLE = """
QMainWindow, QWidget { background:#080d18; color:#e8f2ff; font-family:'Segoe UI'; font-size:14px; }
QLabel#title { font-size:26px; font-weight:700; color:#7dd3fc; }
QLabel#hint { color:#94a3b8; font-size:12px; }
QTextEdit, QLineEdit, QListWidget, QTableWidget, QComboBox, QSpinBox { background:#101a2b; border:1px solid #1d4ed8; border-radius:8px; padding:6px; color:#eef7ff; }
QPushButton { background:#123b70; border:1px solid #38bdf8; border-radius:9px; padding:9px 14px; font-weight:600; }
QPushButton:hover { background:#155e9b; }
QPushButton:disabled { background:#1e293b; color:#64748b; }
QPushButton#primary { background:#0e7490; }
QPushButton#danger { background:#7f1d1d; border-color:#f87171; }
QTabWidget::pane { border:1px solid #1d4ed8; border-radius:8px; }
QTabBar::tab { background:#101a2b; padding:9px 16px; border-top-left-radius:8px; border-top-right-radius:8px; margin-right:2px; }
QTabBar::tab:selected { background:#123b70; }
QGroupBox { border:1px solid #1d4ed8; border-radius:8px; margin-top:14px; padding-top:10px; }
QGroupBox::title { subcontrol-origin: margin; left:10px; color:#7dd3fc; }
QHeaderView::section { background:#123b70; color:#e8f2ff; padding:6px; border:none; }
"""


def _error_text(exc: Exception) -> str:
    """HTTP hatalarında sunucunun Türkçe 'detail' mesajını göster."""
    response = getattr(exc, "response", None)
    if response is not None:
        try:
            detail = response.json().get("detail", exc)
            if isinstance(detail, list):  # pydantic doğrulama hatası
                return "; ".join(f"{'.'.join(map(str, d.get('loc', [])[1:]))}: {d.get('msg')}" for d in detail)
            return str(detail)
        except Exception:
            pass
    if isinstance(exc, requests.ConnectionError):
        return "Yerel köprüye ulaşılamadı. Uygulamayı run_windows.bat ile yeniden başlatın."
    return str(exc)


class CallWorker(QThread):
    """Herhangi bir fonksiyonu arka planda çalıştırır; (başarılı mı, sonuç/hata metni) yayar."""
    done = Signal(bool, object)

    def __init__(self, fn, parent=None):
        super().__init__(parent)
        self.fn = fn

    def run(self):
        try:
            self.done.emit(True, self.fn())
        except Exception as exc:
            self.done.emit(False, _error_text(exc))


class ChatWorker(QThread):
    chunk = Signal(str)
    status = Signal(str)
    computer_task = Signal(str)
    done = Signal(bool, str)

    def __init__(self, engine, text, parent=None):
        super().__init__(parent)
        self.engine, self.text = engine, text
        self._stop = False

    def stop(self):
        self._stop = True

    def run(self):
        try:
            answer = self.engine.send(self.text, self.chunk.emit, self.status.emit, self.computer_task.emit,
                                      lambda: self._stop)
            self.done.emit(True, answer)
        except Exception as exc:
            self.done.emit(False, str(exc))


class JarvisWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("FK JARVIS | Cloud + Local Agent")
        self.resize(1180, 820)
        self.setStyleSheet(STYLE)
        self._workers: set[QThread] = set()
        self.items: list[dict] = []
        self.voice_worker = None
        self.windows_voice_worker = None
        self.agent_worker = None
        self.overlay = None
        self.chat_worker = None
        self.chat_log: list[tuple[str, str]] = []
        from app.sohbet import Sohbet
        self.chat_engine = Sohbet()

        root = QWidget(); self.setCentralWidget(root)
        layout = QVBoxLayout(root)
        head = QHBoxLayout()
        title = QLabel("FK JARVIS"); title.setObjectName("title")
        head.addWidget(title)
        head.addWidget(QLabel("CLAUDE CLOUD BRAIN  •  WINDOWS LOCAL CREATIVE AGENT"))
        head.addStretch(1)
        self.status = QLabel("Yerel köprü kontrol ediliyor…")
        head.addWidget(self.status)
        layout.addLayout(head)
        self.config_status = QLabel(""); self.config_status.setWordWrap(True); self.config_status.setObjectName("hint")
        layout.addWidget(self.config_status)

        self.tabs = QTabWidget()
        layout.addWidget(self.tabs)
        self.tab_chat = self._build_chat_tab()
        self.tab_task = self._build_task_tab()
        self.tab_approvals = self._build_approvals_tab()
        self.tab_planner = self._build_planner_tab()
        self.tab_connectors = self._build_connectors_tab()
        self.tab_history = self._build_history_tab()
        self.tab_settings = self._build_settings_tab()
        for widget, label in ((self.tab_chat, "💬 Sohbet"), (self.tab_task, "🖥 Görev && Kontrol"),
                              (self.tab_approvals, "✅ Onaylar"), (self.tab_planner, "📅 Planlayıcı"),
                              (self.tab_connectors, "🔌 Bağlayıcılar"), (self.tab_history, "🕘 Geçmiş"),
                              (self.tab_settings, "⚙ Ayarlar")):
            self.tabs.addTab(widget, label)
        self.tabs.currentChanged.connect(self._tab_changed)

        self.tray = None
        if QSystemTrayIcon.isSystemTrayAvailable():
            self.tray = QSystemTrayIcon(self.style().standardIcon(QStyle.SP_ComputerIcon), self)
            self.tray.setToolTip("FK JARVIS")
            self.tray.activated.connect(lambda *_: (self.showNormal(), self.raise_(), self.activateWindow()))
            self.tray.show()

        self.refresh_all()
        self.health_timer = QTimer(self)
        self.health_timer.timeout.connect(self.check_health)
        self.health_timer.start(5000)
        self.reminder_timer = QTimer(self)
        self.reminder_timer.timeout.connect(self.check_reminders)
        self.reminder_timer.start(20000)
        QTimer.singleShot(3000, self.check_reminders)

    # ================= yardımcılar =================
    def run_bg(self, fn, on_done):
        worker = CallWorker(fn, self)
        worker.done.connect(on_done)
        worker.finished.connect(lambda: self._workers.discard(worker))
        self._workers.add(worker)
        worker.start()
        return worker

    @staticmethod
    def _post(path, payload, timeout=10):
        r = requests.post(BASE + path, json=payload, timeout=timeout); r.raise_for_status(); return r.json()

    @staticmethod
    def _get(path, timeout=5):
        r = requests.get(BASE + path, timeout=timeout); r.raise_for_status(); return r.json()

    def request_action(self, payload: dict):
        def done(ok, result):
            if ok:
                self.refresh_approvals()
                self.tabs.setCurrentWidget(self.tab_approvals)
                self.statusBar().showMessage("Onay isteği oluşturuldu: " + result["action"].get("summary", ""), 6000)
            else:
                QMessageBox.warning(self, "İstek başarısız", result)
        self.run_bg(lambda: self._post("/actions/request", payload), done)

    # ================= Sohbet sekmesi =================
    def _build_chat_tab(self):
        w = QWidget(); lay = QVBoxLayout(w)
        self.chat_view = QTextBrowser(); self.chat_view.setOpenExternalLinks(True)
        self.chat_view.setPlaceholderText("Merhaba! Bana soru sorabilir veya iş verebilirsin: hava durumu, haberler, "
                                          "hesap, hatırlatma, görev listesi, dosyalarına bakma, fatura okuma…")
        lay.addWidget(self.chat_view, 1)
        self.chat_status = QLabel(""); self.chat_status.setObjectName("hint")
        lay.addWidget(self.chat_status)
        row = QHBoxLayout()
        self.chat_input = QLineEdit(); self.chat_input.setPlaceholderText("Mesajını yaz ve Enter'a bas…")
        self.chat_input.returnPressed.connect(self.send_chat)
        self.chat_send = QPushButton("Gönder"); self.chat_send.setObjectName("primary"); self.chat_send.clicked.connect(self.send_chat)
        mic = QPushButton("🎤"); mic.setToolTip("4 saniye konuş; yazıya çevrilip gönderilir")
        mic.clicked.connect(self.chat_voice_input)
        row.addWidget(self.chat_input, 1); row.addWidget(mic); row.addWidget(self.chat_send)
        lay.addLayout(row)
        row2 = QHBoxLayout()
        self.speak_replies = QCheckBox("🔊 Yanıtları sesli oku")
        new = QPushButton("🧹 Yeni sohbet"); new.clicked.connect(self.new_chat)
        row2.addWidget(self.speak_replies); row2.addStretch(1); row2.addWidget(new)
        lay.addLayout(row2)
        return w

    def _render_chat(self):
        parts = []
        for role, text in self.chat_log:
            who = "**Siz:**" if role == "user" else "**JARVIS:**"
            parts.append(f"{who} {text}")
        self.chat_view.setMarkdown("\n\n---\n\n".join(parts))
        self.chat_view.moveCursor(QTextCursor.End)

    def send_chat(self, text: str | None = None):
        text = (text if isinstance(text, str) else self.chat_input.text()).strip()
        if not text:
            return
        if self.chat_worker and self.chat_worker.isRunning():
            self.chat_worker.stop()
            return
        self.chat_input.clear()
        self.chat_log.append(("user", text))
        self.chat_log.append(("assistant", ""))
        self._render_chat()
        self.chat_worker = ChatWorker(self.chat_engine, text, self)
        self.chat_worker.chunk.connect(self._chat_chunk)
        self.chat_worker.status.connect(self.chat_status.setText)
        self.chat_worker.computer_task.connect(self.receive_task)
        self.chat_worker.done.connect(self._chat_done)
        self.chat_send.setText("■ Durdur")
        self.chat_worker.start()

    def _chat_chunk(self, piece: str):
        role, text = self.chat_log[-1]
        self.chat_log[-1] = (role, text + piece)
        self.chat_view.moveCursor(QTextCursor.End)
        self.chat_view.insertPlainText(piece)

    def _chat_done(self, ok: bool, result: str):
        self.chat_send.setText("Gönder")
        self.chat_status.setText("")
        role, text = self.chat_log[-1]
        if not ok:
            self.chat_log[-1] = (role, f"⚠ {result}")
        elif not text.strip():
            self.chat_log[-1] = (role, result or "(boş yanıt)")
        self._render_chat()
        if ok and self.speak_replies.isChecked():
            from app import konusma
            err = konusma.soyle(self.chat_log[-1][1])
            if err:
                self.chat_status.setText("Sesli okuma: " + err)
        self.refresh_planner()

    def new_chat(self):
        if self.chat_worker and self.chat_worker.isRunning():
            return
        self.chat_engine.reset()
        self.chat_log.clear()
        self.chat_view.clear()

    def chat_voice_input(self):
        if self.windows_voice_worker and self.windows_voice_worker.isRunning():
            return
        from app.windows_voice_commands import DictationWorker
        self.windows_voice_worker = DictationWorker(self)
        self.windows_voice_worker.status.connect(self.chat_status.setText)
        self.windows_voice_worker.error.connect(lambda m: (self.chat_status.setText(""), QMessageBox.warning(self, "Sesli giriş", m)))
        self.windows_voice_worker.text_ready.connect(self.send_chat)
        self.windows_voice_worker.start()

    # ================= Planlayıcı sekmesi =================
    def _build_planner_tab(self):
        w = QWidget(); lay = QHBoxLayout(w)
        col1 = QVBoxLayout()
        col1.addWidget(QLabel("YAPILACAKLAR"))
        self.task_list = QListWidget(); col1.addWidget(self.task_list, 1)
        r = QHBoxLayout()
        self.new_task = QLineEdit(); self.new_task.setPlaceholderText("Yeni görev…")
        self.new_task.returnPressed.connect(self._add_task)
        add = QPushButton("Ekle"); add.clicked.connect(self._add_task)
        r.addWidget(self.new_task, 1); r.addWidget(add); col1.addLayout(r)
        r = QHBoxLayout()
        done = QPushButton("✔ Bitti"); done.clicked.connect(lambda: self._task_action("bitti"))
        rm = QPushButton("Sil"); rm.clicked.connect(lambda: self._task_action("sil"))
        r.addWidget(done); r.addWidget(rm); col1.addLayout(r)
        lay.addLayout(col1, 1)

        col2 = QVBoxLayout()
        col2.addWidget(QLabel("HATIRLATMALAR"))
        self.reminder_list = QListWidget(); col2.addWidget(self.reminder_list, 1)
        r = QHBoxLayout()
        self.rem_text = QLineEdit(); self.rem_text.setPlaceholderText("Ne hatırlatılsın?")
        self.rem_time = QLineEdit(); self.rem_time.setPlaceholderText("18:30 / +20dk / 2026-11-05 09:00")
        self.rem_time.setMaximumWidth(220)
        add = QPushButton("Kur"); add.clicked.connect(self._add_reminder)
        r.addWidget(self.rem_text, 1); r.addWidget(self.rem_time); r.addWidget(add); col2.addLayout(r)
        rm = QPushButton("Seçili hatırlatmayı sil"); rm.clicked.connect(self._delete_reminder)
        col2.addWidget(rm)
        lay.addLayout(col2, 1)

        col3 = QVBoxLayout()
        col3.addWidget(QLabel("HAFIZA (JARVIS'in sizin hakkınızda bildikleri)"))
        self.memory_list = QListWidget(); col3.addWidget(self.memory_list, 1)
        rm = QPushButton("Seçili kaydı unut"); rm.clicked.connect(self._delete_memory)
        col3.addWidget(rm)
        lay.addLayout(col3, 1)
        self._planner_data = {"tasks": [], "reminders": [], "memory": []}
        return w

    def refresh_planner(self):
        from app.beceriler import hafiza, planlayici
        try:
            tasks = planlayici.gorev_listele()["gorevler"]
            rems = planlayici.hatirlatma_listele()["hatirlatmalar"]
            mem = hafiza.tumu()
        except Exception:
            return
        self._planner_data = {"tasks": tasks, "reminders": rems, "memory": mem}
        self.task_list.clear()
        for t in tasks:
            icon = {"devam": "▶", "duraklatildi": "⏸"}.get(t["durum"], "☐")
            self.task_list.addItem(f"{icon} {t['no']}. {t['metin']}" + (f"  ({t['tarih']})" if t["tarih"] else ""))
        self.reminder_list.clear()
        for r in rems:
            self.reminder_list.addItem(f"⏰ {r['zaman']}  —  {r['metin']}")
        self.memory_list.clear()
        for m in mem:
            self.memory_list.addItem(f"[{m['kategori']}] {m['bilgi']}")

    def _add_task(self):
        from app.beceriler import planlayici
        text = self.new_task.text().strip()
        if text:
            planlayici.gorev_ekle(text); self.new_task.clear(); self.refresh_planner()

    def _task_action(self, kind):
        from app.beceriler import planlayici
        row = self.task_list.currentRow()
        if 0 <= row < len(self._planner_data["tasks"]):
            no = self._planner_data["tasks"][row]["no"]
            planlayici.gorev_durum(no, "bitti") if kind == "bitti" else planlayici.gorev_sil(no)
            self.refresh_planner()

    def _add_reminder(self):
        from app.beceriler import planlayici
        try:
            planlayici.hatirlatma_kur(self.rem_text.text(), self.rem_time.text())
        except Exception as exc:
            QMessageBox.warning(self, "Hatırlatma", str(exc)); return
        self.rem_text.clear(); self.rem_time.clear(); self.refresh_planner()

    def _delete_reminder(self):
        from app.beceriler import planlayici
        row = self.reminder_list.currentRow()
        if 0 <= row < len(self._planner_data["reminders"]):
            planlayici.hatirlatma_sil(self._planner_data["reminders"][row]["id"]); self.refresh_planner()

    def _delete_memory(self):
        from app.beceriler import hafiza
        row = self.memory_list.currentRow()
        if 0 <= row < len(self._planner_data["memory"]):
            hafiza.sil(self._planner_data["memory"][row]["id"]); self.refresh_planner()

    def check_reminders(self):
        from app.beceriler import planlayici
        try:
            due = planlayici.zamani_gelenler()
        except Exception:
            return
        for r in due:
            if self.tray:
                self.tray.showMessage("⏰ FK JARVIS hatırlatma", r["metin"], QSystemTrayIcon.Information, 15000)
            if self.speak_replies.isChecked():
                from app import konusma
                konusma.soyle("Hatırlatma: " + r["metin"])
            box = QMessageBox(QMessageBox.Information, "⏰ Hatırlatma", r["metin"], QMessageBox.Ok, self)
            box.setModal(False); box.show()
        if due:
            self.refresh_planner()

    # ================= Görev sekmesi =================
    def _build_task_tab(self):
        w = QWidget(); lay = QVBoxLayout(w)
        self.voice_status = QLabel("Sesli asistan: hazır")
        lay.addWidget(self.voice_status)
        voice_row = QHBoxLayout()
        self.voice_button = QPushButton("🎙 Gemini ile konuşmayı başlat")
        self.voice_button.clicked.connect(self.toggle_gemini_voice)
        self.windows_voice_button = QPushButton("🎤 Windows sesli komut (4 sn)")
        self.windows_voice_button.clicked.connect(self.start_windows_voice_command)
        voice_row.addWidget(self.voice_button); voice_row.addWidget(self.windows_voice_button)
        lay.addLayout(voice_row)

        self.task = QTextEdit()
        self.task.setPlaceholderText("Görevini yaz veya söyle… Örn: Chrome'u açıp hava durumuna bak; Photoshop'ta açık fotoğrafı 1080 piksele küçült.")
        self.task.setMaximumHeight(110)
        lay.addWidget(self.task)

        row = QHBoxLayout()
        self.send_button = QPushButton("📝 Claude ile planla"); self.send_button.clicked.connect(self.plan)
        self.agent_button = QPushButton("🖥 Bilgisayarda yaptır"); self.agent_button.setObjectName("primary")
        self.agent_button.clicked.connect(self.start_agent)
        row.addWidget(self.send_button); row.addWidget(self.agent_button)
        lay.addLayout(row)

        opts = QHBoxLayout()
        opts.addWidget(QLabel("Onay modu:"))
        self.approval_mode = QComboBox()
        for key, label in APPROVAL_MODES.items():
            self.approval_mode.addItem(label, key)
        opts.addWidget(self.approval_mode, 1)
        opts.addWidget(QLabel("En fazla adım:"))
        self.max_turns = QSpinBox(); self.max_turns.setRange(3, 200); self.max_turns.setValue(40)
        opts.addWidget(self.max_turns)
        lay.addLayout(opts)
        hint = QLabel("Bilgisayar kontrolünde pencere küçülür, sağ üstte onay/DURDUR paneli çıkar. Acil durdurma: "
                      "Ctrl+Alt+F12 veya fareyi ekranın sol üst köşesine götürün. Mesaj gönderme, yayınlama, ödeme, "
                      "silme gibi işlemlerden önce Claude size ayrıca sorar.")
        hint.setWordWrap(True); hint.setObjectName("hint")
        lay.addWidget(hint)

        self.output = QTextEdit(); self.output.setReadOnly(True)
        self.output.setPlaceholderText("Claude planı, sesli görüşme ve bilgisayar kontrolü günlüğü burada görünür.")
        lay.addWidget(self.output, 1)
        return w

    def plan(self):
        instruction = self.task.toPlainText().strip()
        if len(instruction) < 3:
            QMessageBox.information(self, "FK JARVIS", "Lütfen en az 3 karakterlik bir görev yazın.")
            return
        self.output.setPlainText("Claude görev planı hazırlanıyor…")
        self.send_button.setEnabled(False)

        def done(ok, result):
            self.send_button.setEnabled(True)
            self.output.setPlainText(result["plan"] + "\n\n" + result["note"] if ok else f"Hata: {result}")
        self.run_bg(lambda: self._post("/task", {"instruction": instruction}, timeout=180), done)

    def receive_task(self, text: str):
        """Sesli komuttan gelen görevi görev alanına koy (otomatik çalıştırmaz)."""
        self.task.setPlainText(text)
        self.tabs.setCurrentWidget(self.tab_task)
        self.output.append(f"🎯 Sesli görev alındı: {text}\n   → 'Claude ile planla' veya 'Bilgisayarda yaptır' ile başlatın.")
        self.showNormal(); self.raise_(); self.activateWindow()

    # ----- bilgisayar kontrolü -----
    def start_agent(self):
        task = self.task.toPlainText().strip()
        if len(task) < 3:
            QMessageBox.information(self, "FK JARVIS", "Önce yapılacak görevi yazın.")
            return
        if self.agent_worker and self.agent_worker.isRunning():
            return
        mode = self.approval_mode.currentData()
        if mode == "auto":
            sure = QMessageBox.question(self, "Tam otomatik mod",
                                        "Claude fare ve klavyeyi onay sormadan kullanacak. Emin misiniz?")
            if sure != QMessageBox.Yes:
                return
        from app.agent_overlay import AgentOverlay
        from app.agent_worker import AgentWorker
        self.output.clear()
        self.agent_worker = AgentWorker(task, mode, self.max_turns.value(), parent=self)
        self.overlay = AgentOverlay()
        w, o = self.agent_worker, self.overlay
        w.log_line.connect(self._agent_log)
        w.approval_needed.connect(o.ask)
        w.question.connect(self._agent_question)
        w.finished_result.connect(self._agent_finished)
        o.decided.connect(w.provide_answer)
        o.stop_requested.connect(w.stop)
        self.agent_button.setEnabled(False)
        o.show()
        self.showMinimized()
        QTimer.singleShot(600, w.start)  # pencere küçülsün, sonra ilk ekran görüntüsü alınsın

    def _agent_log(self, line: str):
        self.output.append(line)
        if self.overlay:
            self.overlay.set_status(line)

    def _agent_question(self, question: str):
        self.showNormal(); self.raise_(); self.activateWindow()
        answer, ok = QInputDialog.getText(self, "Claude soruyor", question + "\n\n(Yanıtınızı yazın; ör. 'evet', 'hayır' veya açıklama)")
        if ok:
            self.agent_worker.provide_answer(answer.strip() or "(boş yanıt)")
        else:
            self.agent_worker.provide_answer("Kullanıcı yanıt vermedi; bu işlemi yapma.")
        self.showMinimized()

    def _agent_finished(self, result):
        if self.overlay:
            self.overlay.close(); self.overlay.deleteLater(); self.overlay = None
        self.agent_button.setEnabled(True)
        self.showNormal(); self.raise_(); self.activateWindow()
        icon = {"done": "✅", "stopped": "⏹", "refused": "⚠", "limit": "⏱", "error": "❌"}.get(result.status, "")
        self.output.append(f"\n{icon} Sonuç: {result.summary}\n(Adım: {result.turns}, eylem: {result.actions})")
        self.refresh_history()

    # ----- ses -----
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
        self.voice_worker.transcript.connect(self.output.append)
        self.voice_worker.error.connect(self.voice_error)
        self.voice_worker.task_requested.connect(self.receive_task)
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
        self.windows_voice_worker.transcript.connect(self.output.append)
        self.windows_voice_worker.error.connect(self.windows_voice_error)
        self.windows_voice_worker.unmatched.connect(self.receive_task)
        self.windows_voice_worker.finished.connect(lambda: self.windows_voice_button.setEnabled(True))
        self.windows_voice_worker.finished.connect(self.refresh_approvals)
        self.windows_voice_button.setEnabled(False)
        self.windows_voice_worker.start()

    def windows_voice_error(self, message):
        self.voice_status.setText("Windows sesli komut: hata")
        QMessageBox.warning(self, "Sesli komut", message)

    def voice_error(self, message):
        self.voice_status.setText("Gemini sesli asistan: hata")
        self.output.append("Ses hatası: " + message)
        QMessageBox.warning(self, "Gemini sesli asistan", message)

    # ================= Onaylar sekmesi =================
    def _build_approvals_tab(self):
        w = QWidget(); lay = QVBoxLayout(w)
        lay.addWidget(QLabel("BEKLEYEN EYLEMLER — onay verilmeden hiçbiri çalışmaz"))
        self.pending = QListWidget()
        lay.addWidget(self.pending, 1)
        row = QHBoxLayout()
        approve = QPushButton("✔ Seçileni onayla ve çalıştır"); approve.setObjectName("primary")
        approve.clicked.connect(lambda: self.decide(True))
        reject = QPushButton("✘ Reddet"); reject.clicked.connect(lambda: self.decide(False))
        refresh = QPushButton("↻ Yenile"); refresh.clicked.connect(self.refresh_approvals)
        row.addWidget(approve); row.addWidget(reject); row.addWidget(refresh)
        lay.addLayout(row)
        box = QGroupBox("Uygulama başlat"); bl = QHBoxLayout(box)
        self.action_app = QComboBox(); self.action_app.addItems(APP_IDS)
        req = QPushButton("Başlatma onayı iste")
        req.clicked.connect(lambda: self.request_action({"action": "launch_app", "app_id": self.action_app.currentText(),
                                                         "description": f"{self.action_app.currentText()} uygulamasını aç"}))
        bl.addWidget(self.action_app, 1); bl.addWidget(req)
        lay.addWidget(box)
        return w

    def refresh_approvals(self):
        def done(ok, result):
            if not ok:
                return
            self.items = result
            self.pending.clear()
            for item in self.items:
                a = item["action"]
                self.pending.addItem(f"{a.get('summary') or a.get('description')}   ·   {item['created_at'][11:19]}")
            if self.items:
                self.pending.setCurrentRow(0)
            idx = self.tabs.indexOf(self.tab_approvals)
            self.tabs.setTabText(idx, f"✅ Onaylar ({len(self.items)})" if self.items else "✅ Onaylar")
        self.run_bg(lambda: self._get("/approvals"), done)

    def decide(self, approved):
        row = self.pending.currentRow()
        if row < 0 or row >= len(self.items):
            QMessageBox.information(self, "FK JARVIS", "Önce listeden bir eylem seçin.")
            return
        item = self.items[row]
        self.statusBar().showMessage("İşleniyor…")

        def done(ok, result):
            self.statusBar().clearMessage()
            if ok:
                msg = result.get("result") or ("Onaylandı." if approved else "Reddedildi.")
                QMessageBox.information(self, "FK JARVIS", msg)
            else:
                QMessageBox.warning(self, "İşlem başarısız", result)
            self.refresh_approvals(); self.refresh_history()
        # YouTube yüklemesi uzun sürebilir: zaman aşımı geniş tutulur.
        self.run_bg(lambda: self._post(f"/approvals/{item['id']}", {"approved": approved}, timeout=3600), done)

    # ================= Bağlayıcılar sekmesi =================
    def _build_connectors_tab(self):
        w = QWidget(); lay = QVBoxLayout(w)
        note = QLabel("Buradaki her işlem önce Onaylar listesine düşer; siz onaylamadan gönderilmez/yayınlanmaz.")
        note.setObjectName("hint"); lay.addWidget(note)

        wa = QGroupBox("WhatsApp Business (Cloud API)"); f = QFormLayout(wa)
        self.wa_to = QLineEdit(); self.wa_to.setPlaceholderText("+90 5xx xxx xx xx")
        self.wa_body = QTextEdit(); self.wa_body.setMaximumHeight(70)
        btn = QPushButton("Gönderim onayı iste")
        btn.clicked.connect(lambda: self.request_action({"action": "whatsapp_send", "to": self.wa_to.text().strip(),
                                                         "body": self.wa_body.toPlainText().strip()}))
        f.addRow("Alıcı:", self.wa_to); f.addRow("Mesaj:", self.wa_body); f.addRow("", btn)
        lay.addWidget(wa)

        yt = QGroupBox("YouTube (Data API v3)"); f = QFormLayout(yt)
        file_row = QHBoxLayout()
        self.yt_file = QLineEdit()
        pick = QPushButton("Gözat…")
        pick.clicked.connect(lambda: self._pick_file(self.yt_file, "Video (*.mp4 *.mov *.mkv *.avi *.webm)"))
        file_row.addWidget(self.yt_file, 1); file_row.addWidget(pick)
        self.yt_title = QLineEdit(); self.yt_title.setMaxLength(100)
        self.yt_desc = QTextEdit(); self.yt_desc.setMaximumHeight(60)
        self.yt_privacy = QComboBox()
        for key, label in (("private", "Özel (önerilen)"), ("unlisted", "Liste dışı"), ("public", "Herkese açık")):
            self.yt_privacy.addItem(label, key)
        btn = QPushButton("Yükleme onayı iste")
        btn.clicked.connect(lambda: self.request_action({
            "action": "youtube_upload", "file_path": self.yt_file.text().strip(), "title": self.yt_title.text().strip(),
            "video_description": self.yt_desc.toPlainText().strip(), "privacy": self.yt_privacy.currentData()}))
        f.addRow("Video:", file_row); f.addRow("Başlık:", self.yt_title); f.addRow("Açıklama:", self.yt_desc)
        f.addRow("Gizlilik:", self.yt_privacy); f.addRow("", btn)
        lay.addWidget(yt)

        ps = QGroupBox("Photoshop (güvenli komutlar)"); f = QFormLayout(ps)
        from app.connectors.photoshop import COMMANDS
        self.ps_cmd = QComboBox()
        for key, label in COMMANDS.items():
            self.ps_cmd.addItem(label, key)
        path_row = QHBoxLayout()
        self.ps_path = QLineEdit(); self.ps_path.setPlaceholderText("Açılacak dosya veya dışa aktarma hedefi")
        pick = QPushButton("Gözat…")
        pick.clicked.connect(self._pick_ps_path)
        path_row.addWidget(self.ps_path, 1); path_row.addWidget(pick)
        self.ps_value = QSpinBox(); self.ps_value.setRange(0, 30000); self.ps_value.setValue(1080)
        btn = QPushButton("Komut onayı iste")
        btn.clicked.connect(lambda: self.request_action({
            "action": "photoshop", "command": self.ps_cmd.currentData(), "path": self.ps_path.text().strip(),
            "value": self.ps_value.value()}))
        f.addRow("Komut:", self.ps_cmd); f.addRow("Dosya:", path_row); f.addRow("Piksel:", self.ps_value); f.addRow("", btn)
        lay.addWidget(ps)
        lay.addStretch(1)
        return w

    def _pick_file(self, target: QLineEdit, filt: str):
        path, _ = QFileDialog.getOpenFileName(self, "Dosya seç", target.text(), filt)
        if path:
            target.setText(path)

    def _pick_ps_path(self):
        if self.ps_cmd.currentData() in ("export_jpeg", "export_png"):
            path, _ = QFileDialog.getSaveFileName(self, "Hedef dosya", self.ps_path.text(), "Görüntü (*.jpg *.png)")
        else:
            path, _ = QFileDialog.getOpenFileName(self, "Dosya seç", self.ps_path.text(),
                                                  "Görüntü (*.psd *.jpg *.jpeg *.png *.tif *.tiff *.cr2 *.nef *.arw *.dng)")
        if path:
            self.ps_path.setText(path)

    # ================= Geçmiş sekmesi =================
    def _build_history_tab(self):
        w = QWidget(); lay = QVBoxLayout(w)
        self.history_table = QTableWidget(0, 4)
        self.history_table.setHorizontalHeaderLabels(["Zaman", "Tür", "Başlık", "Durum"])
        self.history_table.horizontalHeader().setSectionResizeMode(2, QHeaderView.Stretch)
        self.history_table.setEditTriggers(QTableWidget.NoEditTriggers)
        self.history_table.setSelectionBehavior(QTableWidget.SelectRows)
        self.history_table.itemSelectionChanged.connect(self._history_selected)
        lay.addWidget(self.history_table, 2)
        self.history_detail = QTextEdit(); self.history_detail.setReadOnly(True)
        lay.addWidget(self.history_detail, 1)
        row = QHBoxLayout()
        refresh = QPushButton("↻ Yenile"); refresh.clicked.connect(self.refresh_history)
        row.addWidget(refresh); row.addStretch(1)
        lay.addLayout(row)
        self.history_rows: list[dict] = []
        return w

    def refresh_history(self):
        def done(ok, result):
            if not ok:
                return
            self.history_rows = result
            t = self.history_table
            t.setRowCount(len(result))
            for i, ev in enumerate(result):
                for j, key in enumerate(("ts", "kind", "title", "status")):
                    value = ev[key].replace("T", " ") if key == "ts" else ev[key]
                    t.setItem(i, j, QTableWidgetItem(value))
            t.resizeColumnToContents(0); t.resizeColumnToContents(1)
        self.run_bg(lambda: self._get("/history"), done)

    def _history_selected(self):
        row = self.history_table.currentRow()
        if 0 <= row < len(self.history_rows):
            ev = self.history_rows[row]
            self.history_detail.setPlainText(f"{ev['title']}\n\n{ev['detail']}")

    # ================= Ayarlar sekmesi =================
    def _build_settings_tab(self):
        w = QWidget(); lay = QVBoxLayout(w)
        keys = QGroupBox("API anahtarları ve seçenekler (.env)"); f = QFormLayout(keys)
        self.setting_inputs: dict[str, QWidget] = {}
        values = settings_store.read_env()
        inputs, outputs = settings_store.audio_devices()
        for key, label, secret in settings_store.FIELDS:
            if key in ("AUDIO_INPUT_DEVICE", "AUDIO_OUTPUT_DEVICE"):
                combo = QComboBox(); combo.setEditable(True)
                combo.addItem("")
                combo.addItems(inputs if key == "AUDIO_INPUT_DEVICE" else outputs)
                combo.setCurrentText(values.get(key, ""))
                self.setting_inputs[key] = combo
                f.addRow(label + ":", combo)
                continue
            edit = QLineEdit(values.get(key, ""))
            if secret:
                edit.setEchoMode(QLineEdit.Password)
                edit.setPlaceholderText("girilmedi")
            if key == "YOUTUBE_CLIENT_SECRET_FILE":
                row = QHBoxLayout(); row.addWidget(edit, 1)
                pick = QPushButton("Gözat…"); pick.clicked.connect(lambda _=False, e=edit: self._pick_file(e, "JSON (*.json)"))
                row.addWidget(pick)
                f.addRow(label + ":", row)
            else:
                f.addRow(label + ":", edit)
            self.setting_inputs[key] = edit
        show = QCheckBox("Anahtarları göster")
        show.toggled.connect(lambda on: [self.setting_inputs[k].setEchoMode(QLineEdit.Normal if on else QLineEdit.Password)
                                         for k in settings_store.SECRET_KEYS])
        f.addRow("", show)
        lay.addWidget(keys)

        apps = QGroupBox("Uygulama yolları (boş = otomatik bul)"); f = QFormLayout(apps)
        self.app_inputs: dict[str, QLineEdit] = {}
        current = settings_store.read_apps()
        for app_id in APP_IDS:
            row = QHBoxLayout()
            edit = QLineEdit(current.get(app_id, ""))
            pick = QPushButton("Gözat…")
            pick.clicked.connect(lambda _=False, e=edit: self._pick_file(e, "Program (*.exe)"))
            row.addWidget(edit, 1); row.addWidget(pick)
            self.app_inputs[app_id] = edit
            f.addRow(app_id + ":", row)
        lay.addWidget(apps)

        row = QHBoxLayout()
        save = QPushButton("💾 Kaydet"); save.setObjectName("primary"); save.clicked.connect(self.save_settings)
        row.addWidget(save); row.addStretch(1)
        lay.addLayout(row)
        note = QLabel("Anahtarlar yalnızca bu bilgisayardaki .env dosyasında saklanır. Port değişikliği yeniden başlatınca geçerli olur.")
        note.setObjectName("hint"); note.setWordWrap(True)
        lay.addWidget(note)
        lay.addStretch(1)
        return w

    def save_settings(self):
        values = {}
        for key, widget in self.setting_inputs.items():
            values[key] = widget.currentText() if isinstance(widget, QComboBox) else widget.text()
        try:
            settings_store.write_env(values)
            settings_store.write_apps({k: e.text() for k, e in self.app_inputs.items()})
        except Exception as exc:
            QMessageBox.warning(self, "Kaydedilemedi", str(exc))
            return
        QMessageBox.information(self, "FK JARVIS", "Ayarlar kaydedildi.")
        self.refresh_status()

    # ================= durum =================
    def _tab_changed(self, index):
        widget = self.tabs.widget(index)
        if widget is self.tab_approvals:
            self.refresh_approvals()
        elif widget is self.tab_history:
            self.refresh_history()
        elif widget is self.tab_planner:
            self.refresh_planner()

    def refresh_all(self):
        self.check_health(); self.refresh_status(); self.refresh_approvals(); self.refresh_history()
        self.refresh_planner()

    def check_health(self):
        def done(ok, _):
            if ok:
                self.status.setText(f"● Köprü çevrimiçi — {BASE.removeprefix('http://')}")
                self.status.setStyleSheet("color:#4ade80;")
            else:
                self.status.setText("● Yerel köprü kapalı — run_windows.bat ile başlatın")
                self.status.setStyleSheet("color:#f87171;")
        self.run_bg(lambda: self._get("/health", timeout=2), done)

    def refresh_status(self):
        def done(ok, data):
            if not ok:
                return
            mark = lambda v: "✔" if v else "✘"  # noqa: E731
            apps = data.get("apps", {})
            found = [k for k, v in apps.items() if v]
            missing = [k for k, v in apps.items() if not v]
            parts = [f"Claude {mark(data.get('anthropic_key'))}", f"Gemini {mark(data.get('gemini_key'))}",
                     f"WhatsApp {mark(data.get('whatsapp'))}", f"YouTube {mark(data.get('youtube'))}",
                     "Uygulamalar: " + (", ".join(found) or "yok")]
            if missing:
                parts.append("bulunamayan: " + ", ".join(missing))
            self.config_status.setText("   •   ".join(parts) + "   (değiştirmek için ⚙ Ayarlar)")
        self.run_bg(lambda: self._get("/status"), done)

    def closeEvent(self, event):
        if self.chat_worker and self.chat_worker.isRunning():
            self.chat_worker.stop(); self.chat_worker.wait(5000)
        if self.agent_worker and self.agent_worker.isRunning():
            self.agent_worker.stop(); self.agent_worker.wait(5000)
        if self.voice_worker and self.voice_worker.isRunning():
            self.voice_worker.stop(); self.voice_worker.wait(3000)
        for worker in list(self._workers):
            worker.wait(2000)
        super().closeEvent(event)


def main() -> int:
    app = QApplication.instance() or QApplication(sys.argv)
    window = JarvisWindow(); window.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
