"""Touch overlay implementation contracts (host-only, no Android emulator required)."""
from pathlib import Path
import unittest

SOURCE = (Path(__file__).resolve().parents[1] / 'games' / 'totk' / 'android'
          / 'java' / 'com' / 'aure' / 'totk' / 'TouchControlsView.java')

class TouchFeedbackTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SOURCE.read_text(encoding='utf-8')

    def test_switch_like_style_and_original_fallback(self):
        s = self.source
        self.assertIn('STYLE_PRO = "pro"', s)
        self.assertIn('STYLE_OUTLINE = "outline"', s)
        self.assertIn('drawProDpad(canvas)', s)
        self.assertIn('drawOutlineButton(canvas, b)', s)
        self.assertIn('button(A, faceX + diamond, cy, buttonRadius)', s)

    def test_haptics_follow_native_press_edges(self):
        s = self.source
        begin = s.index('private void updateNative()')
        end = s.index('private boolean stickOwned(', begin)
        update = s[begin:end]
        self.assertIn('acceptedButtons & ~currentButtons', update)
        self.assertLess(update.index('send(acceptedButtons'), update.index('pulse(HapticFeedbackConstants.VIRTUAL_KEY)'))
        self.assertIn('pulse(HapticFeedbackConstants.CLOCK_TICK)', update)
        self.assertIn('haptics && isAttachedToWindow()', update)
        self.assertNotIn('VibratorManager', s)  # no collision with game rumble

    def test_multitouch_lifecycle_unchanged(self):
        s = self.source
        self.assertIn('SparseArray<Finger> pointers', s)
        self.assertIn('event.getPointerId(i)', s)
        self.assertIn('event.getPointerId(index)', s)
        self.assertIn('resetInput();', s)
        self.assertIn('pendingStickPulse = false;', s)

    def test_user_choices_persist(self):
        s = self.source
        self.assertIn('prefs.edit().putBoolean(KEY_HAPTICS, haptics).apply()', s)
        self.assertIn('prefs.edit().putString(KEY_STYLE,', s)
        self.assertIn('prefs.getBoolean(KEY_HAPTICS, true)', s)
        self.assertIn('prefs.getString(KEY_STYLE, STYLE_PRO)', s)

if __name__ == '__main__':
    unittest.main()
