"""Apply the Fold5-validated framegen correction to the pinned low-RAM source."""
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(sys.argv[1]).resolve()
base = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
if base != "efe6e56711a8d4bfe56b041ec5ac2060c127b531":
    raise SystemExit("Unexpected low-RAM source revision")

p = root / "games/totk/android/gfx/framegen_math.h"
s = p.read_text()
old = """        fg_math_mul(midpoint_projection, relative, projected);
        fg_math_mul(projected, inverse, transform[side]);"""
new = """        int same_projection = 1;
        for (int i = 0; i < 16; i++)
            if (first[28+i] != second[28+i])
                same_projection = 0;

        if (same_projection) {
            /* Preserve exact identity components by
               projecting only the relative change. */
            for (int i = 0; i < 16; i += 5)
                relative[i] -= 1.0;

            fg_math_mul(midpoint_projection, relative, projected);
            fg_math_mul(projected, inverse, transform[side]);

            for (int i = 0; i < 16; i += 5)
                transform[side][i] += 1.0;
        } else {
            fg_math_mul(midpoint_projection, relative, projected);
            fg_math_mul(projected, inverse, transform[side]);
        }"""
if s.count(old) != 1:
    raise SystemExit("Patch refused: source mismatch")
p.write_text(s.replace(old, new, 1))

directional = r'''
#include "framegen_test_camera.h"
#include <stdio.h>
int main(void) {
    const double directions[][2] = {
        {0,0}, {0.16,0}, {0,0.16}, {0.16,0.16},
        {-0.16,0}, {0,-0.16}, {-0.16,-0.16}
    };
    unsigned failures = 0;
    for (unsigned i=0; i<7; i++) {
        float a[84], b[84], t[2][16];
        fg_test_camera(a,0,0,0,0);
        fg_test_camera(b,0,directions[i][0],directions[i][1],0);
        if (fg_math_interpolate(a,b,FG_TEST_W,FG_TEST_H,t[0],t[1])
            != FG_MATH_OK) {
            failures++;
            continue;
        }
        for (int s=0; s<2; s++) {
            float d=fg_test_depth(2);
            fg_motion_bound m=fg_math_motion_bound(t[s],d,d,FG_TEST_W,FG_TEST_H);
            int valid=(directions[i][0]==0 ? m.radius[0]==1 : m.radius[0]>1)
                   && (directions[i][1]==0 ? m.radius[1]==1 : m.radius[1]>1);
            printf("direction=%u side=%d radius=%u,%u %s\n",
                   i,s,m.radius[0],m.radius[1],valid?"PASS":"FAIL");
            failures += !valid;
        }
    }
    printf("Directional failures: %u\n",failures);
    return failures!=0;
}
'''
with tempfile.TemporaryDirectory(prefix="framegen-v2-") as temp:
    exe = Path(temp) / "test"
    probe = Path(temp) / "direction.c"
    probe.write_text(directional)
    flags = ["clang", "-std=c11", "-Wall", "-Wextra", "-Werror"]
    for opts in (["-O0"], ["-O1"], ["-O2"], ["-O3"], ["-Os"],
                 ["-O2", "-ffp-contract=off"]):
        print("Framegen goldens:", *opts, flush=True)
        subprocess.run([*flags, *opts, str(root / "tests/test_framegen_math.c"),
                        "-lm", "-o", str(exe)], check=True)
        subprocess.run([str(exe)], check=True)
    subprocess.run([*flags, "-O2", "-I" + str(root / "tests"), str(probe),
                    "-lm", "-o", str(exe)], check=True)
    subprocess.run([str(exe)], check=True)
subprocess.run(["git", "-C", str(root), "diff", "--check"], check=True)
print("Framegen V2 candidate and regression gate passed.")
