#include <stddef.h>
#include <stdint.h>
#include "mini_libc.h"
#define HEAP_SIZE (32 * 1024)

static uint8_t g_heap[HEAP_SIZE];
static size_t g_heap_off;
void *memcpy(void *dest, const void *src, unsigned int n)
{
    char *d = dest;
    const char *s = src;
    while (n--)
        *d++ = *s++;
    return dest;
}

void *memmove(void *dest, const void *src, unsigned int n)
{
    char *d = dest;
    const char *s = src;

    if (d < s) {
        while (n--)
            *d++ = *s++;
    } else {
        d += n;
        s += n;
        while (n--)
            *--d = *--s;
    }
    return dest;
}

void *memset(void *dest, int c, unsigned int n)
{
    unsigned char *d = dest;
    while (n--)
        *d++ = (unsigned char) c;
    return dest;
}

unsigned int strlen(const char *s)
{
    unsigned int len = 0;
    while (*s++)
        len++;
    return len;
}

void *malloc(size_t n) {
    n = (n + 7u) & ~7u;
    if (g_heap_off + n > HEAP_SIZE) return (void*)0;
    void *p = &g_heap[g_heap_off];
    g_heap_off += n;
    return p;
}

void free(void *p) { (void)p; }

void *calloc(size_t nmemb, size_t size) {
    size_t n = nmemb * size;
    void *p = malloc(n);
    if (p) memset(p, 0, n);
    return p;
}
