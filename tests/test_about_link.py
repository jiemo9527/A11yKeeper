"""Verify the settings About entry is wired to the public project URL."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/io/github/jiemo9527/a11ykeeper/MainActivity.java").read_text(encoding="utf-8")


class AboutLinkTest(unittest.TestCase):
    def test_about_entry_opens_project_url(self):
        self.assertIn('addSection(page, "关于"', SOURCE)
        self.assertIn('https://github.com/jiemo9527/A11yKeeper', SOURCE)
        self.assertIn('Intent.ACTION_VIEW', SOURCE)
        self.assertIn('startActivity(intent)', SOURCE)
        self.assertIn('ActivityNotFoundException', SOURCE)


if __name__ == "__main__":
    unittest.main()
