"""FK JARVIS giriş noktası (çift tıklayınca konsolsuz açılır; PyInstaller da bunu kullanır).

Doğrudan içe aktarma kullanılır: PyInstaller bağımlılıkları buradan izler (runpy ile dize olarak verilen
modülleri göremezdi).
"""
import sys

from app.main import cli

if __name__ == "__main__":
    sys.exit(cli())
