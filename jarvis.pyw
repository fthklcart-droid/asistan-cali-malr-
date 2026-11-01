"""FK JARVIS giriş noktası (çift tıklayınca konsolsuz açılır; PyInstaller da bunu kullanır)."""
import runpy

runpy.run_module("app.main", run_name="__main__")
