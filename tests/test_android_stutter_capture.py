"""Regression gates for the Fold 5 opt-in, bounded stutter diagnostic integration.

Host static tests always run. The extracted C log-scanner executes on hosts
with a working C compiler; no Android SDK or game assets are required.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'games/totk/android/java/com/aure/totk'
RUNTIME = ROOT / 'games/totk/runtime'
DRAW = ROOT / 'games/totk/android/gfx/vk_draw.c'


def source(path):
    return path.read_text(encoding='utf-8')


class Fold5StutterTests(unittest.TestCase):
    def test_capture_is_explicit_and_off_by_default(self):
        settings = source(APP / 'setup/Settings.java')
        self.assertIn('new Option("stutter_trace", CONTROLS, "off", "off", "on")', settings)
        self.assertIn('if (get("stutter_trace").equals("on")) line(out, "TOTK_VK_HITCH=1");', settings)
        frontend = source(RUNTIME / 'frontend_android.c')
        self.assertIn('"TOTK_VK_HITCH"', frontend)
        self.assertIn('const char *e = getenv("TOTK_VK_HITCH")', source(DRAW))
        self.assertIn('pthread_once(&g_stutter_once, stutter_configure)', source(DRAW))

    def test_refresh_and_shader_cache_paths_preserved(self):
        settings = source(APP / 'setup/Settings.java')
        self.assertIn('new Option("refresh_hint", SCREEN, "60", "auto", "60")', settings)
        self.assertIn('if (get("refresh_hint").equals("60")) line(out, "TOTK_FRAME_RATE_HINT=60");', settings)
        draw = source(DRAW)
        self.assertIn('vk_pipeline_keys_pressure(', draw)
        self.assertIn('vk_pcache_present(', draw)
        self.assertIn('hitch_present(now, n)', draw)
        self.assertIn('g_hitch_on && now - g_last_present_ns >= 50000000ull', draw)
        self.assertIn('v.pipe_ns / 1e6', draw)

    def test_local_log_share_is_bounded_and_opt_in(self):
        frontend = source(RUNTIME / 'frontend_android.c')
        jni = source(RUNTIME / 'companion_jni.c')
        diag = source(APP / 'TouchPerfDiagnostics.java')
        self.assertIn('totk_stutter_excerpt(f, excerpt, sizeof excerpt)', frontend)
        self.assertIn('"nativeStutterExcerpt", "()Ljava/lang/String;"', jni)
        self.assertIn('TotkActivity.nativeStutterExcerpt()', diag)
        self.assertIn('TOTK_STUTTER_SCAN_BYTES = 256 * 1024', source(RUNTIME / 'stutter_excerpt.h'))
        self.assertIn('TOTK_STUTTER_ROWS = 48', source(RUNTIME / 'stutter_excerpt.h'))
        self.assertIn('if (!totk_stutter_relevant(line)) continue;', source(RUNTIME / 'stutter_excerpt.h'))

    def test_native_excerpt_probe(self):
        compiler = shutil.which('cc') or shutil.which('clang')
        if not compiler:
            self.skipTest('No C compiler in this environment; source checks still pass')
        with tempfile.TemporaryDirectory(prefix='totk-stutter-') as d:
            exe = Path(d) / 'probe'
            probe = ROOT / 'tests/stutter_excerpt_probe.c'
            compile_cmd = [compiler, '-std=c11', '-Wall', '-Wextra', '-Werror', str(probe), '-o', str(exe)]
            done = subprocess.run(compile_cmd, capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, done.stderr)
            run = subprocess.run([str(exe)], capture_output=True, text=True, timeout=15)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            self.assertIn('PASS', run.stdout)


if __name__ == '__main__':
    unittest.main()
