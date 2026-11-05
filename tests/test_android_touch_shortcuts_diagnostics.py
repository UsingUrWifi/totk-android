"""Touch shortcut logic and native telemetry contracts. No Android SDK required."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'games/totk/android/java/com/aure/totk'
VK = ROOT / 'games/totk/android/gfx'
JNI = ROOT / 'games/totk/runtime/companion_jni.c'


class TouchShortcutDiagnosticTests(unittest.TestCase):
    def test_bindings_real_java_state_machine(self):
        javac, java = shutil.which('javac'), shutil.which('java')
        if not javac or not java:
            self.skipTest('Host javac/java unavailable; run on desktop CI')
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run([javac, '--release', '17', '-d', tmp,
                            str(JAVA / 'TouchBindings.java'),
                            str(ROOT / 'tests/touch_shortcuts/TouchBindingsProbe.java')],
                           check=True, capture_output=True, text=True, timeout=30)
            run = subprocess.run([java, '-cp', tmp, 'com.aure.totk.TouchBindingsProbe'],
                                 check=True, capture_output=True, text=True, timeout=30)
            self.assertIn('PASS:', run.stdout)

    def test_view_routes_multitouch_and_reset(self):
        src = (JAVA / 'TouchControlsView.java').read_text()
        self.assertIn('MODE_MACRO = 4', src)
        self.assertIn('SparseArray<Finger> pointers', src)
        self.assertIn('getPointerId(i)', src)
        self.assertIn('getPointerId(index)', src)
        self.assertIn('bindings.apply(bits, macroHeld)', src)
        self.assertIn('bindings.reset()', src)
        self.assertIn('bindings.toggledOnThisPress()', src)
        self.assertIn('if (showMenu) showSettings();', src)
        self.assertIn('prefs.edit().putBoolean(KEY_ZL_TOGGLE', src)
        self.assertIn('prefs.edit().putBoolean(KEY_COMBO_ENABLED', src)
        self.assertIn('prefs.edit().putInt(KEY_COMBO_FIRST', src)
        self.assertIn('prefs.edit().putBoolean(KEY_DIAGNOSTICS', src)

    def test_report_native_handoffs_and_idle_context(self):
        java = (JAVA / 'TouchPerfDiagnostics.java').read_text()
        view = (JAVA / 'TouchControlsView.java').read_text()
        jni = JNI.read_text()
        c = (VK / 'vk_overlay.c').read_text()
        self.assertIn('nativePerfTotals()', java)
        self.assertIn('nativePerfTotals', jni)
        self.assertIn('real_handoff_count', c)
        self.assertIn('if (!generated) atomic_fetch_add_explicit(&real_handoff_count', c)
        self.assertIn('vk_overlay_handoff_totals', c)
        self.assertIn('nativeOverlayCycle()', view)
        self.assertIn('nativeOverlayMode()', view)
        self.assertIn('getElapsedCpuTime()', java)
        self.assertIn('getCurrentThermalStatus()', java)
        self.assertIn('getRefreshRate()', java)
        self.assertIn('seconds_since_touch', java)
        self.assertIn('pointer_held', java)
        self.assertIn('Intent.ACTION_SEND', java)
        self.assertIn('handler.postDelayed(ticker, 2000L)', java)
        self.assertIn('diagnostics.onDetached()', view)
        self.assertNotIn('PowerManager.WakeLock', java)


if __name__ == '__main__':
    unittest.main()
