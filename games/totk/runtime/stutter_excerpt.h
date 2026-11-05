/* Bounded, read-only selection of local TotK runtime diagnostics. No game data,
 * shaders, save contents or arbitrary log messages are included. Host-testable C. */
#ifndef TOTK_STUTTER_EXCERPT_H
#define TOTK_STUTTER_EXCERPT_H
#include <stdio.h>
#include <stddef.h>
#include <string.h>

enum { TOTK_STUTTER_SCAN_BYTES = 256 * 1024,
       TOTK_STUTTER_ROWS = 48, TOTK_STUTTER_LINE = 384 };

static inline int totk_stutter_relevant(const char *s) {
    return strstr(s, "hitch at frame ") != NULL ||
           strstr(s, "hitches, last ") != NULL ||
           strstr(s, "long frame ") != NULL ||
           strstr(s, "[pcache-stats]") != NULL ||
           strstr(s, "pipeline failure") != NULL ||
           strstr(s, "pipeline key ") != NULL ||
           strstr(s, "[totk-perf]") != NULL;
}

/* Operates only on an already-opened *local* totk.log, invoked solely by the
 * player's explicit Share action. Reads at most the end 256 KiB and returns
 * the last 48 matching lines, each at most 383 printable ASCII characters.
 * 0 on missing input; otherwise bytes written excluding final NUL. */
static inline size_t totk_stutter_excerpt(FILE *f, char *out, size_t cap) {
    if (!out || cap < 2) return 0;
    out[0] = 0;
    if (!f) return 0;
    if (!fseek(f, 0, SEEK_END)) {
        long end = ftell(f);
        if (end > (long)TOTK_STUTTER_SCAN_BYTES &&
                !fseek(f, end - (long)TOTK_STUTTER_SCAN_BYTES, SEEK_SET)) {
            char discard[4096];
            (void)fgets(discard, sizeof discard, f); /* partial first line */
        } else rewind(f);
    } else rewind(f);

    char records[TOTK_STUTTER_ROWS][TOTK_STUTTER_LINE] = {{0}};
    unsigned count = 0;
    char line[4096];
    while (fgets(line, sizeof line, f)) {
        if (!totk_stutter_relevant(line)) continue;
        char *dst = records[count % TOTK_STUTTER_ROWS];
        size_t n = 0;
        for (; n + 1 < TOTK_STUTTER_LINE && line[n] &&
                line[n] != '\n' && line[n] != '\r'; n++) {
            unsigned char c = (unsigned char)line[n];
            dst[n] = c >= 32 && c <= 126 ? (char)c : '?';
        }
        dst[n] = '\0';
        count++;
    }
    if (!count) {
        const char *message = "No selected native stutter events in the most recent 256 KiB of totk.log.\n";
        size_t n = strlen(message);
        if (n >= cap) n = cap - 1;
        memcpy(out, message, n);
        out[n] = 0;
        return n;
    }
    unsigned keep = count < TOTK_STUTTER_ROWS ? count : TOTK_STUTTER_ROWS;
    unsigned start = count - keep;
    size_t used = 0;
    for (unsigned i = start; i < count; i++) {
        const char *entry = records[i % TOTK_STUTTER_ROWS];
        size_t n = strlen(entry);
        if (used + n + 2 > cap) break;
        memcpy(out + used, entry, n);
        used += n;
        out[used++] = '\n';
    }
    out[used] = 0;
    return used;
}
#endif
