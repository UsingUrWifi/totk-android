import unittest
import importlib.util
from pathlib import Path

PATH=Path(__file__).resolve().parents[1]/'tools/audit_totk_30fps.py'
SPEC=importlib.util.spec_from_file_location('totk_audit_30',PATH)
MOD=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)

class Audit30Tests(unittest.TestCase):
    def fixture(self,fps='29.99',slow='24.0',hitch=1):
        return (MOD.HEADER+'\n'+
                f'1793898841755,0.01,125,1,{fps},{fps},60.0,191.1,0,0,96\n'+
                f'1793898843755,0.01,133,1,{slow},{slow},60.0,185.2,0,0,96\n\n'+
                ('[nvn-vk] hitches, last 300 frames: 40-60 ms 1, 60-100 0, 100-200 0, 200-400 0, >=400 0\n' if hitch else ''))
    def test_rejects_sub30_and_reports_stutters(self):
        s=MOD.report(self.fixture())
        self.assertIn('below 25 FPS 1/2',s)
        self.assertIn('NOT YET ACCEPTED',s)
        self.assertIn('event counts: 1',s)
    def test_never_promises_stability_from_short_samples(self):
        s=MOD.report(self.fixture('29.99','29.99',0))
        self.assertIn('PROMISING SAMPLE ONLY',s)
    def test_empty(self):
        self.assertIn('NO ACTIVE',MOD.report(MOD.HEADER+'\n'))
    def test_missing_header(self):
        with self.assertRaises(ValueError): MOD.report('foo')
if __name__=='__main__': unittest.main()
