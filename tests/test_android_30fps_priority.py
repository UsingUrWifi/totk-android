"""Fold5 real-30 opt-in profile and menu integration (no Android device required)."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'games/totk/android/java/com/aure/totk/setup/Settings.java'
PROBE = ROOT / 'games/totk/tests/settings/SettingsProbe.java'
MENU = ROOT / 'games/totk/android/java/com/aure/totk/setup/SetupActivity.java'
STRINGS = ROOT / 'games/totk/android/java/com/aure/totk/setup/Strings.java'
WORKFLOW = ROOT / '.github/workflows/build-totk-touch.yml'


class Priority30Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which('java') or not shutil.which('javac') or not PROBE.exists():
            raise unittest.SkipTest('Java 17+ and the repository SettingsProbe required')
        cls.out = tempfile.TemporaryDirectory(prefix='totk-priority30-')
        cp = subprocess.run(['javac', '--release', '17', '-d', cls.out.name, str(SRC), str(PROBE)],
                            capture_output=True, text=True)
        if cp.returncode:
            raise AssertionError('Settings.java javac failed:\n'+cp.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.out.cleanup()

    def probe(self, *lines):
        cp = subprocess.run(['java','-cp',self.out.name,'com.aure.totk.setup.SettingsProbe'],
                            input='\n'.join(lines)+'\n',text=True,capture_output=True,timeout=30)
        self.assertEqual(cp.returncode,0,cp.stderr)
        return cp.stdout.strip().splitlines()

    def mapping(self, line):
        return dict(pair.split('=',1) for pair in line.split() if '=' in pair)

    def test_priority_targets_real30_with_visual_integrity(self):
        d=self.mapping(self.probe('set priority30=on')[0])
        for key,want in {'priority30':'on','frame_rate':'30','frame_gen':'off',
                         'render_res':'960x540','latency':'balanced','upscaler':'off',
                         'aa':'fxaa','shadows':'high','shadow_passes':'4',
                         'far_clamp':'auto','refresh_hint':'60',
                         'stutter_trace':'off','pcache_stats':'off'}.items():
            self.assertEqual(d[key],want,key)
        self.assertEqual(d['perf_hints'],'legacy')
        env=self.probe('env en-US priority30=on')[0]
        for value in ['TOTK_FPS60=0','TOTK_FRAME_RATE_HINT=60','TOTK_RENDER_RES=960x540',
                      'TOTK_POST_SCALE=off','TOTK_ACQUIRE_SLACK=1']:
            self.assertIn(value+'|',env)
        self.assertNotIn('TOTK_FRAMEGEN=locked',env)
        self.assertNotIn('TOTK_ADPF=full',env)
        for forbidden in ('TOTK_SHADOW_RES=', 'TOTK_SHADOW_CASCADES=', 'TOTK_FXAA=0', 'TOTK_FAR_CLAMP='):
            self.assertNotIn(forbidden, env)

    def test_optin_is_not_an_unannounced_device_default(self):
        state=self.mapping(self.probe('stored', 'first SM8550')[0])
        self.assertEqual(state['priority30'],'off')
        self.assertNotIn('TOTK_RENDER_RES=960x540|', self.probe('env en-US')[0])

    def test_manual_520_and_display_changes_relinquish_optin(self):
        for command,want in [('set priority30=on render_res=1152x648','1152x648'),
                             ('set priority30=on frame_rate=60','960x540'),
                             ('set priority30=on refresh_hint=auto','960x540')]:
            state=self.mapping(self.probe(command)[0]); self.assertEqual(state['priority30'],'off')
            self.assertEqual(state['render_res'],want)
        state=self.mapping(self.probe('set priority30=on stutter_trace=on')[0])
        self.assertEqual(state['priority30'],'on')
        state=self.mapping(self.probe('set priority30=on perf_hints=full')[0])
        self.assertEqual((state['priority30'],state['perf_hints']),('on','full'))

    def test_persistence_presets_and_toggleoff(self):
        state=self.mapping(self.probe('stored priority30=on frame_gen=on render_res=1280x720')[0])
        self.assertEqual((state['priority30'],state['frame_gen'],state['render_res']),('on','off','960x540'))
        state=self.mapping(self.probe('set priority30=on priority30=off')[0])
        self.assertEqual((state['priority30'],state['render_res']),('off','960x540'))
        state=self.mapping(self.probe('apply balanced priority30=on')[0])
        self.assertEqual((state['priority30'],state['render_res']),('off','game'))
        state=self.mapping(self.probe('defaults performance SM8550 priority30=on')[0])
        self.assertEqual(state['priority30'],'off')
        state=self.mapping(self.probe('defaults picture SM8550 priority30=on')[0])
        self.assertEqual(state['priority30'],'off')

    def test_ui_does_not_reintroduce_cutoff(self):
        src=MENU.read_text(); strings=STRINGS.read_text(); wf=WORKFLOW.read_text()
        self.assertIn('"perf_hints", "priority30"',src)
        self.assertIn('ScrollView scroll = new ScrollView(this)',src)
        self.assertIn('lp.height = Theme.u(70)',src)
        self.assertIn('lp.bottomMargin = Theme.u(8)',src)
        self.assertIn('case "priority30": return "30 FPS priority"',strings)
        self.assertIn('setup/SetupActivity.java',wf)
        self.assertIn('setup/Settings.java',wf)

if __name__=='__main__': unittest.main()
