import sys

import requests
from datetime import datetime

from PySide6.QtCore import QEasingCurve, QPropertyAnimation, Qt, QThread, QTimer, Signal
from PySide6.QtGui import QColor, QTextCursor
from PySide6.QtWidgets import (QApplication, QButtonGroup, QCheckBox, QComboBox, QFileDialog, QFormLayout,
                               QFrame, QGraphicsDropShadowEffect, QGridLayout, QGroupBox, QHBoxLayout,
                               QHeaderView, QInputDialog, QLabel, QLineEdit, QListWidget, QMainWindow,
                               QMessageBox, QPushButton, QScrollArea, QSpinBox, QStackedWidget, QStyle,
                               QSystemTrayIcon, QTableWidget, QTableWidgetItem, QTextBrowser, QTextEdit,
                               QVBoxLayout, QWidget)

from app import settings_store
from app.computer_agent import APPROVAL_MODES
from app.config import base_url, load_env

load_env()
BASE = base_url()
APP_IDS = ("photoshop", "premiere", "lightroom", "capcut", "browser")

# Tema: docs/TEMA_VE_ARAYUZ_TR.md — gece laciverti zemin, neon camgöbeği + holografik mor vurgu
CYAN, PURPLE, GREEN, RED, AMBER, MUTED = "#38BDF8", "#8B5CF6", "#4ADE80", "#F87171", "#FBBF24", "#8DA6C4"
STYLE = """
QMainWindow, QWidget { background:#070B14; color:#EAF4FF; font-family:'Segoe UI'; font-size:14px; }
QFrame#sidebar { background:#0B1220; border:1px solid #17243A; border-radius:18px; }
QFrame#panel { background:#0E1829; border:1px solid #1B3555; border-radius:16px; }
QFrame#panel QLabel, QFrame#sidebar QLabel, QFrame#panel QCheckBox,
QGroupBox QLabel, QGroupBox QCheckBox { background:transparent; }
QLabel#brand { font-size:25px; font-weight:800; color:#EAF7FF; letter-spacing:2px; }
QLabel#eyebrow { font-size:10px; font-weight:700; color:#38BDF8; letter-spacing:2px; }
QLabel#heading { font-size:21px; font-weight:700; color:#F2F8FF; }
QLabel#muted, QLabel#hint { color:#8DA6C4; font-size:12px; }
QLabel#cardvalue { font-size:11px; font-weight:700; }
QLabel#greeting { font-size:25px; font-weight:700; color:#F0F9FF; }
QTextEdit, QLineEdit, QListWidget, QTableWidget, QComboBox, QSpinBox, QTextBrowser {
    background:#081321; border:1px solid #24476B; border-radius:10px; padding:8px; color:#EAF4FF;
    selection-background-color:#155E9B; }
QTextEdit:focus, QLineEdit:focus { border:1px solid #38BDF8; }
QPushButton { background:#102D4C; border:1px solid #2676A8; border-radius:10px; padding:9px 14px; color:#EAF7FF; font-weight:600; }
QPushButton:hover { background:#16456E; border:1px solid #38BDF8; }
QPushButton:disabled { background:#111a2a; color:#4b5d75; border-color:#1b2a40; }
QPushButton#primary { background:#075985; border:1px solid #38BDF8; }
QPushButton#primary:hover { background:#0369A1; }
QPushButton#accent { background:#3B2A78; border:1px solid #8B5CF6; }
QPushButton#accent:hover { background:#4C3696; }
QPushButton#danger { background:#7f1d1d; border-color:#f87171; }
QPushButton#nav { background:transparent; border:0; border-radius:10px; text-align:left; padding:11px 12px; color:#9FB5CE; }
QPushButton#nav:hover { background:#12243A; color:#7DD3FC; }
QPushButton#nav:checked { background:#12243A; color:#7DD3FC; border-left:3px solid #38BDF8; }
QGroupBox { background:#0E1829; border:1px solid #1B3555; border-radius:16px; margin-top:16px; padding:12px 10px 10px 10px;
            font-size:11px; font-weight:700; letter-spacing:1px; }
QGroupBox::title { subcontrol-origin: margin; left:14px; padding:0 4px; color:#38BDF8; font-size:11px; font-weight:700; letter-spacing:1px; }
QHeaderView::section { background:#102D4C; color:#EAF4FF; padding:6px; border:none; }
QScrollArea { border:none; background:transparent; }
QScrollBar:vertical { background:#0B1220; width:10px; border-radius:5px; }
QScrollBar::handle:vertical { background:#1B3555; border-radius:5px; min-height:30px; }
QStatusBar { color:#8DA6C4; }
"""


def panel(title: str, sub: str = ""):
    """Tasarımdaki yuvarlak köşeli koyu panel: (çerçeve, iç düzen)."""
    frame = QFrame(); frame.setObjectName("panel")
    lay = QVBoxLayout(frame); lay.setContentsMargins(16, 14, 16, 14)
    head = QLabel(title); head.setObjectName("eyebrow"); lay.addWidget(head)
    if sub:
        desc = QLabel(sub); desc.setObjectName("muted"); desc.setWordWrap(True); lay.addWidget(desc)
    return frame, lay


class SideNav(QWidget):
    """Sol gezinme çubuğu + sayfa yığını. QTabWidget ile aynı temel arayüzü sunar."""
    currentChanged = Signal(int)

    def __init__(self, button_layout: QVBoxLayout, parent=None):
        super().__init__(parent)
        self.stack = QStackedWidget()
        lay = QVBoxLayout(self); lay.setContentsMargins(0, 0, 0, 0); lay.addWidget(self.stack)
        self._layout = button_layout
        self._group = QButtonGroup(self); self._group.setExclusive(True)
        self._buttons: list[QPushButton] = []
        self._labels: list[str] = []

    def addTab(self, widget: QWidget, label: str) -> int:
        idx = self.stack.addWidget(widget)
        btn = QPushButton(label); btn.setObjectName("nav"); btn.setCheckable(True); btn.setCursor(Qt.PointingHandCursor)
        btn.clicked.connect(lambda _=False, i=idx: self.setCurrentIndex(i))
        self._group.addButton(btn); self._layout.addWidget(btn)
        self._buttons.append(btn); self._labels.append(label)
        if idx == 0:
            btn.setChecked(True)
        return idx

    def setCurrentIndex(self, idx: int):
        if 0 <= idx < self.stack.count():
            self._buttons[idx].setChecked(True)
            if self.stack.currentIndex() != idx:
                self.stack.setCurrentIndex(idx)
                self.currentChanged.emit(idx)

    def setCurrentWidget(self, widget: QWidget):
        self.setCurrentIndex(self.stack.indexOf(widget))

    def currentIndex(self) -> int: return self.stack.currentIndex()
    def currentWidget(self) -> QWidget: return self.stack.currentWidget()
    def indexOf(self, widget: QWidget) -> int: return self.stack.indexOf(widget)
    def widget(self, idx: int) -> QWidget: return self.stack.widget(idx)
    def count(self) -> int: return self.stack.count()
    def tabText(self, idx: int) -> str: return self._buttons[idx].text()

    def setTabText(self, idx: int, text: str):
        self._buttons[idx].setText(text)

    def baseLabel(self, idx: int) -> str:
        return self._labels[idx]



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
        self.setWindowTitle("FK JARVIS | Command Center")
        self.resize(1440, 900)
        self.setMinimumSize(1100, 720)
        self.setStyleSheet(STYLE)
        self._workers: set[QThread] = set()
        self._bridge_online = False
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
        outer = QHBoxLayout(root); outer.setContentsMargins(18, 18, 18, 10); outer.setSpacing(16)

        side = QFrame(); side.setObjectName("sidebar"); side.setFixedWidth(230)
        sl = QVBoxLayout(side); sl.setContentsMargins(14, 20, 14, 16); sl.setSpacing(2)
        brand = QLabel("FK JARVIS"); brand.setObjectName("brand"); sl.addWidget(brand)
        tag = QLabel("ART MEDYA • AI STUDIO"); tag.setObjectName("eyebrow"); sl.addWidget(tag)
        sl.addSpacing(22)
        nav_buttons = QVBoxLayout(); nav_buttons.setSpacing(2); sl.addLayout(nav_buttons)
        sl.addStretch(1)
        self.side_status = QLabel("● LOCAL AGENT  •  BEKLENİYOR"); self.side_status.setObjectName("muted")
        sl.addWidget(self.side_status)
        outer.addWidget(side)

        main = QVBoxLayout(); main.setSpacing(12); outer.addLayout(main, 1)
        top = QHBoxLayout(); titles = QVBoxLayout(); titles.setSpacing(0)
        self.page_eyebrow = QLabel("PERSONAL AI WORKSPACE"); self.page_eyebrow.setObjectName("eyebrow")
        self.page_heading = QLabel("Kontrol Merkezi"); self.page_heading.setObjectName("heading")
        titles.addWidget(self.page_eyebrow); titles.addWidget(self.page_heading)
        top.addLayout(titles); top.addStretch(1)
        self.status = QLabel("● SUNUCU KONTROL EDİLİYOR"); self.status.setObjectName("muted")
        top.addWidget(self.status)
        main.addLayout(top)
        self.config_status = QLabel(""); self.config_status.setWordWrap(True); self.config_status.setObjectName("hint")

        self.tabs = SideNav(nav_buttons)
        main.addWidget(self.tabs, 1)
        foot = QLabel("FK ART MEDYA  •  PRIVATE AI SYSTEM  •  ACTIONS REQUIRE APPROVAL"); foot.setObjectName("muted")
        foot.setAlignment(Qt.AlignRight); main.addWidget(foot)

        self.tab_overview = self._build_overview_page()
        self.tab_chat = self._build_chat_tab()
        self.tab_voice = self._build_voice_page()
        self.tab_task = self._build_task_tab()
        self.tab_social = self._build_social_page()
        self.tab_approvals = self._build_approvals_tab()
        self.tab_planner = self._build_planner_tab()
        self.tab_history = self._build_history_tab()
        self.tab_settings = self._build_settings_tab()
        self.pages = [
            (self.tab_overview, "⌂   Genel Bakış", "PERSONAL AI WORKSPACE", "Kontrol Merkezi"),
            (self.tab_chat, "◉   Asistan Merkezi", "CLAUDE • WEB • BECERİLER", "Asistan Merkezi"),
            (self.tab_voice, "◌   Sesli Komutlar", "GEMINI LIVE • WINDOWS SES", "Sesli Komutlar"),
            (self.tab_task, "▣   Kreatif Stüdyo", "BİLGİSAYAR KONTROLÜ • ADOBE • CAPCUT", "Kreatif Stüdyo"),
            (self.tab_social, "◎   Sosyal Medya", "WHATSAPP • YOUTUBE", "Sosyal Medya"),
            (self.tab_approvals, "◈   Görev Onayları", "HİÇBİR DIŞ İŞLEM ONAYSIZ ÇALIŞMAZ", "Görev Onayları"),
            (self.tab_planner, "▦   Planlayıcı", "GÖREVLER • HATIRLATMALAR • HAFIZA", "Planlayıcı"),
            (self.tab_history, "◷   Geçmiş", "TÜM İŞLEM KAYITLARI", "Geçmiş"),
            (self.tab_settings, "⚙   Ayarlar", "ANAHTARLAR • SES AYGITLARI • UYGULAMALAR", "Ayarlar"),
        ]
        for widget, label, _, _ in self.pages:
            self.tabs.addTab(widget, label)
        self.tabs.currentChanged.connect(self._tab_changed)
        self.statusBar()

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
        if hasattr(self, "today_list"):
            today = datetime.now().strftime("%Y-%m-%d")
            self.today_list.clear()
            for r in rems:
                if r["zaman"][:10] == today:
                    self.today_list.addItem(f"⏰ {r['zaman'][11:16]}  {r['metin']}")
            for t in tasks:
                if not t["tarih"] or t["tarih"] <= today:
                    self.today_list.addItem(f"☐ {t['metin']}" + ("  (gecikti)" if t["tarih"] and t["tarih"] < today else ""))
            if self.today_list.count() == 0:
                self.today_list.addItem("Bugün için açık görev veya hatırlatma yok.")

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

    # ================= Genel Bakış =================
    def _build_overview_page(self):
        scroll = QScrollArea(); scroll.setWidgetResizable(True)
        w = QWidget(); lay = QVBoxLayout(w); lay.setContentsMargins(0, 0, 6, 0); lay.setSpacing(14)

        hero, hl = panel("HOLOGRAPHIC CORE", "Claude planlama • Gemini ses • Windows yerel ajan")
        row = QHBoxLayout(); row.setSpacing(20)
        self.core = QLabel("◉"); self.core.setAlignment(Qt.AlignCenter); self.core.setFixedSize(140, 140)
        self.core.setStyleSheet("font-size:92px;color:#38BDF8;border:2px solid #155E75;border-radius:70px;background:#091B2B;")
        self.core_glow = QGraphicsDropShadowEffect(self.core)
        self.core_glow.setColor(QColor(CYAN)); self.core_glow.setOffset(0, 0); self.core_glow.setBlurRadius(18)
        self.core.setGraphicsEffect(self.core_glow)
        self.core_anim = QPropertyAnimation(self.core_glow, b"blurRadius", self)
        self.core_anim.setStartValue(12); self.core_anim.setKeyValueAt(0.5, 46); self.core_anim.setEndValue(12)
        self.core_anim.setDuration(2600); self.core_anim.setLoopCount(-1); self.core_anim.setEasingCurve(QEasingCurve.InOutSine)
        self.core_anim.start()
        row.addWidget(self.core)
        info = QVBoxLayout(); info.setSpacing(6)
        self.greeting = QLabel("Merhaba, FK."); self.greeting.setObjectName("greeting"); info.addWidget(self.greeting)
        d = QLabel("Bugünkü üretim akışını yönetmeye hazırım. Bir görev yaz veya sesli komut ver.")
        d.setObjectName("muted"); d.setWordWrap(True); info.addWidget(d)
        self.core_status = QLabel("Sistem başlatılıyor…")
        self.core_status.setStyleSheet("color:#7DD3FC;font-weight:600;background:transparent;")
        info.addWidget(self.core_status)
        quick = QHBoxLayout()
        self.quick_input = QLineEdit(); self.quick_input.setPlaceholderText("JARVIS'e bir şey sor veya görev ver…")
        self.quick_input.returnPressed.connect(self._quick_ask)
        ask = QPushButton("✦  Asistana sor"); ask.setObjectName("primary"); ask.clicked.connect(self._quick_ask)
        do = QPushButton("▣  Bilgisayarda yaptır"); do.setObjectName("accent"); do.clicked.connect(self._quick_do)
        mic = QPushButton("🎙"); mic.setToolTip("Gemini Live sesli görüşme"); mic.clicked.connect(self.toggle_gemini_voice)
        quick.addWidget(self.quick_input, 1); quick.addWidget(ask); quick.addWidget(do); quick.addWidget(mic)
        info.addLayout(quick)
        row.addLayout(info, 1)
        hl.addLayout(row)
        lay.addWidget(hero)

        cards = QGridLayout(); cards.setSpacing(12)
        self.cards: dict[str, QLabel] = {}
        specs = [("claude", "CLAUDE CLOUD", "Sohbet • planlama • bilgisayar kontrolü"),
                 ("gemini", "GEMINI LIVE", "Türkçe sesli görüşme"),
                 ("windows", "WINDOWS AGENT", "Yerel köprü • uygulama kontrolü"),
                 ("android", "ANDROID 15/16", "Mobil kontrol merkezi")]
        for i, (key, title, desc) in enumerate(specs):
            c, l = panel(title, desc); l.addSpacing(6)
            v = QLabel("● BAĞLANTI BEKLENİYOR"); v.setObjectName("cardvalue"); v.setStyleSheet(f"color:{MUTED};")
            l.addWidget(v); self.cards[key] = v
            cards.addWidget(c, 0, i)
        lay.addLayout(cards)

        work = QHBoxLayout(); work.setSpacing(12)
        today, tl = panel("BUGÜN", "Açık görevler ve bugünkü hatırlatmalar")
        self.today_list = QListWidget(); self.today_list.setMinimumHeight(170); tl.addWidget(self.today_list)
        go_plan = QPushButton("Planlayıcıyı aç"); go_plan.clicked.connect(lambda: self.tabs.setCurrentWidget(self.tab_planner))
        tl.addWidget(go_plan)
        work.addWidget(today, 1)
        pend, pl = panel("BEKLEYEN İŞLEM ONAYLARI", "Uygulama açma, mesaj, yükleme ve Photoshop komutları")
        self.overview_pending = QListWidget(); self.overview_pending.setMinimumHeight(170); pl.addWidget(self.overview_pending)
        go_appr = QPushButton("Onaylara git"); go_appr.clicked.connect(lambda: self.tabs.setCurrentWidget(self.tab_approvals))
        pl.addWidget(go_appr)
        work.addWidget(pend, 1)
        lay.addLayout(work)
        lay.addStretch(1)
        scroll.setWidget(w)
        return scroll

    def _quick_ask(self):
        text = self.quick_input.text().strip()
        if not text:
            return
        self.quick_input.clear()
        self.tabs.setCurrentWidget(self.tab_chat)
        self.send_chat(text)

    def _quick_do(self):
        text = self.quick_input.text().strip()
        if not text:
            QMessageBox.information(self, "FK JARVIS", "Önce bilgisayarda yapılacak görevi yazın.")
            return
        self.quick_input.clear()
        self.task.setPlainText(text)
        self.tabs.setCurrentWidget(self.tab_task)

    def _set_card(self, key: str, text: str, color: str):
        label = self.cards.get(key)
        if label:
            label.setText(text); label.setStyleSheet(f"color:{color};")

    def _update_greeting(self):
        h = datetime.now().hour
        word = "Günaydın" if 5 <= h < 12 else "İyi günler" if h < 18 else "İyi akşamlar" if h < 23 else "İyi geceler"
        self.greeting.setText(f"{word}, FK.")

    def _set_core_mode(self, mode: str):
        """Çekirdek rengi: idle (camgöbeği), active (mor: ses/ajan çalışıyor)."""
        color = PURPLE if mode == "active" else CYAN
        self.core_glow.setColor(QColor(color))
        self.core.setStyleSheet(f"font-size:92px;color:{color};border:2px solid "
                                f"{'#5B3FA8' if mode == 'active' else '#155E75'};border-radius:70px;background:#091B2B;")

    # ================= Sesli Komutlar =================
    def _build_voice_page(self):
        w = QWidget(); lay = QHBoxLayout(w); lay.setContentsMargins(0, 0, 0, 0); lay.setSpacing(12)
        left, ll = panel("SES KONTROLÜ", "Mikrofon erişimi ve internet bağlantısı gereklidir")
        self.voice_status = QLabel("Ses sistemi: hazır")
        self.voice_status.setStyleSheet("color:#7DD3FC;font-weight:600;background:transparent;")
        self.voice_status.setWordWrap(True)
        ll.addWidget(self.voice_status)
        self.voice_button = QPushButton("◉  Gemini Live görüşmesini başlat"); self.voice_button.setObjectName("primary")
        self.voice_button.clicked.connect(self.toggle_gemini_voice)
        ll.addWidget(self.voice_button)
        self.windows_voice_button = QPushButton("🎙  Windows sesli komut dinle (4 sn)")
        self.windows_voice_button.clicked.connect(self.start_windows_voice_command)
        ll.addWidget(self.windows_voice_button)
        hint = QLabel("• Gemini Live: doğal Türkçe sohbet. \"Photoshop'ta kataloğu hazırla\" gibi bir iş isterseniz görev "
                      "Kreatif Stüdyo'ya aktarılır, siz başlatırsınız.\n"
                      "• Windows sesli komut: \"Photoshop'u aç\", \"Premiere'i aç\", \"Chrome'u aç\" → onay isteği. "
                      "Başka bir cümle söylerseniz Kreatif Stüdyo'ya görev olarak aktarılır.\n"
                      "• Yankıyı önlemek için kulaklık önerilir. Mikrofon/hoparlör seçimi: ⚙ Ayarlar.")
        hint.setObjectName("muted"); hint.setWordWrap(True)
        ll.addWidget(hint); ll.addStretch(1)
        lay.addWidget(left, 2)
        right, rl = panel("KONUŞMA KAYDI", "Sizin ve JARVIS'in söyledikleri")
        self.voice_log = QTextEdit(); self.voice_log.setReadOnly(True)
        rl.addWidget(self.voice_log, 1)
        clear = QPushButton("Kaydı temizle"); clear.clicked.connect(self.voice_log.clear)
        rl.addWidget(clear)
        lay.addWidget(right, 3)
        return w

    # ================= Kreatif Stüdyo =================
    def _build_task_tab(self):
        scroll = QScrollArea(); scroll.setWidgetResizable(True)
        w = QWidget(); lay = QHBoxLayout(w); lay.setContentsMargins(0, 0, 6, 0); lay.setSpacing(12)
        col = QVBoxLayout(); col.setSpacing(12)

        cmd, cl = panel("GÖREV KOMUTA ALANI", "Claude ile planla veya Claude ekranı görüp fare/klavyeyle yapsın")
        self.task = QTextEdit()
        self.task.setPlaceholderText("Örn: Photoshop'ta açık fotoğrafa otomatik ton uygula ve masaüstüne JPEG olarak kaydet.")
        self.task.setMaximumHeight(110)
        cl.addWidget(self.task)
        row = QHBoxLayout()
        self.send_button = QPushButton("✦  Claude ile planla"); self.send_button.clicked.connect(self.plan)
        self.agent_button = QPushButton("▣  Bilgisayarda yaptır"); self.agent_button.setObjectName("accent")
        self.agent_button.clicked.connect(self.start_agent)
        row.addWidget(self.send_button); row.addWidget(self.agent_button)
        cl.addLayout(row)
        opts = QHBoxLayout()
        lbl = QLabel("Onay modu:"); opts.addWidget(lbl)
        self.approval_mode = QComboBox()
        for key, label in APPROVAL_MODES.items():
            self.approval_mode.addItem(label, key)
        opts.addWidget(self.approval_mode, 1)
        opts.addWidget(QLabel("En fazla adım:"))
        self.max_turns = QSpinBox(); self.max_turns.setRange(3, 200); self.max_turns.setValue(40)
        opts.addWidget(self.max_turns)
        cl.addLayout(opts)
        hint = QLabel("Bilgisayar kontrolünde pencere küçülür, sağ üstte onay/DURDUR paneli çıkar. Acil durdurma: "
                      "Ctrl+Alt+F12 veya fareyi ekranın sol üst köşesine götürün.")
        hint.setWordWrap(True); hint.setObjectName("hint")
        cl.addWidget(hint)
        self.output = QTextEdit(); self.output.setReadOnly(True); self.output.setMinimumHeight(220)
        self.output.setPlaceholderText("Görev planı ve bilgisayar kontrolü günlüğü burada görünür.")
        cl.addWidget(self.output, 1)
        col.addWidget(cmd, 1)
        lay.addLayout(col, 3)

        side = QVBoxLayout(); side.setSpacing(12)
        apps, al = panel("UYGULAMALAR", "Açma isteği onaya düşer")
        grid = QGridLayout(); grid.setSpacing(8)
        icons = {"photoshop": "Ps  Photoshop", "premiere": "Pr  Premiere", "lightroom": "Lr  Lightroom",
                 "capcut": "✂  CapCut", "browser": "🌐  Tarayıcı"}
        for i, app_id in enumerate(APP_IDS):
            b = QPushButton(icons[app_id])
            b.clicked.connect(lambda _=False, a=app_id: self.request_action(
                {"action": "launch_app", "app_id": a, "description": f"{a} uygulamasını aç"}))
            grid.addWidget(b, i // 2, i % 2)
        al.addLayout(grid)
        side.addWidget(apps)

        ps = QGroupBox("PHOTOSHOP • GÜVENLİ KOMUTLAR"); f = QFormLayout(ps)
        from app.connectors.photoshop import COMMANDS
        self.ps_cmd = QComboBox()
        for key, label in COMMANDS.items():
            self.ps_cmd.addItem(label, key)
        path_row = QHBoxLayout()
        self.ps_path = QLineEdit(); self.ps_path.setPlaceholderText("Dosya / dışa aktarma hedefi")
        pick = QPushButton("Gözat…"); pick.clicked.connect(self._pick_ps_path)
        path_row.addWidget(self.ps_path, 1); path_row.addWidget(pick)
        self.ps_value = QSpinBox(); self.ps_value.setRange(0, 30000); self.ps_value.setValue(1080)
        btn = QPushButton("Komut onayı iste"); btn.setObjectName("primary")
        btn.clicked.connect(lambda: self.request_action({
            "action": "photoshop", "command": self.ps_cmd.currentData(), "path": self.ps_path.text().strip(),
            "value": self.ps_value.value()}))
        f.addRow("Komut:", self.ps_cmd); f.addRow("Dosya:", path_row); f.addRow("Piksel:", self.ps_value); f.addRow(btn)
        side.addWidget(ps)
        side.addStretch(1)
        lay.addLayout(side, 2)
        scroll.setWidget(w)
        return scroll

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
        if self.overlay is None:
            # Panel bir kez oluşturulur ve yeniden kullanılır (silip yeniden yaratmak Qt'de çökmeye yol açabilir).
            self.overlay = AgentOverlay()
            self.overlay.decided.connect(self._overlay_decided)
            self.overlay.stop_requested.connect(self._overlay_stop)
        w, o = self.agent_worker, self.overlay
        o.reset()
        w.log_line.connect(self._agent_log)
        w.approval_needed.connect(o.ask)
        w.question.connect(self._agent_question)
        w.finished_result.connect(self._agent_finished)
        self.agent_button.setEnabled(False)
        self._set_core_mode("active")
        o.show()
        self.showMinimized()
        QTimer.singleShot(600, w.start)  # pencere küçülsün, sonra ilk ekran görüntüsü alınsın

    def _overlay_decided(self, decision: str):
        if self.agent_worker and self.agent_worker.isRunning():
            self.agent_worker.provide_answer(decision)

    def _overlay_stop(self):
        if self.agent_worker and self.agent_worker.isRunning():
            self.agent_worker.stop()

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
            self.overlay.hide()
        self.agent_button.setEnabled(True)
        self._set_core_mode("idle")
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
        self.voice_worker.transcript.connect(self.voice_log.append)
        self.voice_worker.error.connect(self.voice_error)
        self.voice_worker.task_requested.connect(self.receive_task)
        self.voice_worker.finished.connect(self._voice_finished)
        self.voice_button.setText("■  Görüşmeyi durdur")
        self._set_core_mode("active"); self._set_card("gemini", "● GÖRÜŞME AKTİF", PURPLE)
        self.voice_worker.start()

    def _voice_finished(self):
        self.voice_button.setText("◉  Gemini Live görüşmesini başlat")
        self._set_core_mode("idle")
        self.refresh_status()

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
        self.windows_voice_worker.transcript.connect(self.voice_log.append)
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
        self.voice_log.append("Ses hatası: " + message)
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
            base = self.tabs.baseLabel(idx)
            self.tabs.setTabText(idx, f"{base}  ({len(self.items)})" if self.items else base)
            self.overview_pending.clear()
            for item in self.items[:8]:
                self.overview_pending.addItem("◈ " + (item["action"].get("summary") or item["action"].get("description", "")))
            if not self.items:
                self.overview_pending.addItem("Bekleyen onay yok.")
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

    # ================= Sosyal Medya =================
    def _build_social_page(self):
        w = QWidget(); lay = QHBoxLayout(w); lay.setContentsMargins(0, 0, 0, 0); lay.setSpacing(12)
        wa = QGroupBox("WHATSAPP BUSINESS • CLOUD API"); f = QFormLayout(wa)
        self.wa_to = QLineEdit(); self.wa_to.setPlaceholderText("+90 5xx xxx xx xx")
        self.wa_body = QTextEdit(); self.wa_body.setMinimumHeight(140)
        btn = QPushButton("Gönderim onayı iste"); btn.setObjectName("primary")
        btn.clicked.connect(lambda: self.request_action({"action": "whatsapp_send", "to": self.wa_to.text().strip(),
                                                         "body": self.wa_body.toPlainText().strip()}))
        note = QLabel("Mesaj, Görev Onayları'nda onaylanınca gönderilir. Ayar: ⚙ Ayarlar > WhatsApp.")
        note.setObjectName("muted"); note.setWordWrap(True)
        f.addRow("Alıcı:", self.wa_to); f.addRow("Mesaj:", self.wa_body); f.addRow(btn); f.addRow(note)
        lay.addWidget(wa, 1)

        yt = QGroupBox("YOUTUBE • DATA API v3"); f = QFormLayout(yt)
        file_row = QHBoxLayout()
        self.yt_file = QLineEdit()
        pick = QPushButton("Gözat…")
        pick.clicked.connect(lambda: self._pick_file(self.yt_file, "Video (*.mp4 *.mov *.mkv *.avi *.webm)"))
        file_row.addWidget(self.yt_file, 1); file_row.addWidget(pick)
        self.yt_title = QLineEdit(); self.yt_title.setMaxLength(100)
        self.yt_desc = QTextEdit(); self.yt_desc.setMinimumHeight(100)
        self.yt_privacy = QComboBox()
        for key, label in (("private", "Özel (önerilen)"), ("unlisted", "Liste dışı"), ("public", "Herkese açık")):
            self.yt_privacy.addItem(label, key)
        btn = QPushButton("Yükleme onayı iste"); btn.setObjectName("primary")
        btn.clicked.connect(lambda: self.request_action({
            "action": "youtube_upload", "file_path": self.yt_file.text().strip(), "title": self.yt_title.text().strip(),
            "video_description": self.yt_desc.toPlainText().strip(), "privacy": self.yt_privacy.currentData()}))
        note = QLabel("İlk yüklemede tarayıcıda Google hesabı izni istenir. Varsayılan gizlilik: özel.")
        note.setObjectName("muted"); note.setWordWrap(True)
        f.addRow("Video:", file_row); f.addRow("Başlık:", self.yt_title); f.addRow("Açıklama:", self.yt_desc)
        f.addRow("Gizlilik:", self.yt_privacy); f.addRow(btn); f.addRow(note)
        lay.addWidget(yt, 1)
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
        scroll = QScrollArea(); scroll.setWidgetResizable(True)
        w = QWidget(); lay = QVBoxLayout(w); lay.setContentsMargins(0, 0, 6, 0)
        lay.addWidget(self.config_status)
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
        scroll.setWidget(w)
        return scroll

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
        for page, _label, eyebrow, heading in self.pages:
            if page is widget:
                self.page_eyebrow.setText(eyebrow); self.page_heading.setText(heading)
        if widget is self.tab_overview:
            self.refresh_status(); self.refresh_planner(); self._update_greeting()
        elif widget is self.tab_approvals:
            self.refresh_approvals()
        elif widget is self.tab_history:
            self.refresh_history()
        elif widget is self.tab_planner:
            self.refresh_planner()

    def refresh_all(self):
        self._update_greeting()
        self.check_health(); self.refresh_status(); self.refresh_approvals(); self.refresh_history()
        self.refresh_planner()

    def check_health(self):
        def done(ok, _):
            self._bridge_online = ok
            if ok:
                self.status.setText(f"● YEREL KÖPRÜ ÇEVRİMİÇİ  •  {BASE.removeprefix('http://')}")
                self.status.setStyleSheet(f"color:{GREEN};")
                self.side_status.setText("● LOCAL AGENT  •  ONLINE"); self.side_status.setStyleSheet(f"color:{GREEN};")
            else:
                self.status.setText("● YEREL KÖPRÜ ÇEVRİMDIŞI — run_windows.bat ile başlatın")
                self.status.setStyleSheet(f"color:{RED};")
                self.side_status.setText("● LOCAL AGENT  •  OFFLINE"); self.side_status.setStyleSheet(f"color:{RED};")
                self._set_card("windows", "● KÖPRÜ KAPALI", RED)
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
            self.config_status.setText("Durum: " + "   •   ".join(parts))
            # Genel Bakış kartları
            if data.get("anthropic_key"):
                self._set_card("claude", "● HAZIR", GREEN)
            else:
                self._set_card("claude", "● API ANAHTARI GEREKLİ  (⚙ Ayarlar)", AMBER)
            voice_on = bool(self.voice_worker and self.voice_worker.isRunning())
            if voice_on:
                self._set_card("gemini", "● GÖRÜŞME AKTİF", PURPLE)
            elif data.get("gemini_key"):
                self._set_card("gemini", "● HAZIR", GREEN)
            else:
                self._set_card("gemini", "● API ANAHTARI GEREKLİ  (⚙ Ayarlar)", AMBER)
            self._set_card("windows", f"● ÇEVRİMİÇİ  •  {len(found)}/{len(apps)} uygulama bulundu",
                           GREEN if found else AMBER)
            self._set_card("android", "● PROTOTİP  •  eşleştirme henüz yok", MUTED)
            ready = [n for n, k in (("Claude", "anthropic_key"), ("Gemini", "gemini_key")) if data.get(k)]
            self.core_status.setText(("Hazır: " + " • ".join(ready + ["Windows ajanı"])) if ready
                                     else "Başlamak için ⚙ Ayarlar'dan Claude API anahtarını girin.")
        self.run_bg(lambda: self._get("/status"), done)

    def closeEvent(self, event):
        if self.chat_worker and self.chat_worker.isRunning():
            self.chat_worker.stop(); self.chat_worker.wait(5000)
        if self.overlay:
            self.overlay.close()
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
