"""Opt-in Fold 5 draw-work controls. Focused contract tests, not on-device FPS proof."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'games/totk/android/java/com/aure/totk/setup/Settings.java'
PROBE=ROOT/'games/totk/tests/settings/SettingsProbe.java'
MENU=ROOT/'games/totk/android/java/com/aure/totk/setup/SetupActivity.java'
STRINGS=ROOT/'games/totk/android/java/com/aure/totk/setup/Strings.java'
FRONTEND=ROOT/'games/totk/runtime/frontend_android.c'
PATCH=ROOT/'games/totk/runtime/game_patches_v143.c'


class Fold5DrawBudgetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (shutil.which('javac') and shutil.which('java')):
            raise unittest.SkipTest('OpenJDK 17+ required')
        cls.temp=tempfile.TemporaryDirectory(prefix='totk-draw-budget-')
        build=subprocess.run(['javac','--release','17','-d',cls.temp.name,str(JAVA),str(PROBE)],capture_output=True,text=True)
        if build.returncode:
            raise AssertionError('javac failed: '+build.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_probe(self,*lines):
        p=subprocess.run(['java','-cp',self.temp.name,'com.aure.totk.setup.SettingsProbe'],
                         input='\n'.join(lines)+'\n',capture_output=True,text=True,timeout=30)
        self.assertEqual(p.returncode,0,p.stderr)
        return p.stdout.splitlines()

    def state(self,line):
        return dict(part.split('=',1) for part in line.split() if '=' in part)

    def env(self,line):
        return [part for part in line.split('|') if part and not part.startswith('#')]

    def test_priority30_preserves_original_shadow_coverage(self):
        state=self.state(self.run_probe('set priority30=on')[0])
        self.assertEqual(state['priority30'],'on')
        self.assertEqual(state['shadow_passes'],'4')
        self.assertEqual(state['far_clamp'],'auto')
        self.assertEqual(state['shadows'],'high')
        self.assertEqual(state['aa'],'fxaa')
        self.assertEqual(state['frame_gen'],'off')
        self.assertEqual(state['frame_rate'],'30')
        lines=self.env(self.run_probe('env en-US priority30=on')[0])
        self.assertFalse(any(x.startswith(('TOTK_SHADOW_CASCADES=', 'TOTK_SHADOW_RES=',
                                                  'TOTK_FAR_CLAMP=', 'TOTK_FXAA=')) for x in lines))
        self.assertIn('TOTK_FRAME_RATE_HINT=60',lines)

    def test_shipping_default_and_preset_leave_cascades_unchanged(self):
        state=self.state(self.run_probe('stored')[0])
        self.assertEqual((state['shadow_passes'],state['far_clamp'],state['priority30']),('4','auto','off'))
        lines=self.env(self.run_probe('env en-US')[0])
        self.assertFalse(any(x.startswith('TOTK_SHADOW_CASCADES=') or x.startswith('TOTK_FAR_CLAMP=') for x in lines))
        state=self.state(self.run_probe('apply balanced priority30=on')[0])
        self.assertEqual((state['priority30'],state['shadow_passes']),('off','4'))
        state=self.state(self.run_probe('defaults performance SM8550 priority30=on')[0])
        self.assertEqual((state['priority30'],state['shadow_passes']),('off','4'))
        state=self.state(self.run_probe('defaults picture SM8550 priority30=on')[0])
        self.assertEqual((state['priority30'],state['shadow_passes']),('off','4'))

    def test_cascade_levels_and_manual_override(self):
        state=self.state(self.run_probe('set priority30=on shadow_passes=3')[0])
        self.assertEqual((state['priority30'],state['shadow_passes']),('off','3'))
        lines=self.env(self.run_probe('env en-US shadow_passes=3')[0])
        self.assertIn('TOTK_SHADOW_CASCADES=3',lines)
        self.assertNotIn('TOTK_SHADOW_CASCADES=2',lines)
        invalid=self.state(self.run_probe('stored shadow_passes=1 far_clamp=10')[0])
        self.assertEqual((invalid['shadow_passes'],invalid['far_clamp']),('4','auto'))

    def test_camera_distance_explicit_opt_in(self):
        for metres in ('1000','500','200'):
            lines=self.env(self.run_probe(f'env en-US far_clamp={metres}')[0])
            self.assertIn('TOTK_FAR_CLAMP='+metres,lines)
        state=self.state(self.run_probe('set priority30=on far_clamp=500')[0])
        self.assertEqual(state['priority30'],'off')  # Explicit camera override exits visual-safe profile.
        self.assertEqual(state['shadow_passes'],'4')
        self.assertEqual(state['far_clamp'],'500')
        # Stored priority30=on normalizes stale camera caps at startup.
        self.assertNotIn('TOTK_FAR_CLAMP=500',self.env(self.run_probe('env en-US priority30=on far_clamp=500')[0]))

    def test_version_guarded_patch_and_no_fence_modifications(self):
        runtime=PATCH.read_text()
        self.assertIn('static void patch_shadow_cascades(Module *m)',runtime)
        self.assertIn('static void patch_far_clamp(Module *m)',runtime)
        self.assertIn('verify_render_sites(m,sites',runtime)
        self.assertIn('patch_shadow_cascades(m)',runtime)
        self.assertIn('patch_far_clamp(m)',runtime)
        frontend=FRONTEND.read_text()
        self.assertRegex(frontend,r'"TOTK_SHADOW_CASCADES",\s*"TOTK_FAR_CLAMP"')
        self.assertIn('"TOTK_FRAME_RATE_HINT"',frontend)

    def test_graphics_picture_rows_fit_without_clipping(self):
        menu=MENU.read_text(); labels=STRINGS.read_text()
        self.assertIn('"shadows", "shadow_passes", "far_clamp"',menu)
        self.assertIn('lp.height = Theme.u(84)',menu)
        self.assertIn('lp.bottomMargin = Theme.u(10)',menu)
        self.assertLessEqual(6*(84+10),720-2*28)
        self.assertIn('case "shadow_passes": return "Directional shadow passes"',labels)
        self.assertIn('case "far_clamp": return "Distant scenery"',labels)


if __name__=='__main__':unittest.main()
