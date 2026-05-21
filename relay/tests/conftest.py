"""
pytest configuration for relay tests.

Adds the relay/ directory to sys.path so that `import relay_server` works
without needing a package install.
"""
import sys
import os

# relay/tests/ → relay/
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
