"""Computer Use ajanını arayüzü dondurmadan çalıştıran iş parçacığı ve Qt köprüsü."""
from __future__ import annotations

import threading

from PySide6.QtCore import QThread, Signal

from . import history
from .computer_agent import AgentResult, describe_action, run_agent


class AgentWorker(QThread):
    log_line = Signal(str)
    approval_needed = Signal(list)   # açıklama satırları
    question = Signal(str)
    finished_result = Signal(object)  # AgentResult

    def __init__(self, task: str, approval_mode: str, max_turns: int, executor_factory=None, parent=None):
        super().__init__(parent)
        self.task = task
        self.approval_mode = approval_mode
        self.max_turns = max_turns
        self.executor_factory = executor_factory
        self._stop = threading.Event()
        self._answer_ready = threading.Event()
        self._answer = ""
        self._hotkey = None

    # --- GUI'den çağrılır ---
    def stop(self):
        self._stop.set()
        self._answer = "stop"
        self._answer_ready.set()

    def provide_answer(self, answer: str):
        self._answer = answer
        self._answer_ready.set()

    # --- AgentCallbacks ---
    def log(self, text: str) -> None:
        self.log_line.emit(text)

    def should_stop(self) -> bool:
        return self._stop.is_set()

    def _wait_answer(self) -> str:
        while not self._answer_ready.wait(0.2):
            if self._stop.is_set():
                return "stop"
        self._answer_ready.clear()
        return self._answer

    def approve(self, actions):
        self._answer_ready.clear()
        self.approval_needed.emit([describe_action(n, a) for n, a in actions])
        answer = self._wait_answer()
        return answer if answer in ("yes", "no", "stop") else "no"

    def ask_user(self, question: str) -> str:
        self._answer_ready.clear()
        self.question.emit(question)
        answer = self._wait_answer()
        return "Kullanıcı görevi durdurdu." if answer == "stop" else answer

    # --- iş parçacığı ---
    def _start_hotkey(self):
        try:
            from pynput import keyboard
            self._hotkey = keyboard.GlobalHotKeys({"<ctrl>+<alt>+<f12>": self.stop})
            self._hotkey.start()
        except Exception:
            self._hotkey = None

    def run(self):
        self._start_hotkey()
        try:
            if self.executor_factory:
                executor = self.executor_factory()
            else:
                from .executor import LocalExecutor
                executor = LocalExecutor()
            self.log(f"Görev: {self.task}")
            result = run_agent(self.task, executor, self, self.approval_mode, self.max_turns)
        except Exception as exc:
            result = AgentResult("error", f"{type(exc).__name__}: {exc}", 0, 0)
        finally:
            if self._hotkey:
                try:
                    self._hotkey.stop()
                except Exception:
                    pass
        status = {"done": "tamam", "stopped": "durduruldu", "refused": "reddedildi",
                  "limit": "sınır", "error": "hata"}.get(result.status, result.status)
        history.log("bilgisayar", self.task, f"{result.summary}\n\nAdım: {result.turns}, eylem: {result.actions}", status)
        self.finished_result.emit(result)
