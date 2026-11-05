"""Focused Fold 5 refresh/pipeline-diagnostics source regression tests.

Run from source checkout: python3 -m unittest discover -s tests -p 'test_android_fold5_frame_fix.py' -v
This does not replace a real Android compile and in-game A/B measurement.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'games/totk/android/java/com/aure/totk'
RUNTIME = ROOT / 'games/totk/runtime'
SETTINGS = JAVA / 'setup/Settings.java'
PROBE = ROOT / 'games/totk/tests/settings/SettingsProbe.java'


class Fold5RefreshTests(unittest.TestCase):
    def test_android_runtime_requests_game_compatible_60_not_fixed_video(self):
        code = (RUNTIME / 'android_perf.c').read_text()
        self.assertIn('prefer60 = rate && !strcmp(rate, "60");', code)
        self.assertIn('prefer60 ? 60 : totk_frame_rate', code)
        self.assertIn('ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT', code)
        self.assertNotIn('ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE', code)
        self.assertIn('rate_on = prefer60 || is_one("TOTK_FRAME_RATE_HINT")', code)
        self.assertIn('totk_perf_window(w)', (ROOT / 'games/totk/android/gfx/vk_draw.c').read_text())

    def test_window_fallback_avoids_display_mode_lock(self):
        java = (JAVA / 'TotkActivity.java').read_text()
        self.assertIn('params.preferredRefreshRate = preferred;', java)
        self.assertIn('getSharedPreferences("settings", MODE_PRIVATE)', java)
        self.assertIn('applyRefreshPreference();', java)
        self.assertNotIn('preferredDisplayModeId', java)
        self.assertNotIn('getSupportedModes()', java)

    def test_shader_pipeline_cache_and_prewarmer_are_preserved(self):
        cache = (ROOT / 'games/totk/android/gfx/vk_pcache.c').read_text()
        self.assertIn('static void *saver(void *unused)', cache)
        self.assertIn('pcache_snapshot(&store, cache_path, query, NULL, &size)', cache)
        self.assertIn('TOTK_PCACHE_STATS', cache)
        backend = (ROOT / 'games/totk/android/gfx/vk_draw.c').read_text()
        self.assertIn('TOTK_PSO_PREWARM', (RUNTIME / 'frontend_android.c').read_text())
        self.assertIn('long frame %llu:', backend)

    def test_app_settings_display_opt_ins(self):
        text = (JAVA / 'setup/SetupActivity.java').read_text()
        for item in ('"refresh_hint"', '"perf_hints"', '"pcache_stats"'):
            self.assertIn(item, text)
        strings = (JAVA / 'setup/Strings.java').read_text()
        for key in ('refresh_hint', 'perf_hints', 'pcache_stats'):
            self.assertIn('case "' + key + '"', strings)

    def test_settings_env_can_enable_and_disable_independently(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java 17+ not installed on this host')
        with tempfile.TemporaryDirectory(prefix='totk-fold5-') as tmp:
            build = subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-d', tmp,
                                    str(SETTINGS), str(PROBE)], capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stderr)
            proc = subprocess.run(['java', '-cp', tmp, 'com.aure.totk.setup.SettingsProbe'],
                                  input='env -\nenv - refresh_hint=auto\nenv - perf_hints=full pcache_stats=on refresh_hint=auto\nenv - frame_gen=on\n',
                                  capture_output=True, text=True, check=True)
            dflt, auto, full, fg = proc.stdout.strip().splitlines()
            self.assertIn('TOTK_FRAME_RATE_HINT=60|', dflt)
            self.assertNotIn('TOTK_FRAME_RATE_HINT', auto)
            self.assertNotIn('TOTK_FRAME_RATE_HINT', full)
            self.assertIn('TOTK_ADPF=full|', full)
            self.assertIn('TOTK_PCACHE_STATS=1|', full)
            self.assertNotIn('TOTK_ADPF=', auto)
            self.assertNotIn('TOTK_PCACHE_STATS=', auto)
            self.assertIn('TOTK_FRAME_RATE_HINT=60|', fg)


if __name__ == '__main__':
    unittest.main()
