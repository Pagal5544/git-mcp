import unittest
from pathlib import Path


class RepositorySmokeTests(unittest.TestCase):
    def test_repository_root_exists(self):
        root = Path(__file__).resolve().parents[1]
        self.assertTrue(root.is_dir())

    def test_readme_exists(self):
        root = Path(__file__).resolve().parents[1]
        self.assertTrue((root / "README.md").is_file())


if __name__ == "__main__":
    unittest.main()
