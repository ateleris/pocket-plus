"""pytest configuration: make the `pocketplus` package importable from the python/ root."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
