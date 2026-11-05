#include "../games/totk/runtime/stutter_excerpt.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    FILE *f = tmpfile(); assert(f);
    fputs("private game data and personal paths must stay excluded\n", f);
    fputs("hitch at frame 3: +71.0 ms (median 33.3)\n", f);
    fputs("[pcache-stats] skipped, deferred due to gameplay\n", f);
    fputs("long frame 4: graphics 4.3ms, shader 3.0ms, GPU wait 6.0ms\n", f);
    char out[20000];
    size_t size = totk_stutter_excerpt(f, out, sizeof out);
    assert(size > 0 && size < sizeof out);
    assert(strstr(out, "hitch at frame 3") != NULL);
    assert(strstr(out, "long frame 4") != NULL);
    assert(strstr(out, "[pcache-stats]") != NULL);
    assert(strstr(out, "private game data") == NULL);
    rewind(f);
    for (unsigned i = 0; i < 90; i++) fprintf(f, "hitch at frame %u: +56.0 ms\n", 100 + i);
    size = totk_stutter_excerpt(f, out, sizeof out);
    assert(size && strstr(out, "hitch at frame 100:") == NULL);
    assert(strstr(out, "hitch at frame 189:") != NULL);
    char tiny[35];
    size = totk_stutter_excerpt(f, tiny, sizeof tiny);
    assert(size < sizeof tiny && tiny[size] == 0);
    fclose(f);
    FILE *empty = tmpfile(); assert(empty);
    size = totk_stutter_excerpt(empty, out, sizeof out);
    assert(size && strstr(out, "No selected native stutter events"));
    fclose(empty);
    puts("stutter excerpt C probe: PASS");
    return 0;
}
