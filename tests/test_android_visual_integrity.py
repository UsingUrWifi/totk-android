"""Regression contract for the visual-correctness-first Fold 5 30 FPS target.

Uses the repo's real pure Java Settings and SettingsProbe. A passing test
validates launcher env/configuration, not the GPU driver or on-device image.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT/'games/totk/android/java/com/aure/totk/setup/Settings.java'
PROBE = ROOT/'games/totk/tests/settings/SettingsProbe.java'
STRINGS = ROOT/'games/totk/android/java/com/aure/totk/setup/Strings.java'
NATIVE = ROOT/'games/totk/runtime/game_patches_v143.c'
DOC = ROOT/'docs/fold5-visual-integrity.md'


class VisualIntegrityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which('javac') or not shutil.which('java'):
            raise unittest.SkipTest('Java 17+ required for real settings contract test')
        cls.tmp = tempfile.TemporaryDirectory(prefix='totk-visual-integrity-')
        build = subprocess.run(['javac','--release','17','-d',cls.tmp.name,str(SRC),str(PROBE)],
                               capture_output=True, text=True, timeout=60)
        if build.returncode:
            raise AssertionError('Settings.java javac failed: '+build.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def probe(self,*commands):
        result=subprocess.run(['java','-cp',self.tmp.name,'com.aure.totk.setup.SettingsProbe'],
                              input='\n'.join(commands)+'\n',capture_output=True,text=True,timeout=30)
        self.assertEqual(result.returncode,0,result.stderr)
        return result.stdout.strip().splitlines()

    @staticmethod
    def values(line):
        return dict(kv.split('=',1) for kv in line.split() if '=' in kv)

    @staticmethod
    def env(line):
        return [x for x in line.split('|') if x and not x.startswith('#')]

    def test_priority_restores_original_shadow_and_visibility(self):
        values=self.values(self.probe('set priority30=on')[0])
        expected={'priority30':'on','frame_rate':'30','frame_gen':'off','render_res':'960x540',
                  'aa':'fxaa','shadows':'high','shadow_passes':'4','far_clamp':'auto',
                  'refresh_hint':'60','latency':'balanced','upscaler':'off'}
        for key,want in expected.items(): self.assertEqual(values[key],want,key)

    def test_no_hidden_visual_reduction_in_env(self):
        lines=self.env(self.probe('env en-US priority30=on')[0])
        for kept in ('TOTK_FPS60=0','TOTK_RENDER_RES=960x540',
                     'TOTK_FRAME_RATE_HINT=60','TOTK_ACQUIRE_SLACK=1'):
            self.assertIn(kept,lines)
        for forbidden in ('TOTK_SHADOW_CASCADES=','TOTK_SHADOW_RES=',
                          'TOTK_FXAA=0','TOTK_FAR_CLAMP=', 'TOTK_FRAMEGEN=locked'):
            self.assertFalse(any(line.startswith(forbidden) for line in lines), forbidden)

    def test_old_saved_priority_normalizes_visual_settings(self):
        stale='priority30=on shadow_passes=2 shadows=low aa=off far_clamp=500'
        values=self.values(self.probe('stored '+stale)[0])
        self.assertEqual({k:values[k] for k in ('shadow_passes','shadows','aa','far_clamp')},
                         {'shadow_passes':'4','shadows':'high','aa':'fxaa','far_clamp':'auto'})
        self.assertNotIn('TOTK_FAR_CLAMP=500',self.probe('env en-US '+stale)[0])

    def test_manual_unsafe_visual_changes_relinquish_profile(self):
        for key,value in [('shadow_passes','2'),('shadows','low'),
                          ('aa','off'),('far_clamp','500')]:
            state=self.values(self.probe(f'set priority30=on {key}={value}')[0])
            self.assertEqual(state['priority30'],'off',(key,state))
            self.assertEqual(state[key],value)
        # No permanent loss of the manual experiments outside the safe profile.
        raw=self.env(self.probe('env en-US shadow_passes=2 shadows=low aa=off far_clamp=500')[0])
        for key in ('TOTK_SHADOW_CASCADES=2','TOTK_SHADOW_RES=256',
                    'TOTK_FXAA=0','TOTK_FAR_CLAMP=500'):
            self.assertIn(key,raw)

    def test_ui_copy_and_original_native_checks(self):
        description=STRINGS.read_text()
        self.assertIn('original four shadow cascades', description)
        self.assertIn('full original', description)
        self.assertIn('original shadow-map sizing',description)
        src=NATIVE.read_text()
        self.assertIn('static void patch_far_clamp(Module *m)',src)
        self.assertIn('static void patch_shadow_cascades(Module *m)',src)
        self.assertIn('if(!e||!*e||!strcmp(e,"0"))return;',src)
        self.assertTrue(DOC.exists())


if __name__=='__main__': unittest.main()
