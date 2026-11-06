#!/usr/bin/env python3
"""Strict real-30 target gate for exported PAD performance reports.

Two-second FPS samples cannot prove no frame-time spikes. Includes native
300-frame hitch summaries if the report has opt-in trace enabled.
"""
import csv
import re
import statistics
import sys
from pathlib import Path

HEADER = 'epoch_ms,seconds_since_touch,touches,pointer_held,game_handoffs_fps,real_handoffs_fps,display_mode_hz,process_cpu_pct,thermal_status,power_saver,battery_pct'
HITCH = re.compile(r'hitches, last 300 frames: 40-60 ms (\d+), 60-100 (\d+), 100-200 (\d+), 200-400 (\d+), >=400 (\d+)')


def analyze(content):
    lines=content.splitlines()
    try: h=lines.index(HEADER)
    except ValueError: raise ValueError('This does not contain a PAD diagnostics CSV header')
    samples=[]
    for line in lines[h+1:]:
        if not line.strip() or line.startswith('-- '): break
        columns=next(csv.reader([line]))
        if len(columns)!=11: continue
        try:
            fps=float(columns[5]); mode=float(columns[6]); cpu=float(columns[7]); held=int(columns[3]); thermal=int(columns[8])
            if 0<=fps<500 and 0<=mode<=500 and 0<=cpu<10000:
                samples.append((fps,mode,cpu,held,thermal))
        except ValueError: continue
    active=[s for s in samples if s[3]]
    hitches=[sum(map(int,m.groups())) for line in lines if (m:=HITCH.search(line))]
    return samples,active,hitches


def report(content):
    samples,active,hitches=analyze(content)
    if not active: return 'NO ACTIVE GAMEPLAY SAMPLES: record sustained input before auditing.\n'
    fps=[s[0] for s in active]
    hz=[s[1] for s in samples]
    slow29=sum(x<29 for x in fps); slow25=sum(x<25 for x in fps)
    n=len(fps)
    out=[f'Real 30 FPS target (no frame generation): held samples {n}; median {statistics.median(fps):.2f}; min {min(fps):.2f}; below 29 FPS {slow29}/{n}; below 25 FPS {slow25}/{n}',
         f'Display modes {sorted(set(hz))}; max reported thermal status {max(s[4] for s in samples)}',
         'Native 300-frame >40ms event counts: '+(', '.join(map(str,hitches)) if hitches else 'unavailable (enable trace for a capture)')]
    if slow29 or any(x>0 for x in hitches):
        out.append('NOT YET ACCEPTED: at least one captured interval is below the strict 30 FPS objective.')
    else:
        out.append('PROMISING SAMPLE ONLY: two-second counters do not prove a locked per-frame 30 FPS or all-scene stability.')
    return '\n'.join(out)+'\n'


def main():
    if len(sys.argv)!=2:
        raise SystemExit('Usage: python3 tools/audit_totk_30fps.py <exported-performance-report.txt>')
    print(report(Path(sys.argv[1]).read_text(errors='replace')),end='')

if __name__=='__main__': main()
