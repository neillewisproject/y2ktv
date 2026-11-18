"""The release build keeps HomePressSensor's onCloseSystemDialogs.

On Fire OS the Home key never reaches an accessibility service, so HomeKeyService hears it through a 1x1
overlay whose View gets the system's hidden onCloseSystemDialogs("homekey") callback. Nothing in the app calls
that method, so R8 dropped it from the release APK: the sensor window was attached but deaf, and since a sensor
was attached the service never counted presses another way, so three quick Home presses stopped switching
between this launcher and the stock one. proguard-rules.pro has to keep it.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "launcher" / "app"


class HomePressSensorKept(unittest.TestCase):
    def test_the_sensor_still_defines_the_callback(self):
        source = (APP / "src/main/java/com/gathin/tvhome/HomePressSensor.java").read_text(encoding="utf-8")
        self.assertIn("public void onCloseSystemDialogs(String reason)", source)

    def test_r8_keeps_the_callback(self):
        rules = (APP / "proguard-rules.pro").read_text(encoding="utf-8")
        keep = re.search(r"-keep\w*\s+class\s+com\.gathin\.tvhome\.HomePressSensor\s*\{([^}]*)\}", rules)
        self.assertIsNotNone(keep, "proguard-rules.pro has no keep rule for HomePressSensor")
        self.assertRegex(keep.group(1), r"onCloseSystemDialogs\(java\.lang\.String\)")


if __name__ == "__main__":
    unittest.main()
